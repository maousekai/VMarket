#!/usr/bin/env node
// Runs the 15 performance test cases of the test plan, one k6 run each, and stores
// the evidence per round:
//   perf/results/R<round>/<TC>/summary.json   thresholds + p50/p90/p95/p99/max per endpoint/phase
//   perf/results/R<round>/<TC>/metrics.csv.gz raw k6 metrics
//   perf/results/R<round>/<TC>/timeline.csv   per-minute p95 / RPS / failures per endpoint
//   perf/results/R<round>/<TC>/k6.log         console output
//   perf/results/R<round>/<TC>/run.json       verdict (Passed / Failed / Pending + reason), times, config
//
//   node perf/run.mjs --round=1 --tc=PERF-AUTH-01,PERF-CATALOG-01 --tester="Nguyễn Phương Đông"
//   node perf/run.mjs --round=1 --tc=all --tester="..."        (PERF-CART-05 asks before it starts)
//
// Environment: BASE_URL, PERF_PASSWORD, ALLOW_REMOTE, MANIFEST, K6 (path to k6), and
// optionally K6_PROMETHEUS_RW_SERVER_URL to stream metrics to Prometheus/Grafana.
import { spawn, execFileSync } from 'node:child_process';
import { createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync, createWriteStream } from 'node:fs';
import { createGunzip } from 'node:zlib';
import { createInterface } from 'node:readline';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export const TEST_CASES = {
  'PERF-AUTH-01': ['tc/auth.js', 'smoke'],
  'PERF-AUTH-02': ['tc/auth.js', 'baseline'],
  'PERF-AUTH-03': ['tc/auth.js', 'load'],
  'PERF-AUTH-04': ['tc/auth.js', 'spike'],
  'PERF-AUTH-05': ['tc/auth.js', 'soak'],
  'PERF-CATALOG-01': ['tc/catalog.js', 'smoke'],
  'PERF-CATALOG-02': ['tc/catalog.js', 'baseline'],
  'PERF-CATALOG-03': ['tc/catalog.js', 'load'],
  'PERF-CATALOG-04': ['tc/catalog.js', 'spike'],
  'PERF-CATALOG-05': ['tc/catalog.js', 'soak'],
  'PERF-CART-01': ['tc/cart.js', 'smoke'],
  'PERF-CART-02': ['tc/cart.js', 'baseline'],
  'PERF-CART-03': ['tc/cart.js', 'load'],
  'PERF-CART-04': ['tc/mixed.js', 'soak'],
  'PERF-CART-05': ['tc/rate-limit-guard.js', 'guard'],
};

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const flag = (name) => args.includes(`--${name}`);

