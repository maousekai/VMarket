// Shared configuration for every k6 script in perf/.
// All values come from environment variables (k6 run -e NAME=value ...).
// Requirement source: SRS v1.1, section 6.1 (NFR-PERF-01..05).
import exec from 'k6/execution';

// API gateway (REST). WEB_URL is the web front end (NFR-PERF-04); behind a tunnel
// both are usually the same origin.
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
export const WEB_URL = (__ENV.WEB_URL || 'http://localhost:5173').replace(/\/$/, '');

export const TC = __ENV.TC || '';
export const PROFILE = (__ENV.PROFILE || 'smoke').toLowerCase();

// Thresholds. R = SRS requirement, Q = proposed by the test plan.
export const NFR = {
  READ_P95_MS: 500, // NFR-PERF-01 (R)
  SEARCH_P95_MS: 1000, // NFR-PERF-02 (R)
  SUGGEST_P95_MS: 300, // NFR-PERF-02 (R)
  HOME_LCP_MS: 3000, // NFR-PERF-04 (R)
  SHIP_LATENCY_MS: 5000, // NFR-PERF-05 (R)
  IMAGE_SEARCH_P95_MS: 3000, // NFR-PERF-05 (R)
  INDEX_SYNC_S: 60, // FR-SRCH-04 / UC-11 (R)
  MUTATION_P95_MS: 1000, // (Q) login, cart writes, cancel
  ORDER_P95_MS: 2000, // (Q) place order (cross-service stock reservation)
};
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

// Scripts that change data refuse a non-local gateway unless the operator confirms
// it is the dedicated performance environment (ENV-P).
export function guardWritableTarget(scriptName) {
  if (isLocalTarget() || __ENV.ALLOW_REMOTE === 'true') return;
  throw new Error(
    `${scriptName} changes data and BASE_URL=${BASE_URL} is not local. ` +
      'Only run it against the performance environment (ENV-P), then pass -e ALLOW_REMOTE=true.',
  );
}

// A precondition that is not met (service not deployed, fixture missing) makes the
// run "blocked": run.mjs records Pending with this reason instead of Failed.
export function blocked(reason) {
  throw new Error(`BLOCKED: ${reason}`);
}

// ---------------------------------------------------------------------------
// Load profiles. `peak` is a scenario's share of 200 VUs (e.g. 70 for guest
// browsing in the mixed run). Every stage carries the phase its requests are
// tagged with; only the acceptance phases count for the thresholds. Warm-up,
// ramps, the 300-VU spike and the stress steps stay in the raw metrics.
// ---------------------------------------------------------------------------
const S = (seconds, target, phase) => ({ seconds, target, phase });
const SOAK_SECONDS = intEnv('SOAK_MINUTES', 60) * 60;
export const STRESS_STEPS = [100, 150, 200, 250, 300, 350, 400];

function profileStages(profile, peak) {
  const scale = (n) => Math.max(1, Math.round((n * peak) / 200));
  const warm = [S(60, scale(10), 'warmup')];
  const hold = (seconds, target) => [...warm, S(30, target, 'ramp'), S(seconds, target, 'hold'), S(30, 0, 'rampdown')];
  switch (profile) {
    case 'smoke': // script and data check, 1 VU per scenario
      return { start: 1, stages: [S(15, 1, 'warmup'), S(60, 1, 'hold')], accept: ['hold'] };
    case 'baseline': // 50 VUs in total, 5 min
      return { start: scale(10), stages: hold(300, scale(50)), accept: ['hold'] };
    case 'load': // 200 VUs, 10 min
      return { start: scale(10), stages: hold(600, peak), accept: ['hold'] };
    case 'load15': // 200 VUs, 15 min: the official NFR-PERF-03 run
      return { start: scale(10), stages: hold(900, peak), accept: ['hold'] };
    case 'soak': // 200 VUs, 60 min (SOAK_MINUTES)
      return { start: scale(10), stages: hold(SOAK_SECONDS, peak), accept: ['hold'] };
    case 'spike': // 2 min @50, 50 -> 300 in 10 s, 2 min, back to 50, observe 5 min
      return {
        start: scale(10),
        stages: [
          ...warm,
          S(30, scale(50), 'ramp'),
          S(120, scale(50), 'base'),
          S(10, scale(300), 'spike-up'),
          S(120, scale(300), 'spike'),
          S(10, scale(50), 'spike-down'),
          S(120, scale(50), 'recover-early'),
          S(180, scale(50), 'recover'),
          S(30, 0, 'rampdown'),
        ],
        accept: ['recover'],
      };
    case 'stress': {
      // 100 -> 400 VUs in steps of 50 (3 min each) to find the breaking point,
      // then back to 50 VUs: the system must recover within 5 minutes.
      const stages = [...warm, S(30, scale(STRESS_STEPS[0]), 'ramp')];
      STRESS_STEPS.forEach((n, i) => {
        if (i > 0) stages.push(S(20, scale(n), 'ramp'));
        stages.push(S(180, scale(n), `step-${n}`));
      });
      stages.push(S(30, scale(50), 'ramp'), S(120, scale(50), 'recover-early'), S(180, scale(50), 'recover'), S(30, 0, 'rampdown'));
      return { start: scale(10), stages, accept: ['recover'] };
    }
    default:
      throw new Error(`Unknown PROFILE "${profile}". Use smoke, baseline, load, load15, soak, spike or stress.`);
  }
}

