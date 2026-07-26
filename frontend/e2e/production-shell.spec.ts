import { expect, test } from '@playwright/test';

test('loads the packaged production application without browser runtime errors', async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });

  await page.goto('/', { waitUntil: 'networkidle' });

  await expect(page.getByRole('heading', { name: 'Fresnel Designer' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Single ZP' })).toBeVisible();

  const moduleSource = await page.locator('script[type="module"]').getAttribute('src');
  expect(moduleSource).toMatch(/^\/assets\/index-[^/]+\.js$/);
  expect(moduleSource).not.toContain('/@vite/client');
  expect(pageErrors, 'uncaught browser errors').toEqual([]);
  expect(consoleErrors, 'browser console errors').toEqual([]);
});
