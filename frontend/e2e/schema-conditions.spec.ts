import { expect, test } from '@playwright/test';

test('radio conditions hide groups without discarding parameter values', async ({ page }) => {
  await page.goto('/plugins/hologram');
  const form = page.locator('[data-plugin-schema="hologram"]');
  await expect(form).toBeVisible();

  const greyscale = form.getByRole('radio', { name: 'Greyscale phase' });
  const binary = form.getByRole('radio', { name: 'Binary phase' });
  await expect(greyscale).toBeChecked();
  await expect(binary).not.toBeChecked();

  const fabrication = form.locator('[data-schema-group="fabrication"]');
  await expect(fabrication).toHaveCount(1);
  await form.getByText('Phase-relief fabrication', { exact: true }).click();
  const maximumPhase = form.getByRole('spinbutton', { name: /^Maximum phase shift \(rad\)/ });
  await maximumPhase.fill('5.5');

  await binary.check();
  await expect(fabrication).toHaveCount(0);

  await greyscale.check();
  await expect(form.locator('[data-schema-group="fabrication"]')).toHaveCount(1);
  await form.getByText('Phase-relief fabrication', { exact: true }).click();
  await expect(form.getByRole('spinbutton', { name: /^Maximum phase shift \(rad\)/ }))
    .toHaveValue('5.5');
});
