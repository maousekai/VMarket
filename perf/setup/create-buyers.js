// Creates the test buyers the load scripts log in as, through the real API:
// register -> login -> wait for the user-service profile -> add a delivery address.
// Safe to re-run: existing accounts (409) and existing addresses are kept.
//
//   k6 run -e PERF_PASSWORD=... [-e BUYER_COUNT=200] perf/setup/create-buyers.js
//
// Buyers stay PENDING (email not verified). Login, profile, cart and orders accept
// PENDING accounts, so no OTP round-trip is needed.
import exec from 'k6/execution';
import { get, post } from '../lib/api.js';
import { BUYER_COUNT, guardWritableTarget, requireEnv } from '../lib/config.js';
import { buyerEmail, buyerUsername, login } from '../lib/session.js';
import { testAddress } from '../flows/checkout.js';

const PASSWORD = requireEnv('PERF_PASSWORD');
if (!/^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/.test(PASSWORD)) {
  throw new Error('PERF_PASSWORD must be 8-32 chars with an uppercase letter, a digit and a special character');
}
guardWritableTarget('create-buyers.js');

export const options = {
  scenarios: {
    buyers: {
      executor: 'shared-iterations',
      vus: Math.min(10, Math.max(1, BUYER_COUNT)),
      iterations: BUYER_COUNT,
      maxDuration: '30m',
    },
  },
  thresholds: { checks: ['rate>0.99'] },
};

export default function () {
  const index = exec.scenario.iterationInTest;
  const email = buyerEmail(index);

  post('/api/auth/register', {
    endpoint: 'setup:register',
    expect: [201, 409], // 409 = already created by an earlier run
    body: { email, username: buyerUsername(index), password: PASSWORD },
  });

  const { token } = login(email, PASSWORD);

  // The first GET creates the empty profile; doing it here keeps that write out
  // of the measured GET /api/users/me in the load runs.
  get('/api/users/me', { endpoint: 'setup:profile', token });

  const { data: addresses } = get('/api/users/me/addresses', { endpoint: 'setup:addresses', token });
  if (!addresses || addresses.length === 0) {
    post('/api/users/me/addresses', { endpoint: 'setup:add-address', token, expect: [201], body: testAddress() });
  }
}
