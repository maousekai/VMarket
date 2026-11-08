// handleSummary: writes a machine-readable summary (SUMMARY_OUT) that run.mjs turns
// into a verdict, plus a short text table on stdout. Each threshold carries its
// kind (acceptance / samples / info / abort) from config.buildThresholds.
import { BASE_URL, PROFILE, TC, THRESHOLD_KINDS, acceptPhases } from './config.js';

const fmt = (v) => (v === undefined || v === null ? '-' : Math.round(v * 10) / 10);

export function summarize(data, extra = {}) {
  const thresholds = [];
  const endpoints = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    for (const [expression, result] of Object.entries(metric.thresholds || {})) {
      thresholds.push({
        metric: name,
        expression,
        ok: result.ok,
        kind: THRESHOLD_KINDS[`${name}|${expression}`] || 'acceptance',
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
        count: metric.values.count,
        rps: counts ? counts.values.rate : undefined,
      };
    }
  }
  const counter = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  const gauge = (n) => (data.metrics[n] ? data.metrics[n].values.value : undefined);
  const rate = (n) => (data.metrics[n] ? data.metrics[n].values.rate : undefined);
  return {
    tc: TC,
    profile: PROFILE,
    baseUrl: BASE_URL,
    acceptPhases: acceptPhases(),
    durationMs: data.state.testRunDurationMs,
    httpReqs: counter('http_reqs'),
    rps: data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0,
    failureRate: rate('http_req_failed'),
    checkRate: rate('checks'),
    vusMax: gauge('vus_max'),
    counters: {
      sessions_created: counter('sessions_created'),
      sessions_closed: counter('sessions_closed'),
      cart_invariant_violations: counter('cart_invariant_violations'),
      catalog_unsellable_leaks: counter('catalog_unsellable_leaks'),
    },
    thresholds,
    endpoints,
    ...extra,
  };
}

export function textReport(s) {
  const lines = [`\n${s.tc || '(no TC)'}  profile=${s.profile}  target=${s.baseUrl}  acceptance phase=${s.acceptPhases}`];
  lines.push(`requests=${s.httpReqs}  rps=${fmt(s.rps)}  failure=${fmt((s.failureRate || 0) * 100)}%  checks=${fmt((s.checkRate || 0) * 100)}%  vus_max=${s.vusMax}`);
  lines.push('\nendpoint @phase                                   count     p50     p95     p99     max (ms)');
  for (const [k, e] of Object.entries(s.endpoints)) {
    lines.push(`${k.padEnd(48)} ${String(e.count).padStart(6)} ${String(fmt(e.p50)).padStart(7)} ${String(fmt(e.p95)).padStart(7)} ${String(fmt(e.p99)).padStart(7)} ${String(fmt(e.max)).padStart(7)}`);
  }
  lines.push('\nthresholds');
  for (const t of s.thresholds) {
    if (t.kind === 'info') continue;
    lines.push(`  ${t.ok ? 'PASS' : 'FAIL'}  [${t.kind}] ${t.metric}: ${t.expression}`);
  }
  return lines.join('\n') + '\n';
}

// Use as: export function handleSummary(data) { return writeSummary(data); }
export function writeSummary(data, extra) {
  const s = summarize(data, extra);
  const out = { stdout: textReport(s) };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(s, null, 2);
  return out;
}