// ---------- verdict ----------
// Order of precedence (test plan section 10.1):
//   data invariant / unsellable leak crossed -> Failed (no tolerance for wrong money)
//   aborted (credential stop, safety stop, no summary) -> Pending + reason
//   acceptance threshold crossed -> Failed
//   invalid run (guard window changed) or < 200 samples -> Pending + reason
//   otherwise -> Passed
export function verdict(summary, exitCode, log = '') {
  if (!summary) return { result: 'Pending', reason: `aborted: no summary (k6 exit ${exitCode})` };
  const failed = summary.thresholds.filter((t) => !t.ok);
  const data = failed.filter((t) => /cart_invariant_violations|catalog_unsellable_leaks/.test(t.metric));
  if (data.length) return { result: 'Failed', reason: `data invariant: ${data.map((t) => t.metric).join(', ')}` };
  if (exitCode === 108) {
    const why = (log.match(/test aborted: ([^\n"]+)/) || [])[1] || 'test.abort()';
    return { result: 'Pending', reason: `aborted: ${why}` };
  }
  const safety = failed.filter((t) => t.kind === 'abort');
  if (safety.length) return { result: 'Pending', reason: 'aborted: safety stop, unexpected failure > 10 %' };
  const acceptance = failed.filter((t) => t.kind === 'acceptance');
  if (acceptance.length) return { result: 'Failed', reason: acceptance.map((t) => `${t.metric} ${t.expression}`).join('; ') };
  const invalid = failed.filter((t) => t.kind === 'invalid');
  if (invalid.length) return { result: 'Pending', reason: 'invalid run: rate-limit window changed between request 1 and 201' };
  const samples = failed.filter((t) => t.kind === 'samples');
  if (samples.length) return { result: 'Pending', reason: `inconclusive: fewer than 200 samples (${samples.map((t) => t.metric).join(', ')})` };
  if (exitCode !== 0) return { result: 'Pending', reason: `k6 exit ${exitCode} without a failed threshold` };
  return { result: 'Passed', reason: '' };
}

// ---------- per-minute timeline from the raw CSV ----------
async function timeline(csvGz, out) {
  if (!existsSync(csvGz)) return;
  const buckets = new Map(); // "minute|endpoint" -> { durations, failed, total }
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
    const b = buckets.get(key) || { minute, endpoint, phase: extra.get('phase') || '', durations: [], failed: 0, total: 0 };
    const value = Number(cols[header.metric_value]);
    if (metric === 'http_req_duration') b.durations.push(value);
    else {
      b.total++;
      b.failed += value;
    }
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

// ---------- run one TC ----------
const bangkok = (d) => new Date(d.getTime() + 7 * 3600 * 1000).toISOString().replace('Z', '+07:00');

async function ask(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((done) => rl.question(question, done));
  rl.close();
  return answer.trim().toLowerCase();
}

async function runCase(id, round, tester, k6) {
  const [script, profile] = TEST_CASES[id];
  const dir = resolve(here, 'results', `R${round}`, id);
  mkdirSync(dir, { recursive: true });
  const files = { summary: resolve(dir, 'summary.json'), csv: resolve(dir, 'metrics.csv.gz'), log: resolve(dir, 'k6.log'), timeline: resolve(dir, 'timeline.csv') };
  const k6Args = ['run', '-e', `TC=${id}`, '-e', `PROFILE=${profile}`, '-e', `SUMMARY_OUT=${files.summary}`, '--out', `csv=${files.csv}`];
  if (process.env.K6_PROMETHEUS_RW_SERVER_URL) k6Args.push('--out', 'experimental-prometheus-rw', '--tag', `testid=${id}-R${round}`);
  k6Args.push(resolve(here, script));

  console.log(`\n=== ${id}  (${script}, profile ${profile}, round ${round})`);
  const startedAt = new Date();
  const log = createWriteStream(files.log);
  const exitCode = await new Promise((done) => {
    const child = spawn(k6, k6Args, { env: process.env, stdio: ['inherit', 'pipe', 'pipe'] });
    child.stdout.on('data', (b) => (process.stdout.write(b), log.write(b)));
    child.stderr.on('data', (b) => (process.stderr.write(b), log.write(b)));
    child.on('close', (code) => done(code));
  });
  log.end();
  const endedAt = new Date();
  await timeline(files.csv, files.timeline).catch((e) => console.warn(`timeline: ${e.message}`));

  let summary = null;
  try {
    summary = JSON.parse(readFileSync(files.summary, 'utf8'));
  } catch {
    // aborted before handleSummary
  }
  const v = verdict(summary, exitCode, readFileSync(files.log, 'utf8'));
  let manifestRunId = null;
  try {
    manifestRunId = JSON.parse(readFileSync(process.env.MANIFEST || resolve(here, 'fixtures/out/manifest.json'), 'utf8')).runId;
  } catch {
    // no manifest
  }
  const run = {
    tc: id,
    round: Number(round),
    script,
    profile,
    tester,
    startedAt: bangkok(startedAt),
    endedAt: bangkok(endedAt),
    exitCode,
    verdict: v.result,
    reason: v.reason,
    baseUrl: process.env.BASE_URL || 'http://localhost:8080',
    manifestRunId,
    k6: execFileSync(k6, ['version'], { encoding: 'utf8' }).trim(),
    files: Object.fromEntries(Object.entries(files).map(([k, f]) => [k, f.replace(`${resolve(here, '..')}\\`, '').replace(/\\/g, '/')])),
  };
  writeFileSync(resolve(dir, 'run.json'), JSON.stringify(run, null, 2));
  console.log(`=== ${id}: ${v.result}${v.reason ? ` (${v.reason})` : ''}`);
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

  const capacity = wanted.filter((id) => id !== 'PERF-CART-05');
  if (capacity.length && !flag('yes')) {
    console.log('Capacity profile: ENV-P only, gateway restarted with APP_RATE_LIMIT_ENABLED=false (verify it took effect),');
    console.log('Cloudflare skip rule for this IP, one cart-service instance, Grafana open (infra/monitoring).');
    if ((await ask('Ready? [y/N] ')) !== 'y') return;
  }
  const results = [];
  for (const id of wanted) {
    if (id === 'PERF-CART-05' && !flag('yes')) {
      console.log('\nPERF-CART-05 (guard): turn the limiter back ON (default 200/60 s), no other traffic on the site.');
      console.log('The script takes a token, then waits for you to restart the gateway.');
      if ((await ask('Start the guard? [y/N] ')) !== 'y') continue;
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
