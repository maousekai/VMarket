#!/usr/bin/env node
// Runs the performance test cases (perf/report/test-cases.mjs), one k6 run each,
// and stores the evidence per round:
//   perf/results/R<round>/<TC>/summary.json    thresholds, p50/p90/p95/p99/max per endpoint and phase
//   perf/results/R<round>/<TC>/metrics.csv.gz  raw k6 metrics
//   perf/results/R<round>/<TC>/timeline.csv    per-minute p95 / RPS / failures per endpoint
//   perf/results/R<round>/<TC>/k6.log          console output
//   perf/results/R<round>/<TC>/run.json        verdict (Passed / Failed / Pending + reason), times, config
//   perf/results/R<round>/<TC>/companion/      background load (WEB-02, SHIP-02)
//
//   node perf/run.mjs --round=1 --tc=PERF-READ-01,PERF-LOAD-01 --tester="Nguyễn Phương Đông"
//   node perf/run.mjs --round=1 --tc=all --tester="..."
//
// Environment: BASE_URL, WEB_URL, PERF_PASSWORD, SEED_PASSWORD (PERF-SRCH-04),
// ALLOW_REMOTE, MANIFEST, K6 (path to k6), K6_PROMETHEUS_RW_SERVER_URL (optional).
import { spawn, execFileSync } from 'node:child_process';
import { createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync, createWriteStream } from 'node:fs';
import { createGunzip } from 'node:zlib';
import { createInterface } from 'node:readline';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TEST_CASES } from './report/test-cases.mjs';

const here = dirname(fileURLToPath(import.meta.url));
export { TEST_CASES };

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const flag = (name) => args.includes(`--${name}`);

