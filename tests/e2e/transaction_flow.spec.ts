import { test, expect, type APIRequestContext } from '@playwright/test';

/**
 * Transact API E2E Test Suite (requires a live stack; default :8080, override with E2E_BASE_URL).
 *
 * Setup before running `npx playwright test`:
 *   1. cp .env.example .env  (set POSTGRES_PASSWORD, SPRING_DATASOURCE_PASSWORD,
 *      APP_SECURITY_JWT_SECRET — must be >= 32 bytes)
 *   2. docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build \
 *        postgres zookeeper kafka transaction-api
 *      (omit docker-compose.e2e.yml if :8080/:9092 are free)
 *   3. Wait for /actuator/health, then:
 *        E2E_BASE_URL=http://127.0.0.1:18081 npx playwright test
 *
 * If the app is not running, every spec is skipped (not failed), so this suite
 * never breaks the Java/Maven build.
 *
 * Auth: global-setup mints a real JWT for the V2 demo customer
 * (00000000-0000-0000-0000-000000000001) via scripts/mint-jwt.mjs with the same
 * APP_SECURITY_JWT_SECRET the app uses.
 */

const DEMO_CUSTOMER_ID =
  process.env.E2E_CUSTOMER_ID || '00000000-0000-0000-0000-000000000001';
const OTHER_CUSTOMER_ID = '00000000-0000-0000-0000-0000000000ff';

function authHeaders(): Record<string, string> {
  const token = process.env.E2E_TOKEN;
  if (!token) {
    throw new Error('E2E_TOKEN missing — Playwright globalSetup did not run');
  }
  return { Authorization: `Bearer ${token}` };
}

test.describe('Transact API Production-Grade Suite', () => {
  test.beforeEach(() => {
    test.skip(
      process.env.E2E_APP_UP !== 'true',
      `API not running (${process.env.E2E_BASE_URL || 'http://localhost:8080'}) — start the stack first`,
    );
  });

  test('GET /transactions - normalized transactions with completeness and freshness metadata', async ({
    request,
  }: { request: APIRequestContext }) => {
    const response = await request.get(
      `/v1/customers/${DEMO_CUSTOMER_ID}/transactions`,
      { params: { limit: '10' }, headers: authHeaders() },
    );

    expect(response.status()).toBe(200);
    const body = await response.json();

    expect(body).toHaveProperty('data');
    expect(body).toHaveProperty('meta');
    expect(Array.isArray(body.data)).toBe(true);

    if (body.data.length > 0) {
      const tx = body.data[0];
      expect(tx).toHaveProperty('id');
      expect(tx.amount).toHaveProperty('value');
      expect(tx.amount).toHaveProperty('currency');
      expect(['DEBIT', 'CREDIT']).toContain(tx.direction);
      expect(tx.source).toHaveProperty('provider');
      expect(tx.source).toHaveProperty('transactionId');
      expect(tx.category).toHaveProperty('code');
    }

    expect(body.meta.completeness).toMatch(/^(COMPLETE|PARTIAL)$/);
    expect(body.meta.freshness.status).toMatch(
      /^(FRESH|STALE|VERY_STALE|UNKNOWN)$/,
    );
    expect(Array.isArray(body.meta.freshness.sources)).toBe(true);
    expect(body.meta.freshness.sources.length).toBe(3);
    expect(body.meta).toHaveProperty('hasMore');
    expect(body.meta).toHaveProperty('nextCursor');
  });

  test('GET /summary - monetary aggregates structured per currency', async ({
    request,
  }: { request: APIRequestContext }) => {
    const response = await request.get(
      `/v1/customers/${DEMO_CUSTOMER_ID}/summary`,
      { headers: authHeaders() },
    );
    expect(response.status()).toBe(200);

    const body = await response.json();

    expect(Array.isArray(body.summaries)).toBe(true);
    if (body.summaries.length > 0) {
      const summary = body.summaries[0];
      expect(summary).toHaveProperty('currency');
      expect(summary).toHaveProperty('totalDebit');
      expect(summary).toHaveProperty('totalCredit');
      expect(summary).toHaveProperty('netFlow');
      const currencies = body.summaries.map(
        (s: { currency: string }) => s.currency,
      );
      expect(new Set(currencies).size).toBe(currencies.length);
    }

    expect(Array.isArray(body.categoryBreakdown)).toBe(true);
    expect(body.meta.completeness).toMatch(/^(COMPLETE|PARTIAL)$/);
    expect(body.meta.freshness).toBeDefined();
  });

  test('Security: tenant isolation - other customer id is forbidden', async ({
    request,
  }: { request: APIRequestContext }) => {
    const response = await request.get(
      `/v1/customers/${OTHER_CUSTOMER_ID}/transactions`,
      { headers: authHeaders() },
    );

    expect(response.status()).toBe(403);
    expect(response.headers()['content-type']).toContain(
      'application/problem+json',
    );
    const body = await response.json();
    expect(body.title).toBe('Forbidden');
    expect(body.status).toBe(403);
    expect(body.detail).toContain('Access denied');
  });

  test('Security: 401 without a token', async ({ request }: { request: APIRequestContext }) => {
    const response = await request.get(
      `/v1/customers/${DEMO_CUSTOMER_ID}/transactions`,
      { headers: { Authorization: '' } },
    );

    expect(response.status()).toBe(401);
    expect(response.headers()['content-type']).toContain(
      'application/problem+json',
    );
    const body = await response.json();
    expect(body.title).toBe('Unauthenticated');
  });

  test('Security: admin endpoint rejects customer role', async ({
    request,
  }: { request: APIRequestContext }) => {
    const response = await request.get('/v1/admin/sources', {
      headers: authHeaders(),
    });

    expect(response.status()).toBe(403);
    const body = await response.json();
    expect(body.title).toBe('Access Denied');
  });
});
