import { expect, test } from '@playwright/test';

function parameterRow(page: import('@playwright/test').Page, path: string) {
  return page.locator(`[data-parameter-path="${path}"]`);
}

test('grounds natural language, validates, previews, saves and reopens a zone plate job', async ({ page }) => {
  await page.goto('/assistant');

  await expect(page.getByRole('heading', { name: 'Experiment Copilot' })).toBeVisible();
  await page.getByLabel('Optical goal').fill(
    'Create a printable 532 nm zone plate with a one metre focus at 1200 DPI. ' +
      'Prefer a robust design that is easy to fabricate.',
  );
  await page.getByRole('button', { name: 'Create grounded proposal' }).click();

  await expect(page.getByTestId('copilot-parameter-review')).toBeVisible();
  await expect(parameterRow(page, 'wavelengthNm').getByRole('spinbutton')).toHaveValue('532');
  await expect(parameterRow(page, 'focalLengthMm').getByRole('spinbutton')).toHaveValue('1000');
  await expect(parameterRow(page, 'dpi').getByRole('spinbutton')).toHaveValue('1200');
  await expect(parameterRow(page, 'apertureDiameterMm').locator('[data-source]'))
    .toHaveAttribute('data-source', 'COPILOT_INFERRED');

  // Prove that user edits supersede the proposal and are visible in provenance.
  await parameterRow(page, 'apertureDiameterMm').getByRole('spinbutton').fill('8');
  await expect(parameterRow(page, 'apertureDiameterMm').locator('[data-source]'))
    .toHaveAttribute('data-source', 'USER_SUPPLIED');

  await page.getByRole('button', { name: 'Validate & preview' }).click();
  await expect(page.getByTestId('copilot-validation'))
    .toContainText('Deterministic validation passed', { timeout: 30_000 });
  await expect(page.getByTestId('copilot-preview')).toBeVisible({ timeout: 30_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Save job (.fresnel)' }).click(),
  ]);
  expect(download.suggestedFilename()).toBe('fresnel-copilot-zone-plate.fresnel');
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const saved = JSON.parse(json);
  expect(saved.plugin.id).toBe('zone-plate');
  expect(saved.parameters.apertureDiameterMm).toBe(8);
  expect(saved.parameters.wavelengthNm).toBe(532);
  expect(saved.parameters.focalLengthMm).toBe(1000);
  expect(saved.provenance.createdWith).toBe('Fresnel experiment copilot (mock)');
  expect(saved.provenance.parameterSha256).toMatch(/^[0-9a-f]{64}$/);

  await page.getByRole('button', { name: 'Open in Zone Plate editor' }).click();
  await expect(page).toHaveURL(/\/plugins\/zone-plate$/);
  const editor = page.locator('[data-plugin-schema="zone-plate"]');
  await expect(editor.getByLabel(/^Aperture diameter \(mm\)/)).toHaveValue('8');
  await expect(editor.getByLabel(/^Focal length \(mm\)/)).toHaveValue('1000');
  await expect(editor.getByLabel(/^Wavelength \(nm\)/)).toHaveValue('532');
  await expect(editor.getByLabel(/^Printer DPI/)).toHaveValue('1200');
});

test('asks clarification instead of silently using defaults for missing optical intent', async ({ page }) => {
  await page.goto('/assistant');
  await page.getByLabel('Optical goal').fill('Create a robust printable zone plate at 1200 DPI.');
  await page.getByRole('button', { name: 'Create grounded proposal' }).click();

  const questions = page.getByTestId('copilot-questions');
  await expect(questions).toContainText('wavelength');
  await expect(questions).toContainText('focal distance');
  await expect(page.getByRole('button', { name: 'Save job (.fresnel)' })).toHaveCount(0);
});
