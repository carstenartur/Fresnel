import { expect, test } from '@playwright/test';

const connectedOverview = {
  state: 'CONNECTED',
  checkedAt: '2026-08-23T18:45:00Z',
  messageCode: 'ALL_CAPTURE_PROVIDERS_CONNECTED',
  providers: [
    {
      id: 'photographer-rest',
      displayName: 'Photographer',
      state: 'CONNECTED',
      checkedAt: '2026-08-23T18:45:00Z',
      messageCode: 'PHOTOGRAPHER_CONNECTED',
      protocolVersion: 1,
      minimumSupportedProtocolVersion: 1,
      capabilities: ['STILL_CAPTURE', 'EXTERNAL_STEP_TRIGGER'],
      supportedFormats: ['RAW_WITH_PREVIEW'],
      limits: {
        maximumSteps: 64,
        maximumAssetBytes: 2000000000,
        maximumSessionDurationSeconds: 1800,
      },
      devices: [
        {
          id: 'camera-profile-1',
          displayName: 'Canon EOS R',
          state: 'CONNECTED',
          capabilities: ['STILL_CAPTURE'],
          supportedFormats: ['RAW_WITH_PREVIEW'],
        },
      ],
    },
  ],
};

test('shows that no remote camera service is configured by default', async ({ page }) => {
  await page.goto('/');

  const status = page.getByTestId('capture-provider-status');
  await expect(status).toHaveAttribute('data-state', 'NOT_CONFIGURED');
  await expect(status).toHaveAttribute('data-stale', 'false');
  await expect(status).toContainText('Camera service');
  await expect(status).toContainText('Not configured');
  await expect(status).toContainText('No Photographer or other remote capture service is configured.');
});

test('shows a connected Photographer-style provider and camera details', async ({ page }) => {
  await page.route('**/api/capture-providers', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(connectedOverview),
    });
  });

  await page.goto('/');
  const status = page.getByTestId('capture-provider-status');
  await expect(status).toHaveAttribute('data-state', 'CONNECTED');
  await expect(status).toHaveAttribute('data-stale', 'false');
  await expect(status).toContainText('Photographer · 1 connected camera');

  await status.getByText('Connection details').click();
  await expect(status).toContainText('Canon EOS R: connected');
});

test('keeps the last confirmed connection state when a later refresh fails', async ({ page }) => {
  await page.clock.install();
  let requestCount = 0;
  await page.route('**/api/capture-providers', async (route) => {
    requestCount += 1;
    if (requestCount === 1) {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(connectedOverview),
      });
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/');
  const status = page.getByTestId('capture-provider-status');
  await expect(status).toHaveAttribute('data-state', 'CONNECTED');
  await expect(status).toHaveAttribute('data-stale', 'false');

  await page.clock.fastForward(15_001);

  await expect(status).toHaveAttribute('data-state', 'CONNECTED');
  await expect(status).toHaveAttribute('data-stale', 'true');
  await expect(status).toContainText('Connected · stale');
  await expect(status).toContainText('showing the last confirmed camera-service state');
});
