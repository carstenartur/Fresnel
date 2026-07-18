import { expect, test } from '@playwright/test';

const HEX_JOB = {
  $schema: 'https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json',
  format: 'io.github.carstenartur.fresnel.job',
  formatVersion: 1,
  plugin: {
    id: 'hex-macro-cell',
    parameterSchemaVersion: 1,
    algorithmVersion: 'hex-macro-cell/1',
  },
  parameters: {
    macroRadiusMm: 42,
    subDiameterMm: 7,
    subPitchMm: 8,
    focalLengthMm: 850,
    targetOffsetXmm: 3,
    targetOffsetYmm: -2,
    wavelengthNm: 532,
    dpi: 1200,
    maskType: 'GREYSCALE_PHASE',
    polarity: 'NEGATIVE',
  },
};

test('opening a .fresnel job selects its stable schema-driven plugin route', async ({ page }) => {
  await page.goto('/');

  await page.locator('input[type="file"]').setInputFiles({
    name: 'imported-hex.fresnel',
    mimeType: 'application/vnd.carstenartur.fresnel.job+json',
    buffer: Buffer.from(JSON.stringify(HEX_JOB)),
  });

  await expect(page).toHaveURL(/\/plugins\/hex-macro-cell$/);
  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByText('Opened "imported-hex.fresnel" as hex-macro-cell.')).toBeVisible();

  const form = page.locator('[data-plugin-schema="hex-macro-cell"]');
  await expect(form).toBeVisible();
  await expect(page.getByLabel('Macro radius (mm)')).toHaveValue('42');
  await expect(page.getByLabel('Sub-element diameter (mm)')).toHaveValue('7');
  await expect(page.getByLabel('Sub-element pitch (mm)')).toHaveValue('8');
  await expect(page.getByLabel('Focal length (mm)')).toHaveValue('850');
  await expect(page.getByLabel('Target X (mm)')).toHaveValue('3');
  await expect(page.getByLabel('Target Y (mm)')).toHaveValue('-2');
  await expect(page.getByLabel('Wavelength (nm)')).toHaveValue('532');
  await expect(page.getByLabel('Printer DPI')).toHaveValue('1200');
  await expect(page.getByLabel('Mask type')).toHaveValue('GREYSCALE_PHASE');
  await expect(page.getByLabel('Polarity')).toHaveValue('NEGATIVE');

  await expect(page.getByRole('button', { name: 'Render preview' }))
    .toBeEnabled({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Save job (.fresnel)' })).toBeEnabled();
});
