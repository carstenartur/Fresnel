import { expect, test } from '@playwright/test';

test('canonical validation reports nested paths and gates production actions', async ({ page }) => {
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
