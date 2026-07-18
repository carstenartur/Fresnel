import { expect, test, type Download, type Locator, type Page } from '@playwright/test';

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

function jobInput(page: Page): Locator {
  return page.locator('input[type="file"][accept*=".fresnel"]');
}

async function clickDownload(button: Locator): Promise<Download> {
  await expect(button).toBeEnabled({ timeout: 30_000 });
  const page = button.page();
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    button.click(),
  ]);
  const stream = await download.createReadStream();
  let bytes = 0;
  for await (const chunk of stream) bytes += chunk.length;
  expect(bytes).toBeGreaterThan(64);
  return download;
}

async function saveJob(page: Page): Promise<Download> {
  return clickDownload(page.getByRole('button', { name: 'Save job (.fresnel)' }));
}

test('Hex Macro Cell completes preview, export and job round trip', async ({ page }) => {
  await page.goto('/plugins/hex-macro-cell');
  const form = page.locator('[data-plugin-schema="hex-macro-cell"]');
  await expect(form).toBeVisible();

  await form.getByLabel('Macro radius (mm)').fill('15');
  await form.getByLabel('Sub-element diameter (mm)').fill('5');
  await form.getByLabel('Sub-element pitch (mm)').fill('6');
  await form.getByLabel('Focal length (mm)').fill('500');
  await form.getByLabel('Printer DPI').fill('1200');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'Hex macro cell preview' }))
    .toBeVisible({ timeout: 30_000 });
  await clickDownload(page.getByRole('button', { name: 'PNG', exact: true }));

  const saved = await saveJob(page);
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  await form.getByLabel('Macro radius (mm)').fill('20');
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/hex-macro-cell$/);
  await expect(form.getByLabel('Macro radius (mm)')).toHaveValue('15');
});

test('Window Foil completes preview, PDF export and job round trip', async ({ page }) => {
  await page.goto('/plugins/window-foil');
  const form = page.locator('[data-plugin-schema="window-foil"]');
  await expect(form).toBeVisible();

  await form.getByLabel('Sheet width (mm)').fill('50');
  await form.getByLabel('Sheet height (mm)').fill('30');
  await form.getByLabel('Macro radius (mm)').fill('10');
  await form.getByLabel('Sub-element diameter (mm)').fill('3');
  await form.getByLabel('Sub-element pitch (mm)').fill('4');
  await form.getByLabel('Printer DPI').fill('150');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'Window foil preview' }))
    .toBeVisible({ timeout: 30_000 });
  await clickDownload(page.getByRole('button', { name: 'PDF (A4)' }));

  const saved = await saveJob(page);
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  await form.getByLabel('Sheet width (mm)').fill('60');
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/window-foil$/);
  await expect(form.getByLabel('Sheet width (mm)')).toHaveValue('50');
});

test('Multi-Focus completes preview, PNG export and job round trip', async ({ page }) => {
  await page.goto('/plugins/multi-focus');
  const form = page.locator('[data-plugin-schema="multi-focus"]');
  await expect(form).toBeVisible();

  await form.getByLabel('Aperture diameter (mm)').fill('6');
  await form.getByLabel('x1 (mm)').fill('-3');
  await form.getByLabel('x2 (mm)').fill('3');
  await form.getByLabel('z1 (mm)').fill('600');
  await form.getByLabel('z2 (mm)').fill('600');
  await form.getByLabel('Printer DPI').fill('600');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'Multi-focus preview' }))
    .toBeVisible({ timeout: 30_000 });
  await clickDownload(page.getByRole('button', { name: 'PNG', exact: true }));

  const saved = await saveJob(page);
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  await form.getByLabel('Aperture diameter (mm)').fill('8');
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/multi-focus$/);
  await expect(form.getByLabel('Aperture diameter (mm)')).toHaveValue('6');
  await expect(form.getByLabel('x1 (mm)')).toHaveValue('-3');
});

test('RGB Zone Plate completes preview, PNG export and job round trip', async ({ page }) => {
  await page.goto('/plugins/rgb-zone-plate');
  const form = page.locator('[data-plugin-schema="rgb-zone-plate"]');
  await expect(form).toBeVisible();

  await form.getByLabel('Aperture diameter (mm)').fill('5');
  await form.getByLabel('Focal length (mm)').fill('200');
  await form.getByLabel('Printer DPI').fill('1200');
  await form.getByLabel('Red wavelength (nm)').fill('650');
  await form.getByLabel('Green wavelength (nm)').fill('540');
  await form.getByLabel('Blue wavelength (nm)').fill('460');

  const render = page.getByRole('button', { name: 'Render preview' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await render.click();
  await expect(page.getByRole('img', { name: 'RGB zone plate preview' }))
    .toBeVisible({ timeout: 30_000 });
  await clickDownload(page.getByRole('button', { name: 'PNG', exact: true }));

  const saved = await saveJob(page);
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  await form.getByLabel('Red wavelength (nm)').fill('630');
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/rgb-zone-plate$/);
  await expect(form.getByLabel('Red wavelength (nm)')).toHaveValue('650');
  await expect(form.getByLabel('Green wavelength (nm)')).toHaveValue('540');
});

test('Hologram completes synthesis, PNG export and embedded-asset job round trip', async ({ page }) => {
  await page.goto('/plugins/hologram');
  const form = page.locator('[data-plugin-schema="hologram"]');
  await expect(form).toBeVisible();

  await form.getByLabel('Target image').setInputFiles({
    name: 'target.png',
    mimeType: 'image/png',
    buffer: ONE_PIXEL_PNG,
  });
  await form.getByLabel('Side (px)').fill('64');
  await form.getByLabel('Gerchberg–Saxton iterations').fill('5');

  const synthesise = page.getByRole('button', { name: 'Synthesise mask' });
  await expect(synthesise).toBeEnabled({ timeout: 30_000 });
  await synthesise.click();
  await expect(page.getByRole('img', { name: 'Hologram phase mask' }))
    .toBeVisible({ timeout: 30_000 });
  await clickDownload(page.getByRole('button', { name: 'PNG', exact: true }));

  const saved = await saveJob(page);
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  await form.getByRole('button', { name: 'Clear image' }).click();
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/hologram$/);
  await expect(form.getByLabel('Side (px)')).toHaveValue('64');
  await expect(form.getByRole('status')).toContainText('Embedded target image loaded');
});
