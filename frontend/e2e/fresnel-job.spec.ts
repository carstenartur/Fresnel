import { expect, test } from '@playwright/test';

const MEDIA_TYPE = 'application/vnd.carstenartur.fresnel.job+json';

function fileInput(page: import('@playwright/test').Page) {
  return page.locator('input[type="file"][accept*=".fresnel"]');
}

test('saves the current zone plate as a canonical .fresnel job', async ({ page }) => {
  await page.goto('/');

  await page.getByLabel('Aperture diameter (mm)', { exact: true }).fill('8');
  await page.getByLabel('Focal length (mm)', { exact: true }).fill('500');
  await page.getByLabel('Wavelength (nm)', { exact: true }).fill('632');

  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(save).toBeEnabled();

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
  ]);

  expect(download.suggestedFilename()).toMatch(/\.fresnel$/);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const job = JSON.parse(json);

  expect(job.format).toBe('io.github.carstenartur.fresnel.job');
  expect(job.formatVersion).toBe(1);
  expect(job.plugin.id).toBe('zone-plate');
  expect(job.parameters.apertureDiameterMm).toBe(8);
  expect(job.parameters.focalLengthMm).toBe(500);
  expect(job.parameters.wavelengthNm).toBe(632);
  expect(job.provenance.parameterSha256).toMatch(/^[0-9a-f]{64}$/);
});

test('opens a job in the editor selected by its stable plugin id', async ({ page }) => {
  await page.goto('/');

  const job = {
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
      subDiameterMm: 6,
      subPitchMm: 7,
      focalLengthMm: 850,
      targetOffsetXmm: 1,
      targetOffsetYmm: -2,
      wavelengthNm: 532,
      dpi: 1200,
      maskType: 'BINARY_AMPLITUDE',
      polarity: 'POSITIVE',
    },
  };

  await fileInput(page).setInputFiles({
    name: 'hex-example.fresnel',
    mimeType: MEDIA_TYPE,
    buffer: Buffer.from(JSON.stringify(job)),
  });

  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Macro radius (mm)')).toHaveValue('42');
  await expect(page.getByLabel('Focal length (mm)')).toHaveValue('850');
  await expect(page.getByRole('status')).toContainText('Opened "hex-example.fresnel"');
});

test('migrates a legacy design JSON before populating the editor', async ({ page }) => {
  await page.goto('/');

  const legacy = {
    kind: 'single',
    version: 1,
    payload: {
      apertureDiameterMm: 12,
      focalLengthMm: 750,
      wavelengthNm: 550,
      dpi: 1200,
    },
  };

  await fileInput(page).setInputFiles({
    name: 'legacy-zone-plate.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(legacy)),
  });

  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Aperture diameter (mm)', { exact: true })).toHaveValue('12');
  await expect(page.getByLabel('Focal length (mm)', { exact: true })).toHaveValue('750');
  await expect(page.getByRole('status')).toContainText('Migrated legacy design');
});
