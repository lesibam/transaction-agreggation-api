import { execFileSync } from 'node:child_process';
import http from 'node:http';

/**
 * Playwright global setup:
 * 1. Mints a real HS* JWT for the V2 demo customer using scripts/mint-jwt.mjs
 *    (secret from APP_SECURITY_JWT_SECRET, defaulting to the surefire/dev value).
 * 2. Probes the API (E2E_BASE_URL, default http://localhost:8080) and exports
 *    E2E_APP_UP so specs can skip cleanly when the stack is not running.
 *
 * Uses node:http (NOT global fetch) — undici/fetch proved unreliable against
 * loopback in some environments and would mark a healthy app as down.
 *
 * E2E requires the full stack first:
 *   lsof -nP -iTCP:18081 -sTCP:LISTEN   # pre-flight: port must be free
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build \
 *     postgres zookeeper kafka transaction-api
 *   E2E_BASE_URL=http://127.0.0.1:18081 npx playwright test
 *
 * WARNING: when the probe fails, specs are SKIPPED and the suite exits 0.
 * Treat a "5 skipped" result as "NOT RUN", not "passed". In CI, assert
 * explicitly that zero tests were skipped.
 */
export default async function globalSetup(): Promise<void> {
  const token = execFileSync(process.execPath, ['scripts/mint-jwt.mjs'], {
    cwd: process.cwd(),
    env: process.env,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();

  if (!token || token.split('.').length !== 3) {
    throw new Error(`mint-jwt.mjs produced an invalid token: "${token}"`);
  }
  process.env.E2E_TOKEN = token;

  process.env.E2E_CUSTOMER_ID =
    process.env.E2E_CUSTOMER_ID || '00000000-0000-0000-0000-000000000001';

  const baseUrl = process.env.E2E_BASE_URL || 'http://localhost:8080';
  const up = await probe(`${baseUrl}/actuator/health`);
  process.env.E2E_APP_UP = up ? 'true' : 'false';
  if (!up) {
    // Loud on purpose: a silent skip is how a full E2E non-run hides as green.
    console.warn(
      `[e2e] API NOT reachable at ${baseUrl} — all specs will be SKIPPED. ` +
      'Verify the port is actually served by THIS app (lsof -nP -iTCP:<port> -sTCP:LISTEN), ' +
      'then treat this run as NOT RUN, not passed.',
    );
  }
};

function probe(url: string, timeoutMs = 5000): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => {
      res.resume();
      resolve(!!res.statusCode && res.statusCode >= 200 && res.statusCode < 300);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(timeoutMs, () => {
      req.destroy();
      resolve(false);
    });
  });
}