// ---------- verdict ----------
// Precedence (test plan, "Quy tắc kết luận"):
//   data invariant crossed (money, stock, leaks)           -> Failed (no tolerance)
//   blocked precondition (service not deployed, fixture)   -> Pending + reason
//   aborted (credential stop, safety stop, no summary)     -> Pending + reason
//   acceptance threshold crossed                           -> Failed
//   invalid run / degraded dependency / < 200 samples      -> Pending + reason
//   otherwise                                              -> Passed
export function verdict(summary, exitCode, log = '') {
  const blockedReason = (log.match(/BLOCKED: ([^\n"\\]+)/) || [])[1];
  if (summary) {
    const data = summary.thresholds.filter((t) => !t.ok && t.kind === 'data');
    if (data.length) return { result: 'Failed', reason: `data invariant: ${data.map((t) => t.metric).join(', ')}` };
  }
  if (blockedReason) return { result: 'Pending', reason: `blocked: ${blockedReason.trim()}` };
  const missingEnv = (log.match(/Missing required environment variable (\w+)/) || [])[1];
  if (missingEnv) return { result: 'Pending', reason: `not run: environment variable ${missingEnv} is required (see perf/README.md)` };
  if (!summary) return { result: 'Pending', reason: `aborted: no summary (k6 exit ${exitCode})` };
  if (exitCode === 108) return { result: 'Pending', reason: `aborted: ${(log.match(/test aborted: ([^\n"]+)/) || [])[1] || 'test.abort()'}` };
  const failed = summary.thresholds.filter((t) => !t.ok);
  if (failed.some((t) => t.kind === 'abort')) return { result: 'Pending', reason: 'aborted: safety stop, unexpected failure > 10 %' };
  const acceptance = failed.filter((t) => t.kind === 'acceptance');
  if (acceptance.length) return { result: 'Failed', reason: acceptance.map((t) => `${t.metric} ${t.expression}`).join('; ') };
  const invalid = failed.filter((t) => t.kind === 'invalid');
  if (invalid.length) return { result: 'Pending', reason: `invalid run: ${invalid.map((t) => `${t.metric} ${t.expression}`).join('; ')}` };
  if (summary.degraded && summary.degraded.length) return { result: 'Pending', reason: `degraded: ${summary.degraded.join('; ')}` };
  const samples = failed.filter((t) => t.kind === 'samples');
  if (samples.length) return { result: 'Pending', reason: `inconclusive: fewer samples than required (${samples.map((t) => t.metric).join(', ')})` };
  const bp = summary.breakingPoint;
  if (bp && bp.complete === false && bp.lastGoodVus < 200) return { result: 'Pending', reason: `inconclusive: stress run stopped at the ${bp.reachedVus} VU step before reaching 200 VUs` };
  if (bp && bp.lastGoodVus < 200) return { result: 'Failed', reason: `breaking point below 200 VUs (last good step ${summary.breakingPoint.lastGoodVus})` };
  if (exitCode !== 0) return { result: 'Pending', reason: `k6 exit ${exitCode} without a failed threshold` };
  return { result: 'Passed', reason: '' };
}

// ---------- per-minute timeline from the raw CSV ----------
async function timeline(csvGz, out) {
  if (!existsSync(csvGz)) return;
  const buckets = new Map();
  let header;
  const lines = createInterface({ input: createReadStream(csvGz).pipe(createGunzip()) });
  for await (const line of lines) {
    const cols = line.split(',');
    if (!header) {
      header = Object.fromEntries(cols.map((c, i) => [c, i]));
      continue;
    }
    const metric = cols[header.metric_name];
    if (metric !== 'http_req_duration' && metric !== 'http_req_failed') continue;
    const extra = new URLSearchParams(cols[header.extra_tags] || '');
    const endpoint = extra.get('endpoint');
    if (!endpoint || endpoint.startsWith('setup:')) continue;
    const minute = new Date(Number(cols[header.timestamp]) * 1000).toISOString().slice(0, 16);
    const key = `${minute}|${endpoint}`;
    const b = buckets.get(key) || { minute, endpoint, phase: extra.get('phase') || '', durations: [], failed: 0 };
    const value = Number(cols[header.metric_value]);
    if (metric === 'http_req_duration') b.durations.push(value);
    else b.failed += value;
    buckets.set(key, b);
  }
  const p = (arr, q) => {
    if (!arr.length) return '';
    const s = [...arr].sort((a, b) => a - b);
    return Math.round(s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)]);
  };
  const rows = ['minute_utc,endpoint,phase,requests,rps,p50_ms,p95_ms,p99_ms,failed'];
  for (const b of [...buckets.values()].sort((x, y) => (x.minute + x.endpoint).localeCompare(y.minute + y.endpoint))) {
    rows.push([b.minute, `"${b.endpoint}"`, b.phase, b.durations.length, (b.durations.length / 60).toFixed(2), p(b.durations, 0.5), p(b.durations, 0.95), p(b.durations, 0.99), b.failed].join(','));
  }
  writeFileSync(out, rows.join('\n') + '\n');
}

// ---------- run ----------
const bangkok = (d) => new Date(d.getTime() + 7 * 3600 * 1000).toISOString().replace('Z', '+07:00');
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

async function ask(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((done) => rl.question(question, done));
  rl.close();
  return answer.trim().toLowerCase();
}

function startK6(k6, { id, round, script, profile, env = {} }, dir, label) {
  mkdirSync(dir, { recursive: true });
  const files = { summary: resolve(dir, 'summary.json'), csv: resolve(dir, 'metrics.csv.gz'), log: resolve(dir, 'k6.log'), timeline: resolve(dir, 'timeline.csv') };
  const k6Args = ['run', '-e', `TC=${id}`, '-e', `PROFILE=${profile}`, '-e', `SUMMARY_OUT=${files.summary}`, '--out', `csv=${files.csv}`];
  for (const [k, v] of Object.entries(env)) k6Args.push('-e', `${k}=${v}`);
  if (process.env.K6_PROMETHEUS_RW_SERVER_URL) k6Args.push('--out', 'experimental-prometheus-rw', '--tag', `testid=${id}-R${round}${label ? `-${label}` : ''}`);
  k6Args.push(resolve(here, script));
  const log = createWriteStream(files.log);
  const child = spawn(k6, k6Args, { env: { ...process.env, K6_BROWSER_HEADLESS: process.env.K6_BROWSER_HEADLESS || 'true' }, stdio: ['inherit', 'pipe', 'pipe'] });
  const prefix = label ? `[${label}] ` : '';
  child.stdout.on('data', (b) => (label ? null : process.stdout.write(b), log.write(b)));
  child.stderr.on('data', (b) => (process.stderr.write(prefix + b), log.write(b)));
  const done = new Promise((resolveDone) => child.on('close', (code) => (log.end(), resolveDone(code))));
  return { files, done, child };
}

async function finish(files, exitCode) {
  await timeline(files.csv, files.timeline).catch((e) => console.warn(`timeline: ${e.message}`));
  let summary = null;
  try {
    summary = JSON.parse(readFileSync(files.summary, 'utf8'));
  } catch {
    // aborted before handleSummary
  }
  return { summary, verdict: verdict(summary, exitCode, existsSync(files.log) ? readFileSync(files.log, 'utf8') : '') };
}

async function runCase(id, round, tester, k6) {
  const def = TEST_CASES[id];
  const dir = resolve(here, 'results', `R${round}`, id);
  console.log(`\n=== ${id}  (${def.script}, profile ${def.profile}${def.companion ? `, with background ${def.companion.script}` : ''}, round ${round})`);
  const startedAt = new Date();

  let companion = null;
  if (def.companion) {
    companion = startK6(k6, { id, round, ...def.companion }, resolve(dir, 'companion'), 'background');
    console.log('background load started; waiting 90 s for warm-up and ramp-up');
    await sleep(90000);
  }
  const main = startK6(k6, { id, round, ...def }, dir);
  const exitCode = await main.done;
  let background = null;
  if (companion) {
    const code = await companion.done;
    background = await finish(companion.files, code);
  }
  const { verdict: v } = await finish(main.files, exitCode);
  let result = v;
  if (background && v.result === 'Passed' && background.verdict.result !== 'Passed') {
    result = { result: background.verdict.result === 'Failed' ? 'Failed' : 'Pending', reason: `background load: ${background.verdict.reason}` };
  }

  let manifestRunId = null;
  try {
    manifestRunId = JSON.parse(readFileSync(process.env.MANIFEST || resolve(here, 'fixtures/out/manifest.json'), 'utf8')).runId;
  } catch {
    // no manifest
  }
  const run = {
    tc: id,
    round: Number(round),
    script: def.script,
    profile: def.profile,
    env: def.env || {},
    companion: def.companion || null,
    tester,
    startedAt: bangkok(startedAt),
    endedAt: bangkok(new Date()),
    exitCode,
    verdict: result.result,
    reason: result.reason,
    backgroundVerdict: background ? background.verdict : null,
    baseUrl: process.env.BASE_URL || 'http://localhost:8080',
    webUrl: process.env.WEB_URL || 'http://localhost:5173',
    manifestRunId,
    k6: execFileSync(k6, ['version'], { encoding: 'utf8' }).trim(),
  };
  writeFileSync(resolve(dir, 'run.json'), JSON.stringify(run, null, 2));
  console.log(`=== ${id}: ${result.result}${result.reason ? ` (${result.reason})` : ''}`);
  return run;
}

async function main() {
  const round = option('round');
  const tester = option('tester', '');
  const k6 = process.env.K6 || 'k6';
  if (!['1', '2', '3'].includes(round)) throw new Error('--round=1|2|3 is required');
  if (!tester) throw new Error('--tester="<name of the person running the test>" is required');
  const wanted = option('tc', 'all') === 'all' ? Object.keys(TEST_CASES) : option('tc').split(',').map((s) => s.trim());
  for (const id of wanted) if (!TEST_CASES[id]) throw new Error(`unknown test case ${id}`);

  if (!flag('yes')) {
    console.log('Before running: ENV-P (>= 4 vCPU / 8 GB, SRS 5.2), APP_RATE_LIMIT_ENABLED=false (verified), k6 on a separate machine,');
    console.log('one cart-service instance, Grafana open (infra/monitoring), fixture manifest present.');
    if ((await ask('Ready? [y/N] ')) !== 'y') return;
  }
  const results = [];
  for (const id of wanted) {
    if (id === 'PERF-LOAD-07' && !flag('yes')) {
      console.log('\nPERF-LOAD-07: when the hold phase starts (~90 s), stop ai-search-service and the recommendation service.');
      if ((await ask('Start? [y/N] ')) !== 'y') continue;
    }
    results.push(await runCase(id, round, tester, k6));
  }
  console.log('\nRound summary');
  for (const r of results) console.log(`  ${r.tc.padEnd(16)} ${r.verdict.padEnd(8)} ${r.reason}`);
  console.log(`\nFill the Excel report: node perf/report/fill-excel.mjs --round=${round}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
