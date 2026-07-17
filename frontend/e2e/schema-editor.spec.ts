import { expect, test } from '@playwright/test';

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

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
  await expect(page.getByRole('button', { name: 'PDF' })).toHaveCount(0);
});

test('trusted focus-point widget edits a schema-owned array', async ({ page }) => {
  await page.goto('/plugins/multi-focus');
  const form = page.locator('[data-plugin-schema="multi-focus"]');
  await expect(form).toBeVisible();

  await expect(page.getByLabel('x1 (mm)')).toHaveValue('-5');
  await expect(page.getByLabel('x2 (mm)')).toHaveValue('5');
  await page.getByRole('button', { name: '+ Add focus point' }).click();
  await expect(page.getByLabel('x3 (mm)')).toHaveValue('0');
  await page.getByLabel('x3 (mm)').fill('12');
  await page.getByLabel('z3 (mm)').fill('800');
  await page.getByRole('button', { name: 'Remove focus 2' }).click();
  await expect(page.getByLabel('x2 (mm)')).toHaveValue('12');

  await page.getByRole('button', { name: 'Render preview' }).click();
  await expect(page.getByRole('img', { name: 'Multi-focus preview' }))
    .toBeVisible({ timeout: 30_000 });
});

test('trusted Window Foil widget round-trips optional per-cell overrides', async ({ page }) => {
  await page.goto('/plugins/window-foil');
  const form = page.locator('[data-plugin-schema="window-foil"]');
  await expect(form).toBeVisible();
  await expect(page.getByLabel('Draw crop marks')).toBeChecked();

  await page.getByText('Per-cell layout', { exact: true }).click();
  await page.getByRole('button', { name: '+ Add cell specification' }).click();
  await page.getByLabel('Cell 1 focal length (mm)').fill('750');
  await page.getByLabel('Cell 1 target X (mm)').fill('2');
  await page.getByLabel('Cell 1 target Y (mm)').fill('-3');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Save job (.fresnel)' }).click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);

  expect(job.plugin.id).toBe('window-foil');
  expect(job.parameters.drawCropMarks).toBe(true);
  expect(job.parameters.cellSpecs).toEqual([{
    focalLengthMm: 750,
    targetOffsetXmm: 2,
    targetOffsetYmm: -3,
  }]);
});

test('trusted Hologram image widget embeds only local file data', async ({ page }) => {
  await page.goto('/plugins/hologram');
  const form = page.locator('[data-plugin-schema="hologram"]');
  await expect(form).toBeVisible();

  await page.getByLabel('Target image').setInputFiles({
    name: 'target.png',
    mimeType: 'image/png',
    buffer: ONE_PIXEL_PNG,
  });
  await expect(page.getByRole('status')).toContainText('Loaded target.png');
  await page.getByLabel('Side (px)').fill('64');
  await page.getByLabel('Gerchberg–Saxton iterations').fill('5');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Save job (.fresnel)' }).click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);

  expect(job.plugin.id).toBe('hologram');
  expect(job.parameters.targetImageBase64).toBe(ONE_PIXEL_PNG.toString('base64'));
  expect(job.parameters.sidePx).toBe(64);
  expect(job.parameters.iterations).toBe(5);

  await page.getByRole('button', { name: 'Clear image' }).click();
  await expect(page.getByRole('button', { name: 'Save job (.fresnel)' })).toBeDisabled();
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
