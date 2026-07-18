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

  const form = page.locator('[data-plugin-schema="zone-plate"]');
  await expect(form.getByRole('spinbutton', { name: /^Aperture diameter \(mm\)/ }))
    .toHaveValue('10');
  await expect(form.getByRole('spinbutton', { name: /^Focal length \(mm\)/ }))
    .toHaveValue('1000');
  await expect(form.getByRole('spinbutton', { name: /^Wavelength \(nm\)/ }))
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
