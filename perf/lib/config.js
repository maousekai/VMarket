// Shared configuration for every k6 script in perf/.
// All values come from environment variables (k6 run -e NAME=value ...).
import exec from 'k6/execution';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

// Test case id (PERF-AUTH-03 ...) and load profile. run.mjs sets both from the TC table.
export const TC = __ENV.TC || '';
export const PROFILE = (__ENV.PROFILE || 'smoke').toLowerCase();

// Test buyers created by fixtures/prepare.mjs. VU n -> buyer ((n - 1) % BUYER_COUNT).
export const BUYER_COUNT = intEnv('BUYER_COUNT', 300);
export const BUYER_PREFIX = __ENV.BUYER_PREFIX || 'perf.buyer';
export const BUYER_DOMAIN = 'perf.vmarket.test'; // .test is reserved, never receives mail

// Thresholds from the test plan (sheet "Test Cases", section 10.1).
export const READ_P95_MS = 500; // NFR-PERF-01 (R): GET /api/products, /api/products/{id}, /api/cart
export const LOGIN_P95_MS = 1000; // (Q) login
export const MUTATION_P95_MS = 1000; // (Q) PATCH cart
export const MAX_FAILURE_RATE = 0.01; // (Q) unexpected HTTP failure < 1 %
export const MIN_CHECK_RATE = 0.99; // (Q) business checks >= 99 %
export const MIN_SAMPLES = 200; // (Q) samples per endpoint in the measured phase
export const ABORT_FAILURE_RATE = 0.1; // safety stop: unexpected failure > 10 % for 60 s

export function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') return fallback;
  const value = parseInt(raw, 10);
  if (Number.isNaN(value) || value < 0) throw new Error(`${name} must be a non-negative integer, got "${raw}"`);
  return value;
}

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`Missing required environment variable ${name} (see perf/README.md)`);
  return value;
}

export function isLocalTarget() {
  return /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\]|host\.docker\.internal)(:\d+)?$/.test(BASE_URL);
}

// Scripts that change data (cart quantities, sessions) refuse a non-local gateway
// unless the operator confirms it is the dedicated performance environment (ENV-P).
export function guardWritableTarget(scriptName) {
  if (isLocalTarget() || __ENV.ALLOW_REMOTE === 'true') return;
  throw new Error(
    `${scriptName} changes data and BASE_URL=${BASE_URL} is not local. ` +
      'Only run it against the performance environment (ENV-P), then pass -e ALLOW_REMOTE=true.',
  );
}

// ---------------------------------------------------------------------------
// Load profiles (sheet "Test Cases", 10.1 "Profile" table and workload rule 6).
// Each stage carries the phase name its requests are tagged with. Warm-up, ramps
// and the spike itself are kept in the raw metrics but excluded from acceptance.
// ---------------------------------------------------------------------------
const S = (seconds, target, phase) => ({ seconds, target, phase });

function profileStages(profile, peak) {
  const scale = (n) => Math.max(1, Math.round((n * peak) / 200));
  switch (profile) {
    case 'smoke': // 1 VU x 1 min; the first 15 s warm the connection and caches
      return { start: 1, stages: [S(15, 1, 'warmup'), S(60, 1, 'hold')], accept: ['hold'] };
    case 'baseline': // warm-up 1 min @10, ramp 30 s to 50, hold 5 min
      return {
        start: scale(10),
        stages: [S(60, scale(10), 'warmup'), S(30, scale(50), 'ramp'), S(300, scale(50), 'hold'), S(30, 0, 'rampdown')],
        accept: ['hold'],
      };
    case 'load': // warm-up, ramp 30 s to 200, hold 10 min
      return {
        start: scale(10),
        stages: [S(60, scale(10), 'warmup'), S(30, peak, 'ramp'), S(600, peak, 'hold'), S(30, 0, 'rampdown')],
        accept: ['hold'],
      };
    case 'soak': // warm-up, ramp 30 s to 200, hold 30 min
      return {
        start: scale(10),
        stages: [S(60, scale(10), 'warmup'), S(30, peak, 'ramp'), S(1800, peak, 'hold'), S(30, 0, 'rampdown')],
        accept: ['hold'],
      };
    case 'spike': {
      // Stable 2 min at 50, 50 -> 300 in 10 s, hold 2 min, back to 50, observe 5 min.
      // Acceptance: the last 3 minutes of the recovery window (back to the 50-VU
      // thresholds within 5 minutes). The 300-VU phase is diagnostic only.
      const base = scale(50);
      const top = scale(300);
      return {
        start: scale(10),
        stages: [
          S(60, scale(10), 'warmup'),
          S(30, base, 'ramp'),
          S(120, base, 'base'),
          S(10, top, 'spike-up'),
          S(120, top, 'spike'),
          S(10, base, 'spike-down'),
          S(120, base, 'recover-early'),
          S(180, base, 'recover'),
          S(30, 0, 'rampdown'),
        ],
        accept: ['recover'],
      };
    }
    default:
      throw new Error(`Unknown PROFILE "${profile}". Use smoke, baseline, load, spike or soak.`);
  }
}

