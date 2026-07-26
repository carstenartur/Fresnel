import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Fresnel end-to-end tests.
 *
 * Conventions:
 *  - Tests live in `frontend/e2e/`.
 *  - A direct local `npm run e2e` starts Vite on port 5173 and proxies `/api`
 *    to an already-running backend on :8080.
 *  - `mvn -Dfresnel.e2e.skip=false verify` sets E2E_NO_WEBSERVER and targets
 *    the Maven-started Spring Boot application on :8080. This verifies the
 *    packaged production frontend copied into the backend, not Vite dev mode.
 *  - CI failures are never hidden by retries. The HTML report, trace, media and
 *    per-test JUnit XML remain available as diagnostics.
 */
export default defineConfig({
  testDir: './e2e',
  outputDir: 'test-results/artifacts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI
    ? [
        ['html', { open: 'never' }],
        ['github'],
        ['junit', { outputFile: 'test-results/playwright-junit.xml' }],
      ]
    : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // Default to the seeded test user; specs that need an admin override per-test.
    httpCredentials: {
      username: process.env.E2E_USER ?? 'user',
      password: process.env.E2E_PASSWORD ?? 'user',
    },
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: process.env.E2E_NO_WEBSERVER
    ? undefined
    : {
        command: 'npm run dev -- --strictPort',
        port: 5173,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
