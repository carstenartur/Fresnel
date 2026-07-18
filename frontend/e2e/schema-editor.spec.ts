import { expect, test } from '@playwright/test';

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

test('Zone Plate standard fields and actions come from the plugin schema', async ({ page }) => {
  const schemaResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith('/api/plugins/zone-plate/schema'));

  await page.goto('/plugins/zone-plate');
  const schemaResponse = await schemaResponsePromise;
  expect(schemaResponse.ok()).toBeTruthy();

  const form = page.locator('[data-plugin-schema="zone-plate"]');
  await expect(form).toBeVisible();
  await expect(form.locator('[data-schema-group="geometry"]')).toBeVisible();
  await expect(form.locator('[data-schema-group="off-axis-target"]')).toBeVisible();
  await expect(form.locator('[data-schema-group="production"]')).toBeVisible();

  const aperture = form.getByRole('spinbutton', { name: /^Aperture diameter \(mm\)/ });
  await aperture.fill('0');
  await expect(aperture).toHaveAttribute('aria-invalid', 'true');
  await aperture.fill('12');
  await form.getByRole('spinbutton', { name: /^Focal length \(mm\)/ }).fill('750');
  await form.getByRole('spinbutton', { name: /^Wavelength \(nm\)/ }).fill('532');
  await form.getByRole('spinbutton', { name: /^Target offset X \(mm\)/ }).fill('2');
  await form.getByRole('spinbutton', { name: /^Target offset Y \(mm\)/ }).fill('-1');
  await form.getByRole('spinbutton', { name: /^Printer DPI/ }).fill('2400');
  await form.getByLabel('Mask type').selectOption('GREYSCALE_PHASE');
  await form.getByLabel('Polarity').selectOption('NEGATIVE');

  const actionBar = page.locator('[data-plugin-action-bar="true"]');
  const render = actionBar.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'PNG' })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'SVG' })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'PDF', exact: true })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'DXF' })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'Gerber' })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'Calibration PDF' })).toBeVisible();
  await expect(actionBar.getByRole('button', { name: 'STL' })).toHaveCount(0);

  await expect(page.getByRole('heading', { name: 'Experimental validation' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Optical propagation preview' })).toBeVisible();

  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'Fresnel zone plate preview' }))
    .toBeVisible({ timeout: 30_000 });

  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(save).toBeEnabled({ timeout: 30_000 });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);

  expect(job.plugin.id).toBe('zone-plate');
  expect(job.parameters).toMatchObject({
    apertureDiameterMm: 12,
    focalLengthMm: 750,
    wavelengthNm: 532,
    targetOffsetXmm: 2,
    targetOffsetYmm: -1,
    dpi: 2400,
    maskType: 'GREYSCALE_PHASE',
    polarity: 'NEGATIVE',
  });
});

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

  const macroRadius = form.getByLabel('Macro radius (mm)');
  await macroRadius.fill('0');
  await expect(macroRadius).toHaveAttribute('aria-invalid', 'true');
  await expect(form.getByText(/Enter a finite number within the allowed range/)).toBeVisible();

  await macroRadius.fill('20');
  await form.getByLabel('Sub-element diameter (mm)').fill('5');
  await form.getByLabel('Sub-element pitch (mm)').fill('6');
  await form.getByLabel('Focal length (mm)').fill('500');
  await form.getByLabel('Wavelength (nm)').fill('550');
  await form.getByLabel('Mask type').selectOption('GREYSCALE_PHASE');
  await form.getByLabel('Polarity').selectOption('NEGATIVE');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByText(/sub-elements ·.*px per side/)).toBeVisible({ timeout: 30_000 });
});

test('nested RGB schema paths update the request used for rendering', async ({ page }) => {
  await page.goto('/plugins/rgb-zone-plate');
  const form = page.locator('[data-plugin-schema="rgb-zone-plate"]');
  await expect(form).toBeVisible();
  await expect(page.getByRole('tab', { name: 'RGB' }))
    .toHaveAttribute('aria-selected', 'true');

  await form.getByLabel('Aperture diameter (mm)').fill('7');
  await form.getByLabel('Focal length (mm)').fill('250');
  await form.getByLabel('Printer DPI').fill('900');
  await form.getByLabel('Red wavelength (nm)').fill('650');
  await form.getByLabel('Green wavelength (nm)').fill('540');
  await form.getByLabel('Blue wavelength (nm)').fill('460');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'RGB zone plate preview' }))
    .toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'PDF' })).toHaveCount(0);
});

test('trusted focus-point widget edits a schema-owned array', async ({ page }) => {
  await page.goto('/plugins/multi-focus');
  const form = page.locator('[data-plugin-schema="multi-focus"]');
  await expect(form).toBeVisible();

  await expect(form.getByLabel('x1 (mm)')).toHaveValue('-5');
  await expect(form.getByLabel('x2 (mm)')).toHaveValue('5');
  await form.getByRole('button', { name: '+ Add focus point' }).click();
  await expect(form.getByLabel('x3 (mm)')).toHaveValue('0');
  await form.getByLabel('x3 (mm)').fill('12');
  await form.getByLabel('z3 (mm)').fill('800');
  await form.getByRole('button', { name: 'Remove focus 2' }).click();
  await expect(form.getByLabel('x2 (mm)')).toHaveValue('12');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'Multi-focus preview' }))
    .toBeVisible({ timeout: 30_000 });
});

test('trusted Window Foil widget round-trips optional per-cell overrides', async ({ page }) => {
  await page.goto('/plugins/window-foil');
  const form = page.locator('[data-plugin-schema="window-foil"]');
  await expect(form).toBeVisible();
  await expect(form.getByLabel('Draw crop marks')).toBeChecked();

  await form.getByText('Per-cell layout', { exact: true }).click();
  await form.getByRole('button', { name: '+ Add cell specification' }).click();
  await form.getByLabel('Cell 1 focal length (mm)').fill('750');
  await form.getByLabel('Cell 1 target X (mm)').fill('2');
  await form.getByLabel('Cell 1 target Y (mm)').fill('-3');

  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(save).toBeEnabled({ timeout: 30_000 });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
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

  await form.getByLabel('Target image').setInputFiles({
    name: 'target.png',
    mimeType: 'image/png',
    buffer: ONE_PIXEL_PNG,
  });
  await expect(page.getByRole('status')).toContainText('Loaded target.png');
  await form.getByLabel('Side (px)').fill('64');
  await form.getByLabel('Gerchberg–Saxton iterations').fill('5');

  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(save).toBeEnabled({ timeout: 30_000 });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);

  expect(job.plugin.id).toBe('hologram');
  expect(job.parameters.targetImageBase64).toBe(ONE_PIXEL_PNG.toString('base64'));
  expect(job.parameters.sidePx).toBe(64);
  expect(job.parameters.iterations).toBe(5);

  await form.getByRole('button', { name: 'Clear image' }).click();
  await expect(save).toBeDisabled();
});

test('tab navigation and browser history use stable plugin-id routes', async ({ page }) => {
  await page.goto('/plugins/hex-macro-cell');
  await expect(page.locator('[data-plugin-schema="hex-macro-cell"]')).toBeVisible();

  await page.getByRole('tab', { name: 'Single ZP' }).click();
  await expect(page).toHaveURL(/\/plugins\/zone-plate$/);
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('[data-plugin-schema="zone-plate"]')).toBeVisible();

  await page.goBack();
  await expect(page).toHaveURL(/\/plugins\/hex-macro-cell$/);
  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('[data-plugin-schema="hex-macro-cell"]')).toBeVisible();
});
