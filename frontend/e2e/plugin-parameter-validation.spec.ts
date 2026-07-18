import { expect, test } from '@playwright/test';

test('canonical validation reports nested paths and gates production actions', async ({ page }) => {
  await page.goto('/plugins/multi-focus');
  await expect(page.locator('[data-plugin-schema="multi-focus"]')).toBeVisible();

  const render = page.getByRole('button', { name: 'Render preview' });
  const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
  await expect(render).toBeEnabled({ timeout: 30_000 });
  await expect(save).toBeEnabled({ timeout: 30_000 });

  await page.getByLabel('z1 (mm)').fill('0');

  const validation = page.locator('[data-parameter-validation="invalid"]');
  await expect(validation).toBeVisible({ timeout: 30_000 });
  await expect(validation).toContainText('focusPoints[0].zMm');
  await expect(render).toBeDisabled();
  await expect(save).toBeDisabled();

  await page.getByLabel('z1 (mm)').fill('1000');
  await expect(validation).toHaveCount(0, { timeout: 30_000 });
  await expect(render).toBeEnabled();
  await expect(save).toBeEnabled();
});
