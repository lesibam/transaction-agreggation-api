import { defineConfig, devices } from '@playwright/test';

/**
 * E2E suite runs against a live stack (docker compose + app).
 * Default baseURL is :8080; override with E2E_BASE_URL when host ports clash
 * (e.g. docker-compose.e2e.yml maps the API to :18081).
 * It is intentionally NOT wired into the Maven build — run with `npx playwright test`.
 * globalSetup mints a real JWT (scripts/mint-jwt.mjs) and probes app availability.
 * A "skipped" result means NOT RUN — never treat it as green.
 */
const baseURL = process.env.E2E_BASE_URL || 'http://localhost:8080';

export default defineConfig({
  testDir: './tests/e2e',
  globalSetup: './tests/e2e/global-setup.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL,
    extraHTTPHeaders: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
