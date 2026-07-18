import { expect, test } from '@playwright/test';

const EXAMPLE_JOB = '../docs/jobs/zone-plate/on-axis.fresnel';

function fileInput(page: import('@playwright/test').Page) {
  return page.locator('input[type="file"][accept*=".fresnel"]');
}

test('opens and resaves the checked-in zone plate example', async ({ page }) => {
  await page.goto('/');

  await fileInput(page).setInputFiles(EXAMPLE_JOB);
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Aperture diameter (mm)', { exact: true }))
    .toHaveValue('10');
  await expect(page.getByLabel('Focal length (mm)', { exact: true }))
    .toHaveValue('1000');
  await expect(page.getByLabel('Wavelength (nm)', { exact: true }))
    .toHaveValue('550');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Save job (.fresnel)' }).click(),
  ]);
  const stream = await download.createReadStream();
  let json = '';
  for await (const chunk of stream) json += chunk.toString();
  const saved = JSON.parse(json);

  expect(saved.plugin.id).toBe('zone-plate');
  expect(saved.parameters.apertureDiameterMm).toBe(10);
  expect(saved.production.outputs).toEqual([
    {
      id: 'documentation-preview',
      format: 'png',
      filename: 'on-axis.png',
    },
  ]);
  expect(saved.provenance.parameterSha256).toMatch(/^[0-9a-f]{64}$/);
});