export const profileFor = (peak = 200, profile = PROFILE) => profileStages(profile, peak);
export const acceptPhases = (profile = PROFILE) => profileStages(profile, 200).accept;
export const profileSeconds = (profile = PROFILE) => profileStages(profile, 200).stages.reduce((n, s) => n + s.seconds, 0);
export const measuredStartSeconds = (profile = PROFILE) => {
  let at = 0;
  for (const s of profileStages(profile, 200).stages) {
    if (s.phase === 'hold' || s.phase === 'base') return at;
    at += s.seconds;
  }
  return 0;
};

export function scenarioFor(execName, peak = 200, profile = PROFILE) {
  const { start, stages } = profileStages(profile, peak);
  SCENARIO_PEAKS[execName] = peak;
  return {
    executor: 'ramping-vus',
    exec: execName,
    startVUs: start,
    stages: stages.map((s) => ({ duration: `${s.seconds}s`, target: s.target })),
    gracefulRampDown: '30s',
    gracefulStop: '30s',
  };
}

export const peakVus = (scenario) => Math.max(scenario.startVUs || 0, ...scenario.stages.map((s) => s.target));
export const totalPeak = (scenarios) => Object.values(scenarios).reduce((n, s) => n + (s.stages ? peakVus(s) : s.vus || 1), 0);

// Phase of "now" for the running scenario. api.js tags every request with it.
export const SCENARIO_PEAKS = {};
const timelines = {};
export function currentPhase() {
  let scenario;
  try {
    scenario = exec.scenario;
  } catch (_) {
    return 'setup';
  }
  if (!scenario || !scenario.name) return 'setup';
  let timeline = timelines[scenario.name];
  if (!timeline) {
    let at = 0;
    timeline = profileStages(PROFILE, SCENARIO_PEAKS[scenario.name] || 200).stages.map((s) => ({ end: (at += s.seconds * 1000), phase: s.phase }));
    timelines[scenario.name] = timeline;
  }
  const elapsed = Date.now() - scenario.startTime;
  for (const step of timeline) if (elapsed < step.end) return step.phase;
  return 'rampdown';
}

// ---------------------------------------------------------------------------
// Thresholds, each classified for the verdict (run.mjs):
//   acceptance -> Failed when crossed      samples -> < MIN_SAMPLES: Pending (inconclusive)
//   info       -> reported only            abort   -> safety stop
//   data       -> data invariant (money, stock, leaks): Failed, no tolerance
// ---------------------------------------------------------------------------
export const THRESHOLD_KINDS = {};

export function addThreshold(target, metric, expression, kind, extra) {
  THRESHOLD_KINDS[`${metric}|${expression}`] = kind;
  (target[metric] = target[metric] || []).push(extra ? { threshold: expression, ...extra } : expression);
}

// endpoints: { tag: p95 ms } acceptance per endpoint in the measured phase
// info: [tag] latency reported only; data: { metric: expression } invariants
// custom: [[metric, expression, kind]] any other threshold
export function buildThresholds({ endpoints = {}, info = [], data = {}, custom = [], profile = PROFILE, minSamples = MIN_SAMPLES } = {}) {
  const t = {};
  const smoke = profile === 'smoke';
  addThreshold(t, 'http_req_failed', `rate<${ABORT_FAILURE_RATE}`, 'abort', { abortOnFail: true, delayAbortEval: '60s' });
  for (const phase of acceptPhases(profile)) {
    addThreshold(t, `http_req_failed{phase:${phase}}`, smoke ? 'rate==0' : `rate<${MAX_FAILURE_RATE}`, 'acceptance');
    addThreshold(t, `checks{phase:${phase}}`, smoke ? 'rate==1' : `rate>=${MIN_CHECK_RATE}`, 'acceptance');
    for (const [endpoint, p95] of Object.entries(endpoints)) {
      const tags = `{endpoint:${endpoint},phase:${phase}}`;
      if (smoke) addThreshold(t, `http_req_duration${tags}`, 'p(95)>=0', 'info');
      else {
        addThreshold(t, `http_req_duration${tags}`, `p(95)<=${p95}`, 'acceptance');
        if (minSamples) addThreshold(t, `http_reqs${tags}`, `count>=${minSamples}`, 'samples');
      }
    }
    for (const endpoint of info) addThreshold(t, `http_req_duration{endpoint:${endpoint},phase:${phase}}`, 'p(95)>=0', 'info');
  }
  // Stress: per-step latency for the breaking-point table (reported only).
  if (profile === 'stress') {
    for (const n of STRESS_STEPS) {
      addThreshold(t, `http_req_failed{phase:step-${n}}`, 'rate>=0', 'info');
      for (const endpoint of Object.keys(endpoints)) addThreshold(t, `http_req_duration{endpoint:${endpoint},phase:step-${n}}`, 'p(95)>=0', 'info');
    }
  }
  for (const [metric, expression] of Object.entries(data)) addThreshold(t, metric, expression, 'data', { abortOnFail: true });
  for (const [metric, expression, kind = 'acceptance'] of custom) addThreshold(t, metric, expression, kind);
  return t;
}

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];
