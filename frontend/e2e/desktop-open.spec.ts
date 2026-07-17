import { expect, test } from '@playwright/test';

const TOKEN = 'A'.repeat(43);

const HEX_JOB = {
  format: 'io.github.carstenartur.fresnel.job',
  formatVersion: 1,
  plugin: {
    id: 'hex-macro-cell',
    parameterSchemaVersion: 1,
    algorithmVersion: 'hex-macro-cell/1',
  },
  parameters: {
    macroRadiusMm: 37,
    subDiameterMm: 6,
    subPitchMm: 7,
    focalLengthMm: 900,
    targetOffsetXmm: 0,
    targetOffsetYmm: 0,
    wavelengthNm: 532,
    dpi: 1200,
    maskType: 'BINARY_AMPLITUDE',
    polarity: 'POSITIVE',
  },
};

test('consumes a desktop token, cleans the URL and selects the correct editor', async ({ page }) => {
  let consumeCount = 0;
  await page.route(`**/api/desktop/open/${TOKEN}`, async (route) => {
    consumeCount += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ valid: true, job: HEX_JOB }),
    });
  });

  await page.goto(`/?fresnelOpen=${TOKEN}`);

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('tab', { name: 'Hex macro' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Macro radius (mm)')).toHaveValue('37');
  await expect(page.getByLabel('Focal length (mm)')).toHaveValue('900');
  await expect(page.getByRole('status')).toContainText('Opened desktop job as hex-macro-cell');
  expect(consumeCount).toBe(1);

  // Refreshing the cleaned URL must not attempt to consume the one-time token again.
  await page.reload();
  await expect(page).toHaveURL(/\/$/);
  expect(consumeCount).toBe(1);
});

test('shows an invalid desktop job without replacing the current editor state', async ({ page }) => {
  await page.route(`**/api/desktop/open/${TOKEN}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        valid: false,
        errorCode: 'INVALID_JOB',
        errorMessage: 'Unsupported Fresnel job format version 999.',
      }),
    });
  });

  await page.goto(`/?fresnelOpen=${TOKEN}`);

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('alert'))
    .toContainText('Unsupported Fresnel job format version 999');
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Aperture diameter (mm)', { exact: true })).toHaveValue('10');
});

test('shows an expired-token error and removes the token from browser history', async ({ page }) => {
  await page.route(`**/api/desktop/open/${TOKEN}`, async (route) => {
    await route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  });

  await page.goto(`/?fresnelOpen=${TOKEN}`);

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('alert'))
    .toContainText('expired or was already consumed');
});
