import { expect, test } from '@playwright/test';

test('a backend plugin without a trusted editor is never silently hidden', async ({ page }) => {
  await page.route('**/api/plugins', async (route) => {
    const response = await route.fetch();
    const plugins = await response.json();
    await route.fulfill({
      response,
      json: [
        ...plugins,
        {
          id: 'future-optic',
          displayName: 'Future Optic',
          description: 'Test-only plugin without a trusted editor component',
          documentationUrl: 'docs/plugins/future-optic.md',
          capabilities: ['PREVIEW_PNG'],
          parameterSchemaVersion: 1,
          editorMode: 'SCHEMA',
          schemaUrl: '/api/plugins/future-optic/schema',
        },
      ],
    });
  });

  await page.goto('/plugins/zone-plate');

  const alert = page.getByRole('alert');
  await expect(alert).toContainText('No trusted editor is registered for backend plugin');
  await expect(alert).toContainText('future-optic');
  await expect(page.getByRole('tab', { name: 'Future Optic' })).toHaveCount(0);

  // Known plugins remain usable even while the integration error is explicit.
  await expect(page.getByRole('tab', { name: 'Single ZP' }))
    .toHaveAttribute('aria-selected', 'true');
});
