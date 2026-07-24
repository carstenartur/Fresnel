import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Fresnel end-to-end tests.
 *
 * Conventions:
 *  - Tests live in `frontend/e2e/`.
 *  - The Vite dev server is launched automatically (`webServer`) on port 5173.
 *    The Vite proxy forwards `/api` to a backend on :8080.
 *  - `mvn -Dfresnel.e2e.skip=false verify` owns the reproducible E2E lifecycle:
 *    it installs Chromium, starts/stops the backend and invokes this suite through
 *    Maven Failsafe. A direct `npm run e2e` remains useful while a backend is
 *    already running locally.
 *  - CI and local Maven runs use only chromium to keep wall-time reasonable.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,        // backend keeps in-memory job state
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['html', { open: 'never' }], ['github']]
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
