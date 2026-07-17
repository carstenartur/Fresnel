import { expect, test } from '@playwright/test';

test('stable plugin route renders and edits the Hex schema form', async ({ page }) => {
  const schemaResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith('/api/plugins/hex-macro-cell/schema'));

  await page.goto('/plugins/hex-macro-cell');
  const schemaResponse = await schemaResponsePromise;
  expect(schemaResponse.ok()).toBeTruthy();
  await expect(page).toHaveURL(/\/plugins\/hex-macro-cell$/);
  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');

  const form = page.locator('[data-plugin-schema="hex-macro-cell"]');
  await expect(form).toBeVisible();
  await expect(form.locator('[data-schema-group="geometry"]')).toBeVisible();
  await expect(form.locator('[data-schema-group="target"]')).toBeVisible();
  await expect(form.locator('[data-schema-group="production"]')).toBeVisible();

  const macroRadius = page.getByLabel('Macro radius (mm)');
  await macroRadius.fill('0');
  await expect(macroRadius).toHaveAttribute('aria-invalid', 'true');
  await expect(form.getByText(/Enter a finite number within the allowed range/)).toBeVisible();

  await macroRadius.fill('20');
  await page.getByLabel('Sub-element diameter (mm)').fill('5');
  await page.getByLabel('Sub-element pitch (mm)').fill('6');
  await page.getByLabel('Focal length (mm)').fill('500');
  await page.getByLabel('Wavelength (nm)').fill('550');
  await page.getByLabel('Mask type').selectOption('GREYSCALE_PHASE');
  await page.getByLabel('Polarity').selectOption('NEGATIVE');

  await page.getByRole('button', { name: 'Render preview' }).click();
  await expect(page.getByText(/sub-elements ·.*px per side/)).toBeVisible({ timeout: 30_000 });
});

test('nested RGB schema paths update the request used for rendering', async ({ page }) => {
  await page.goto('/plugins/rgb-zone-plate');
  const form = page.locator('[data-plugin-schema="rgb-zone-plate"]');
  await expect(form).toBeVisible();
  await expect(page.getByRole('tab', { name: 'RGB' }))
    .toHaveAttribute('aria-selected', 'true');

  await page.getByLabel('Aperture diameter (mm)').fill('7');
  await page.getByLabel('Focal length (mm)').fill('250');
  await page.getByLabel('Printer DPI').fill('900');
  await page.getByLabel('Red wavelength (nm)').fill('650');
  await page.getByLabel('Green wavelength (nm)').fill('540');
  await page.getByLabel('Blue wavelength (nm)').fill('460');

  await page.getByRole('button', { name: 'Render preview' }).click();
  await expect(page.getByRole('img', { name: 'RGB zone plate preview' }))
    .toBeVisible({ timeout: 30_000 });
});

test('tab navigation and browser history use stable plugin-id routes', async ({ page }) => {
  await page.goto('/plugins/hex-macro-cell');
  await expect(page.locator('[data-plugin-schema="hex-macro-cell"]')).toBeVisible();

  await page.getByRole('tab', { name: 'Single ZP' }).click();
  await expect(page).toHaveURL(/\/plugins\/zone-plate$/);
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');

  await page.goBack();
  await expect(page).toHaveURL(/\/plugins\/hex-macro-cell$/);
  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('[data-plugin-schema="hex-macro-cell"]')).toBeVisible();
});
