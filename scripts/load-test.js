import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

/**
 * K6 load profile for the Transact SLI endpoints (see IMPLEMENTATION_PLAN
 * Phase 6/7: error rate < 0.1%, p99 < 200 ms for transaction queries).
 *
 * Scenarios (select with SCENARIO=smoke|load|soak, default: all):
 *   - smoke: 1 VU, 5 iterations — pipeline sanity before any load run
 *   - load:  ramping-vus 0 -> LOAD_TEST_VUS (default 20) over LOAD_TEST_RAMP,
 *            hold LOAD_TEST_HOLD (default 2m), ramp down 30s — the
 *            "find the bottleneck" profile
 *   - soak:  constant-vus SOAK_VUS (default 8) for SOAK_DURATION (default 5m)
 *            — watches for degradation over time (connection leaks, GC)
 *
 * Workload (per iteration, 1s think time):
 *   - 60% GET /transactions?limit=2 — 30% of those add &category=GROCERIES
 *     (filtered keyset path); every result with hasMore follows the
 *     meta.nextCursor one hop (cursor decode + page-2 keyset query)
 *   - 40% GET /summary — per-currency SQL aggregates
 *
 * Auth: mints the same HS* JWT as scripts/mint-jwt.mjs (alg chosen from
 * secret length) unless LOAD_TEST_TOKEN is provided. The default secret
 * matches the committed test-only value; point LOAD_TEST_JWT_SECRET at the
 * server's APP_SECURITY_JWT_SECRET for any other environment.
 *
 * Usage (against the compose stack):
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d \
 *     postgres zookeeper kafka transaction-api
 *   k6 run scripts/load-test.js -e SCENARIO=smoke \
 *     -e LOAD_TEST_BASE_URL=http://127.0.0.1:18081
 *   k6 run scripts/load-test.js -e SCENARIO=load  -e LOAD_TEST_BASE_URL=...
 *   k6 run scripts/load-test.js -e SCENARIO=soak  -e LOAD_TEST_BASE_URL=...
 *
 * SCENARIO=all runs the three sequentially via startTime, assuming DEFAULT
 * durations; use per-scenario runs for isolated, comparable numbers.
 * Deliberately NOT wired into per-PR CI: shared-runner timings are noise.
 * Run ad hoc or scheduled against a production-like environment before
 * making capacity claims — local compose numbers are a baseline, not proof.
 */

const BASE_URL = __ENV.LOAD_TEST_BASE_URL || 'http://localhost:8080';
const CUSTOMER_ID =
  __ENV.LOAD_TEST_CUSTOMER_ID || '00000000-0000-0000-0000-000000000001';
const DEFAULT_SECRET =
  'test-only-not-a-real-secret-0123456789abcdef0123456789';

const SCENARIO = (__ENV.SCENARIO || 'all').toLowerCase();
const LOAD_VUS = Number(__ENV.LOAD_TEST_VUS || 20);
const LOAD_RAMP = __ENV.LOAD_TEST_RAMP || '2m';
const LOAD_HOLD = __ENV.LOAD_TEST_HOLD || '2m';
const SOAK_VUS = Number(__ENV.SOAK_VUS || 8);
const SOAK_DURATION = __ENV.SOAK_DURATION || '5m';

// SLI-specific duration trends, so thresholds target the endpoints the SLOs
// name instead of a single blended http_req_duration.
const txnDuration = new Trend('transact_txn_duration', true);
const summaryDuration = new Trend('transact_summary_duration', true);

