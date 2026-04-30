import { expect, test } from '@playwright/test';

/**
 * Hex macro-cell tab: switching modes, configuring the sub-element grid, and
 * verifying that the design info ("N sub-elements · M px per side") appears
 * after a render. We deliberately push the macro radius / sub-pitch combo so
 * the outer-zone-pixel warning is plausible.
 */
test('hex macro cell renders and shows sub-element info', async ({ page }) => {
  await page.goto('/');

  // Switch tab.
  await page.getByRole('tab', { name: 'Hex macro' }).click();
  await expect(page.getByRole('tab', { name: 'Hex macro' })).toHaveAttribute('aria-selected', 'true');

  // Configure the sub-element grid.
  await page.getByLabel('Macro radius (mm)').fill('20');
  await page.getByLabel('Sub-element diameter (mm)').fill('5');
  await page.getByLabel('Sub-element pitch (mm)').fill('6');
  await page.getByLabel('Focal length (mm)').fill('500');
  await page.getByLabel('Wavelength (nm)').fill('550');

  await page.getByRole('button', { name: /Render preview/ }).click();

  // Sub-element / px count appears.
  await expect(page.getByText(/sub-elements ·.*px per side/)).toBeVisible({ timeout: 30_000 });
});
