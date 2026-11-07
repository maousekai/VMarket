// Shared configuration for every k6 script in perf/.
// All values come from environment variables (k6 run -e NAME=value ...).

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

// Load shape: smoke | load | stress | soak (see README).
export const PROFILE = (__ENV.PROFILE || 'smoke').toLowerCase();
export const VUS = intEnv('VUS', 200);
export const RAMP = __ENV.RAMP || '3m';
export const HOLD = __ENV.HOLD || '5m';
export const SOAK_DURATION = __ENV.SOAK_DURATION || '1h';

// Test buyers created by setup/create-buyers.js. Each VU uses its own account:
// VU n -> buyer ((n - 1) % BUYER_COUNT).
export const BUYER_COUNT = intEnv('BUYER_COUNT', 200);
export const BUYER_PREFIX = __ENV.BUYER_PREFIX || 'perf.buyer';
export const BUYER_DOMAIN = 'perf.vmarket.test'; // .test is reserved, never receives mail

// Think time between user actions, in seconds (uniform random in [min, max]).
export const THINK_MIN = floatEnv('THINK_MIN', 1);
export const THINK_MAX = floatEnv('THINK_MAX', 3);

// NFR-PERF thresholds (SRS 6.1).
export const READ_P95_MS = 500; // NFR-PERF-01
export const SEARCH_P95_MS = 1000; // NFR-PERF-02
export const SUGGEST_P95_MS = 300; // NFR-PERF-02
export const MAX_ERROR_RATE = 0.01;

export function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') return fallback;
  const value = parseInt(raw, 10);
  if (Number.isNaN(value) || value < 0) throw new Error(`${name} must be a non-negative integer, got "${raw}"`);
  return value;
}

function floatEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') return fallback;
  const value = parseFloat(raw);
  if (Number.isNaN(value) || value < 0) throw new Error(`${name} must be a non-negative number, got "${raw}"`);
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

// Scripts that create data (accounts, carts, orders) refuse a non-local gateway
// unless the operator confirms the target is a disposable test environment.
export function guardWritableTarget(scriptName) {
  if (isLocalTarget()) return;
  if (__ENV.ALLOW_REMOTE === 'true') return;
  throw new Error(
    `${scriptName} creates data and BASE_URL=${BASE_URL} is not local. ` +
      'Only run it against a disposable test environment, then pass -e ALLOW_REMOTE=true.',
  );
}

// One k6 scenario object for the selected PROFILE.
// `exec` names the exported function that each VU runs.
export function scenarioFor(exec, { vus = VUS, startTime } = {}) {
  const base = { exec, ...(startTime ? { startTime } : {}) };
  switch (PROFILE) {
    case 'smoke':
      return { ...base, executor: 'constant-vus', vus: Math.min(Math.max(vus, 1), 5), duration: __ENV.DURATION || '1m' };
    case 'load':
      return {
        ...base,
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
          { duration: RAMP, target: vus },
          { duration: HOLD, target: vus },
          { duration: '1m', target: 0 },
        ],
        gracefulRampDown: '30s',
      };
    case 'stress': {
      // Step past the target until latency or errors break; thresholds report where.
      const steps = [0.5, 1, 1.5, 2, 2.5].map((f) => Math.max(1, Math.round(vus * f)));
      return {
        ...base,
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [...steps.flatMap((target) => [{ duration: '2m', target }, { duration: '3m', target }]), { duration: '1m', target: 0 }],
        gracefulRampDown: '30s',
      };
    }
    case 'soak':
      return { ...base, executor: 'constant-vus', vus: Math.max(1, Math.round(vus / 2)), duration: SOAK_DURATION };
    default:
      throw new Error(`Unknown PROFILE "${PROFILE}". Use smoke, load, stress or soak.`);
  }
}

// Thresholds that make k6 exit non-zero when an NFR is missed.
// `endpoints` maps an endpoint tag to its p95 budget in ms.
export function thresholdsFor(endpoints, extra = {}) {
  const result = {
    http_req_failed: [`rate<${MAX_ERROR_RATE}`],
    checks: [`rate>${1 - MAX_ERROR_RATE}`],
  };
  for (const [endpoint, p95] of Object.entries(endpoints)) {
    result[`http_req_duration{endpoint:${endpoint}}`] = [`p(95)<${p95}`];
  }
  return { ...result, ...extra };
}

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];