// peak = VUs this scenario reaches at 200 total (e.g. 120 for the catalog share of the mixed run).
export function profileFor(peak = 200, profile = PROFILE) {
  return profileStages(profile, peak);
}

export function acceptPhases(profile = PROFILE) {
  return profileStages(profile, 200).accept;
}

// k6 scenario for this profile. Spike peaks at 300 when peak = 200.
export function scenarioFor(execName, peak = 200, profile = PROFILE) {
  const { start, stages } = profileStages(profile, peak);
  return {
    executor: 'ramping-vus',
    exec: execName,
    startVUs: start,
    stages: stages.map((s) => ({ duration: `${s.seconds}s`, target: s.target })),
    gracefulRampDown: '30s',
    gracefulStop: '30s',
  };
}

export function peakVus(scenario) {
  return Math.max(scenario.startVUs || 0, ...scenario.stages.map((s) => s.target));
}

// VU ids are unique across all scenarios of a test. Each logged-in VU maps to
// buyer ((id - 1) % BUYER_COUNT); if the total peak exceeds BUYER_COUNT, two VUs
// share one account (and its cart and row locks). Refuse to start instead.
export function assertBuyersCover(scenarios, available = BUYER_COUNT) {
  const peak = Object.values(scenarios).reduce((sum, s) => sum + peakVus(s), 0);
  if (peak > available) {
    throw new Error(
      `Peak VUs (${peak}, PROFILE=${PROFILE}) > available test buyers (${available}). ` +
        'Create more buyers with fixtures/prepare.mjs (PERF_BUYERS) or use a smaller profile.',
    );
  }
}

// Phase of "now" for the running scenario. Every request is tagged with it (api.js).
const timelines = {};
export function currentPhase() {
  let scenario;
  try {
    scenario = exec.scenario;
  } catch (_) {
    return 'setup'; // setup()/teardown() run outside any scenario
  }
  if (!scenario || !scenario.name) return 'setup';
  let timeline = timelines[scenario.name];
  if (!timeline) {
    const peak = SCENARIO_PEAKS[scenario.name] || 200;
    let at = 0;
    timeline = profileStages(PROFILE, peak).stages.map((s) => ({ end: (at += s.seconds * 1000), phase: s.phase }));
    timelines[scenario.name] = timeline;
  }
  const elapsed = Date.now() - scenario.startTime;
  for (const step of timeline) if (elapsed < step.end) return step.phase;
  return 'rampdown';
}

// Scripts with several scenarios register each scenario's 200-VU share here.
export const SCENARIO_PEAKS = {};

// ---------------------------------------------------------------------------
// Thresholds. Each entry is classified so the report can tell
//   acceptance -> Failed when crossed
//   samples    -> fewer than MIN_SAMPLES in the measured phase: inconclusive (Pending)
//   info       -> reported only (always passes)
//   abort      -> safety stop
// ---------------------------------------------------------------------------
export const THRESHOLD_KINDS = {}; // "metric|expression" -> kind

function add(target, metric, expression, kind, extra) {
  THRESHOLD_KINDS[`${metric}|${expression}`] = kind;
  (target[metric] = target[metric] || []).push(extra ? { threshold: expression, ...extra } : expression);
}

// endpoints: { tag: p95 ms } (acceptance) ; info: [tag, ...] (latency reported, no NFR)
// business: { metricName: expression } (acceptance, e.g. cart invariants)
export function buildThresholds({ endpoints = {}, info = [], business = {}, profile = PROFILE } = {}) {
  const t = {};
  const smoke = profile === 'smoke';
  const phases = acceptPhases(profile);

  // Safety stop over the whole run (rule 9).
  add(t, 'http_req_failed', `rate<${ABORT_FAILURE_RATE}`, 'abort', { abortOnFail: true, delayAbortEval: '60s' });

  for (const phase of phases) {
    add(t, `http_req_failed{phase:${phase}}`, smoke ? 'rate==0' : `rate<${MAX_FAILURE_RATE}`, 'acceptance');
    add(t, `checks{phase:${phase}}`, smoke ? 'rate==1' : `rate>=${MIN_CHECK_RATE}`, 'acceptance');
    for (const [endpoint, p95] of Object.entries(endpoints)) {
      const tags = `{endpoint:${endpoint},phase:${phase}}`;
      if (smoke) {
        // Smoke has too few samples for a p95 conclusion: report it only.
        add(t, `http_req_duration${tags}`, `p(95)>=0`, 'info');
      } else {
        add(t, `http_req_duration${tags}`, `p(95)<=${p95}`, 'acceptance');
        add(t, `http_reqs${tags}`, `count>=${MIN_SAMPLES}`, 'samples');
      }
    }
    for (const endpoint of info) add(t, `http_req_duration{endpoint:${endpoint},phase:${phase}}`, 'p(95)>=0', 'info');
  }
  for (const [metric, expression] of Object.entries(business)) {
    const abort = metric === 'cart_invariant_violations'; // data corruption stops the run (rule 9)
    add(t, metric, expression, 'acceptance', abort ? { abortOnFail: true } : undefined);
  }
  return t;
}

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];