function buildScenarios(which) {
  const want = (name) => which === 'all' || which === name;
  const scenarios = {};
  if (want('smoke')) {
    scenarios.smoke = {
      executor: 'shared-iterations',
      exec: 'smoke',
      vus: 1,
      iterations: 5,
      maxDuration: '1m',
    };
  }
  if (want('load')) {
    scenarios.load = {
      executor: 'ramping-vus',
      exec: 'load',
      startVUs: 0,
      stages: [
        { duration: LOAD_RAMP, target: LOAD_VUS },
        { duration: LOAD_HOLD, target: LOAD_VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
      // In 'all' mode the load profile runs after smoke (max ~10s).
      startTime: which === 'all' ? '15s' : '0s',
    };
  }
  if (want('soak')) {
    scenarios.soak = {
      executor: 'constant-vus',
      exec: 'soak',
      vus: SOAK_VUS,
      duration: SOAK_DURATION,
      // In 'all' mode the soak starts after the default load profile
      // (15s + 2m ramp + 2m hold + 30s ramp-down + margin).
      startTime: which === 'all' ? '330s' : '0s',
    };
  }
  return scenarios;
}

// SLI targets from IMPLEMENTATION_PLAN Phase 6, enforced as exit-code gates.
export const options = {
  scenarios: buildScenarios(SCENARIO),
  thresholds: {
    // Availability: error rate < 0.1% on the SLI endpoints.
    http_req_failed: ['rate<0.001'],
    // Latency: p99 < 200 ms for transaction queries (and summaries).
    transact_txn_duration: ['p(99)<200'],
    transact_summary_duration: ['p(99)<200'],
    // Structural checks must keep passing.
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const token =
    __ENV.LOAD_TEST_TOKEN ||
    mintJwt(__ENV.LOAD_TEST_JWT_SECRET || DEFAULT_SECRET, CUSTOMER_ID);
  // Fail fast in setup if the token is rejected — clearer than a wall of 401s.
  const res = http.get(
    `${BASE_URL}/v1/customers/${CUSTOMER_ID}/transactions?limit=1`,
    authParams(token),
  );
  if (res.status !== 200) {
    throw new Error(
      `authenticated setup request returned ${res.status} — check ` +
        `LOAD_TEST_BASE_URL (${BASE_URL}) and the JWT secret`,
    );
  }
  return { token };
}

export function smoke(data) {
  iterate(data);
}
export function load(data) {
  iterate(data);
}
export function soak(data) {
  iterate(data);
}

function iterate(data) {
  group('sli-workload', () => {
    if (Math.random() < 0.6) {
      txnFlow(data.token);
    } else {
      summaryFlow(data.token);
    }
  });
  sleep(1);
}

function txnFlow(token) {
  const filtered = Math.random() < 0.3;
  let url = `${BASE_URL}/v1/customers/${CUSTOMER_ID}/transactions?limit=2`;
  if (filtered) {
    url += '&category=GROCERIES';
  }

  const res = http.get(url, authParams(token));
  txnDuration.add(res.timings.duration);
  const ok = check(res, {
    'txn 200': (r) => r.status === 200,
    'txn data is array': (r) => Array.isArray(r.json('data')),
    'txn freshness present': (r) => r.json('meta.freshness.status') !== undefined,
  });
  if (!ok) {
    return;
  }

  // Follow one keyset hop so the cursor path is exercised under load too.
  const meta = res.json('meta');
  if (meta && meta.hasMore && meta.nextCursor) {
    let page2 = `${BASE_URL}/v1/customers/${CUSTOMER_ID}/transactions?limit=2` +
      `&cursor=${encodeURIComponent(meta.nextCursor)}`;
    if (filtered) {
      page2 += '&category=GROCERIES';
    }
    const res2 = http.get(page2, authParams(token));
    txnDuration.add(res2.timings.duration);
    check(res2, {
      'txn page2 200': (r) => r.status === 200,
      'txn page2 data is array': (r) => Array.isArray(r.json('data')),
    });
  }
}

function summaryFlow(token) {
  const res = http.get(
    `${BASE_URL}/v1/customers/${CUSTOMER_ID}/summary`,
    authParams(token),
  );
  summaryDuration.add(res.timings.duration);
  check(res, {
    'summary 200': (r) => r.status === 200,
    'summary summaries is array': (r) => Array.isArray(r.json('summaries')),
    'summary meta present': (r) => r.json('meta') !== undefined,
  });
}

function authParams(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
    },
  };
}

// --- JWT minting (mirrors scripts/mint-jwt.mjs — same claims, same alg rule) ---

function b64url(value) {
  return encoding
    .b64encode(value)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function mintJwt(secret, customerId) {
  const bits = secret.length * 8;
  let alg;
  let hashAlg;
  if (bits >= 512) {
    alg = 'HS512';
    hashAlg = 'sha512';
  } else if (bits >= 384) {
    alg = 'HS384';
    hashAlg = 'sha384';
  } else if (bits >= 256) {
    alg = 'HS256';
    hashAlg = 'sha256';
  } else {
    throw new Error('LOAD_TEST_JWT_SECRET must be at least 32 bytes (256 bits)');
  }

  const now = Math.floor(Date.now() / 1000);
  const header = { alg, typ: 'JWT' };
  const payload = {
    sub: customerId,
    tenantId: 'tenant-1',
    roles: ['CUSTOMER'],
    iss: 'transact',
    aud: ['transact-api'],
    iat: now,
    exp: now + 3600,
  };

  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
  // k6 createHMAC accepts the secret as a plain string (UTF-8 bytes).
  const signer = crypto.createHMAC(hashAlg, secret);
  signer.update(signingInput);
  const signature = signer
    .digest('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

  return `${signingInput}.${signature}`;
}
