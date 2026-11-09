// handleSummary helpers: a machine-readable summary (SUMMARY_OUT) that run.mjs turns
// into a verdict, plus a short text table on stdout. Each threshold carries its kind
// (acceptance / samples / info / abort / data / invalid) from config.buildThresholds.
import { BASE_URL, MAX_FAILURE_RATE, PROFILE, STRESS_STEPS, TC, THRESHOLD_KINDS, acceptPhases } from './config.js';

const fmt = (v) => (v === undefined || v === null || Number.isNaN(v) ? '-' : Math.round(v * 10) / 10);
const BUILTIN = /^(http_|data_|iteration|vus|checks|group_duration|ws_|browser_)/;

export function summarize(data, extra = {}) {
  const thresholds = [];
  const endpoints = {};
  const custom = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    for (const [expression, result] of Object.entries(metric.thresholds || {})) {
      const base = name.replace(/\{.*$/, '');
      thresholds.push({
        metric: name,
        expression,
        ok: result.ok,
        kind: THRESHOLD_KINDS[`${name}|${expression}`] || THRESHOLD_KINDS[`${base}|${expression}`] || 'acceptance',
      });
    }
    const m = name.match(/^http_req_duration\{endpoint:(.+),phase:(.+)\}$/);
    if (m) {
      const counts = data.metrics[`http_reqs{endpoint:${m[1]},phase:${m[2]}}`];
      endpoints[`${m[1]} @${m[2]}`] = {
        endpoint: m[1],
        phase: m[2],
        p50: metric.values.med,
        p90: metric.values['p(90)'],
        p95: metric.values['p(95)'],
        p99: metric.values['p(99)'],
        max: metric.values.max,
        avg: metric.values.avg,
        count: counts ? counts.values.count : metric.values.count,
        rps: counts ? counts.values.rate : undefined,
      };
    } else if (!BUILTIN.test(name)) {
      custom[name] = metric.values;
    }
  }
  const rate = (n) => (data.metrics[n] ? data.metrics[n].values.rate : undefined);
  return {
    tc: TC,
    profile: PROFILE,
    baseUrl: BASE_URL,
    acceptPhases: acceptPhases(),
    durationMs: data.state.testRunDurationMs,
    httpReqs: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    rps: data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0,
    failureRate: rate('http_req_failed'),
    checkRate: rate('checks'),
    vusMax: data.metrics.vus_max ? data.metrics.vus_max.values.value : undefined,
    thresholds,
    endpoints,
    custom,
    ...extra,
  };
}

// Stress: first step where an endpoint's p95 exceeds its budget or failures exceed 1 %.
// A step with no request is a step the run never reached (aborted early, e.g. by a
// data invariant): it is reported as not reached, never as passed, and the
// breaking point is then incomplete rather than "every step good".
export function breakingPoint(data, budgets) {
  const steps = [];
  for (const n of STRESS_STEPS) {
    const failed = data.metrics[`http_req_failed{phase:step-${n}}`];
    const requests = failed ? (failed.values.passes || 0) + (failed.values.fails || 0) : 0;
    if (requests === 0) {
      steps.push({ vus: n, reached: false, requests: 0, failureRate: null, overBudget: [], ok: null });
      continue;
    }
    const over = Object.entries(budgets)
      .map(([endpoint, p95]) => {
        const m = data.metrics[`http_req_duration{endpoint:${endpoint},phase:step-${n}}`];
        return m && m.values.count > 0 && m.values['p(95)'] > p95 ? `${endpoint} p95 ${Math.round(m.values['p(95)'])} > ${p95}` : null;
      })
      .filter(Boolean);
    const failure = failed.values.rate;
    steps.push({ vus: n, reached: true, requests, failureRate: failure, overBudget: over, ok: over.length === 0 && failure < MAX_FAILURE_RATE });
  }
  return stressOutcome(steps);
}

// lastGoodVus: highest step that passed before the first failing or unreached step
// (0 when none passed). complete: every step ran, or a failing step was found.
export function stressOutcome(steps) {
  const stop = steps.findIndex((s) => s.ok !== true);
  const first = stop >= 0 && steps[stop].ok === false ? steps[stop] : null;
  const lastGood = stop === -1 ? steps[steps.length - 1] : steps[stop - 1];
  return {
    lastGoodVus: lastGood ? lastGood.vus : 0,
    firstBadVus: first ? first.vus : null,
    complete: stop === -1 || first !== null,
    reachedVus: Math.max(0, ...steps.filter((s) => s.reached).map((s) => s.vus)),
    steps,
  };
}

export function stressText(bp) {
  if (!bp.complete) return `stress incomplete: run stopped at the ${bp.reachedVus} VU step; last good step ${bp.lastGoodVus} VUs, no failing step reached`;
  return `stress: last good step ${bp.lastGoodVus} VUs, first failing step ${bp.firstBadVus || 'none'}`;
}

export function textReport(s) {
  const lines = [`\n${s.tc || '(no TC)'}  profile=${s.profile}  target=${s.baseUrl}  acceptance phase=${s.acceptPhases}`];
  lines.push(`requests=${s.httpReqs}  rps=${fmt(s.rps)}  failure=${fmt((s.failureRate || 0) * 100)}%  checks=${fmt((s.checkRate || 0) * 100)}%  vus_max=${s.vusMax}`);
  if (Object.keys(s.endpoints).length) {
    lines.push('\nendpoint @phase                                         count     p50     p95     p99     max (ms)');
    for (const [k, e] of Object.entries(s.endpoints)) {
      lines.push(`${k.padEnd(54)} ${String(e.count).padStart(6)} ${String(fmt(e.p50)).padStart(7)} ${String(fmt(e.p95)).padStart(7)} ${String(fmt(e.p99)).padStart(7)} ${String(fmt(e.max)).padStart(7)}`);
    }
  }
  for (const [k, v] of Object.entries(s.custom)) {
    lines.push(`${k}: ${Object.entries(v).map(([a, b]) => `${a}=${fmt(b)}`).join(' ')}`);
  }
  if (s.breakingPoint) lines.push(`\n${stressText(s.breakingPoint)}`);
  if (s.degraded && s.degraded.length) lines.push(`\nDEGRADED: ${s.degraded.join('; ')}`);
  lines.push('\nthresholds');
  for (const t of s.thresholds) if (t.kind !== 'info') lines.push(`  ${t.ok ? 'PASS' : 'FAIL'}  [${t.kind}] ${t.metric}: ${t.expression}`);
  return lines.join('\n') + '\n';
}

export function writeSummary(data, extra) {
  const s = summarize(data, extra);
  const out = { stdout: textReport(s) };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(s, null, 2);
  return out;
}
