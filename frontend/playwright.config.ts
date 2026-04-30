import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Fresnel end-to-end tests.
 *
 * Conventions:
 *  - Tests live in `frontend/e2e/`.
 *  - The Vite dev server is launched automatically (`webServer`) on port 5173.
 *    The Vite proxy forwards `/api` to a backend on :8080, which CI starts as a
 *    separate workflow step (see `.github/workflows/e2e.yml`). For local runs,
 *    start the backend manually with `mvn -pl backend spring-boot:run` first.
 *  - In CI we run only chromium to keep wall-time reasonable; you can opt in to
 *    other browsers by overriding the `projects` field locally.
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
