import { expect, test } from '@playwright/test';

const MEDIA_TYPE = 'application/vnd.carstenartur.fresnel.job+json';

function fileInput(page: import('@playwright/test').Page) {
  return page.locator('input[type="file"][accept*=".fresnel"]');
}

function zonePlateForm(page: import('@playwright/test').Page) {
  return page.locator('[data-plugin-schema="zone-plate"]');
}

function zonePlateNumber(
  page: import('@playwright/test').Page,
  name: RegExp,
) {
  return zonePlateForm(page).getByRole('spinbutton', { name });
}

test('saves and reopens the current zone plate as a canonical .fresnel job', async ({ page }) => {
  await page.goto('/');

  const aperture = zonePlateNumber(page, /^Aperture diameter \(mm\)/);
  const focalLength = zonePlateNumber(page, /^Focal length \(mm\)/);
  const wavelength = zonePlateNumber(page, /^Wavelength \(nm\)/);
  await aperture.fill('8');
  await focalLength.fill('500');
  await wavelength.fill('632');

  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(save).toBeEnabled({ timeout: 30_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    save.click(),
  ]);

  expect(download.suggestedFilename()).toMatch(/\.fresnel$/);
  const savedPath = await download.path();
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

  // Prove that the downloaded public artifact is also accepted by the GUI and
  // restores the normalized parameter state.
  await aperture.fill('5');
  await focalLength.fill('250');
  await fileInput(page).setInputFiles(savedPath);
  await expect(aperture).toHaveValue('8');
  await expect(focalLength).toHaveValue('500');
  await expect(wavelength).toHaveValue('632');
});

test('preserves production and compatibility metadata while editing a loaded job', async ({ page }) => {
  await page.goto('/');

  const sourceJob = {
    $schema: 'https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json',
    format: 'io.github.carstenartur.fresnel.job',
    formatVersion: 1,
    plugin: {
      id: 'zone-plate',
      parameterSchemaVersion: 1,
      algorithmVersion: 'zone-plate/2026.07',
    },
    parameters: {
      apertureDiameterMm: 10,
      focalLengthMm: 1000,
      wavelengthNm: 550,
      dpi: 1200,
      targetOffsetXmm: 0,
      targetOffsetYmm: 0,
      maskType: 'BINARY_AMPLITUDE',
      polarity: 'POSITIVE',
    },
    production: {
      outputs: [
        {
          id: 'print-sheet',
          format: 'pdf',
          filename: 'zone-plate-print.pdf',
          sheet: 'A4',
          printScale: 1,
        },
      ],
    },
    provenance: {
      createdWith: 'Fresnel laboratory template',
      applicationVersion: '1.2.3',
    },
  };

  await fileInput(page).setInputFiles({
    name: 'production-job.fresnel',
    mimeType: MEDIA_TYPE,
    buffer: Buffer.from(JSON.stringify(sourceJob)),
  });
  await zonePlateNumber(page, /^Focal length \(mm\)/).fill('1250');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Save job (.fresnel)' }).click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const saved = JSON.parse(json);

  expect(saved.plugin.algorithmVersion).toBe('zone-plate/2026.07');
  expect(saved.plugin.parameterSchemaVersion).toBe(1);
  expect(saved.parameters.focalLengthMm).toBe(1250);
  expect(saved.production).toEqual(sourceJob.production);
  expect(saved.provenance.createdWith).toBe('Fresnel laboratory template');
  expect(saved.provenance.applicationVersion).toBe('1.2.3');
  expect(saved.provenance.parameterSha256).toMatch(/^[0-9a-f]{64}$/);
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
  const hexForm = page.locator('[data-plugin-schema="hex-macro-cell"]');
  await expect(hexForm.getByRole('spinbutton', { name: /^Macro radius \(mm\)/ }))
    .toHaveValue('42');
  await expect(hexForm.getByRole('spinbutton', { name: /^Focal length \(mm\)/ }))
    .toHaveValue('850');
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
  await expect(zonePlateNumber(page, /^Aperture diameter \(mm\)/)).toHaveValue('12');
  await expect(zonePlateNumber(page, /^Focal length \(mm\)/)).toHaveValue('750');
  await expect(page.getByRole('status')).toContainText('Migrated legacy design');
});

test('rejects a future job version without replacing the current editor state', async ({ page }) => {
  await page.goto('/');

  const aperture = zonePlateNumber(page, /^Aperture diameter \(mm\)/);
  await aperture.fill('7');

  const futureJob = {
    format: 'io.github.carstenartur.fresnel.job',
    formatVersion: 999,
    plugin: {
      id: 'zone-plate',
      parameterSchemaVersion: 1,
      algorithmVersion: 'zone-plate/1',
    },
    parameters: {
      apertureDiameterMm: 99,
      focalLengthMm: 1000,
      wavelengthNm: 550,
      dpi: 1200,
    },
  };

  await fileInput(page).setInputFiles({
    name: 'future.fresnel',
    mimeType: MEDIA_TYPE,
    buffer: Buffer.from(JSON.stringify(futureJob)),
  });

  await expect(page.locator('.error-message').filter({ hasText: 'newer than supported' }))
    .toBeVisible();
  await expect(aperture).toHaveValue('7');
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
});
