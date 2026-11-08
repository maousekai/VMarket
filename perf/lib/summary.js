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
export function breakingPoint(data, budgets) {
  const steps = [];
  for (const n of STRESS_STEPS) {
    const failed = data.metrics[`http_req_failed{phase:step-${n}}`];
    const over = Object.entries(budgets)
      .map(([endpoint, p95]) => {
        const m = data.metrics[`http_req_duration{endpoint:${endpoint},phase:step-${n}}`];
        return m && m.values.count > 0 && m.values['p(95)'] > p95 ? `${endpoint} p95 ${Math.round(m.values['p(95)'])} > ${p95}` : null;
      })
      .filter(Boolean);
    const failure = failed ? failed.values.rate : 0;
    steps.push({ vus: n, failureRate: failure, overBudget: over, ok: over.length === 0 && failure < MAX_FAILURE_RATE });
  }
  const first = steps.find((s) => !s.ok);
  return { lastGoodVus: first ? (steps[steps.indexOf(first) - 1] || { vus: 0 }).vus : STRESS_STEPS[STRESS_STEPS.length - 1], firstBadVus: first ? first.vus : null, steps };
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
  if (s.breakingPoint) lines.push(`\nstress: last good step ${s.breakingPoint.lastGoodVus} VUs, first failing step ${s.breakingPoint.firstBadVus || 'none'}`);
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
