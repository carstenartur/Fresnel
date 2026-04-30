import { expect, test } from '@playwright/test';

/**
 * Single zone-plate happy-path: tweak diameter / focal / wavelength, wait for
 * the auto-rendered preview to appear, then trigger PNG export and confirm a
 * non-empty download is delivered.
 */
test('single zone plate preview renders and PNG export downloads', async ({ page }) => {
  await page.goto('/');

  // The "Single ZP" tab is the default mode.
  await expect(page.getByRole('tab', { name: 'Single ZP' })).toHaveAttribute('aria-selected', 'true');

  // Tweak a couple of fields.
  await page.getByLabel('Aperture diameter (mm)').fill('8');
  await page.getByLabel('Focal length (mm)').fill('500');
  await page.getByLabel('Wavelength (nm)').fill('632');

  // Render preview.
  await page.getByRole('button', { name: /Render preview/ }).click();

  // The preview <img> appears inside the panel once the blob URL is set.
  const preview = page.getByRole('img', { name: 'Fresnel zone plate preview' });
  await expect(preview).toBeVisible({ timeout: 30_000 });
  await expect(preview).toHaveJSProperty('complete', true);

  // PNG export → assert download non-empty.
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /^PNG$/ }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/\.png$/);
  const stream = await download.createReadStream();
  let bytes = 0;
  for await (const chunk of stream) bytes += chunk.length;
  expect(bytes).toBeGreaterThan(64); // non-empty PNG (small for a low-DPI preview)
});
