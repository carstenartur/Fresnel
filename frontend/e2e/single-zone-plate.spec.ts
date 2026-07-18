import { expect, test } from '@playwright/test';

/**
 * Single zone-plate happy-path: tweak diameter / focal / wavelength, wait for
 * the rendered preview to appear, then trigger PNG export and confirm a
 * non-empty download is delivered.
 */
test('single zone plate preview renders and PNG export downloads', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');

  const form = page.locator('[data-plugin-schema="zone-plate"]');
  await form.getByRole('spinbutton', { name: /^Aperture diameter \(mm\)/ }).fill('8');
  await form.getByRole('spinbutton', { name: /^Focal length \(mm\)/ }).fill('500');
  await form.getByRole('spinbutton', { name: /^Wavelength \(nm\)/ }).fill('632');
  // Keep the fabrication fixture above the two-pixels-per-outer-zone hard limit.
  await form.getByRole('spinbutton', { name: /^Printer DPI/ }).fill('2400');

  const render = page.getByRole('button', { name: /Render preview/ });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();

  const preview = page.getByRole('img', { name: 'Fresnel zone plate preview' });
  await expect(preview).toBeVisible({ timeout: 30_000 });
  await expect(preview).toHaveJSProperty('complete', true);

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /^PNG$/ }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/\.png$/);
  const stream = await download.createReadStream();
  let bytes = 0;
  for await (const chunk of stream) bytes += chunk.length;
  expect(bytes).toBeGreaterThan(64);
});
