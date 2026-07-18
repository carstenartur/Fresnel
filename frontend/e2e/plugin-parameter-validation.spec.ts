import { expect, test } from '@playwright/test';

test('canonical validation reports nested paths and gates all actions for structural errors', async ({ page }) => {
  await page.goto('/plugins/multi-focus');
  await expect(page.locator('[data-plugin-schema="multi-focus"]')).toBeVisible();

  const render = page.getByRole('button', { name: 'Render preview' });
  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  const depth = page.getByLabel('z1 (mm)');
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await expect(save).toBeEnabled({ timeout: 30_000 });

  // Incomplete text stays in the control instead of becoming 0 or silently
  // exporting the previous value. The visible UI state gates all actions even
  // though the serialisable model still holds its last complete number.
  await depth.fill('');
  await expect(depth).toHaveAttribute('aria-invalid', 'true');
  await expect(render).toBeDisabled();
  await expect(save).toBeDisabled();

  await depth.fill('0');

  const validation = page.locator('[data-parameter-validation="invalid"]');
  await expect(validation).toBeVisible({ timeout: 30_000 });
  await expect(validation).toContainText('focusPoints[0].zMm');
  await expect(render).toBeDisabled();
  await expect(save).toBeDisabled();

  await depth.fill('1000');
  await expect(validation).toHaveCount(0, { timeout: 30_000 });
  await expect(render).toBeEnabled();
  await expect(save).toBeEnabled();
});

test('a structurally valid draft can be previewed and saved while production stays gated', async ({ page }) => {
  await page.goto('/plugins/zone-plate');
  const form = page.locator('[data-plugin-schema="zone-plate"]');
  await expect(form).toBeVisible();

  // This geometry is structurally valid but intentionally undersampled at 600 DPI.
  await form.getByRole('spinbutton', { name: /^Aperture diameter \(mm\)/ }).fill('8');
  await form.getByRole('spinbutton', { name: /^Focal length \(mm\)/ }).fill('500');
  await form.getByRole('spinbutton', { name: /^Wavelength \(nm\)/ }).fill('632');
  await form.getByRole('spinbutton', { name: /^Printer DPI/ }).fill('600');

  const render = page.getByRole('button', { name: 'Render preview' });
  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  const png = page.getByRole('button', { name: 'PNG', exact: true });

  await expect(page.getByRole('heading', { name: 'Design metrics' }))
    .toBeVisible({ timeout: 30_000 });
  await expect(render).toBeEnabled();
  await expect(save).toBeEnabled();
  await expect(png).toBeDisabled();

  await render.click();
  await expect(page.getByRole('img', { name: 'Fresnel zone plate preview' }))
    .toBeVisible({ timeout: 30_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);
  expect(job.parameters).toMatchObject({
    apertureDiameterMm: 8,
    focalLengthMm: 500,
    wavelengthNm: 632,
    dpi: 600,
    targetOffsetXmm: 0,
    targetOffsetYmm: 0,
    maskType: 'BINARY_AMPLITUDE',
    polarity: 'POSITIVE',
  });
});
