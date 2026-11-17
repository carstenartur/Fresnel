import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

test('opens a verified Photographer pair directly in the BOS editor and preserves its source identity', async ({
  page,
}) => {
  const id = 'adf7d59a-42af-4e38-907f-6149fef00005',
    capturedAt = '2026-11-17T16:00:00Z';
  await page.route('**/api/capture-providers', (r) =>
    r.fulfill({
      json: {
        state: 'CONNECTED',
        checkedAt: capturedAt,
        providers: [
          { id: 'photographer-rest', displayName: 'Photographer', state: 'CONNECTED', devices: [] },
        ],
      },
    }),
  );
  await page.goto('/plugins/background-oriented-schlieren');
  const png = Buffer.from(
    await page.evaluate(() => {
      const c = document.createElement('canvas');
      c.width = 160;
      c.height = 128;
      const ctx = c.getContext('2d')!,
        im = ctx.createImageData(c.width, c.height);
      let state = 14;
      for (let i = 0; i < im.data.length; i += 4) {
        state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
        im.data[i] = im.data[i + 1] = im.data[i + 2] = state >>> 24;
        im.data[i + 3] = 255;
      }
      ctx.putImageData(im, 0, 0);
      return c.toDataURL().split(',')[1];
    }),
    'base64',
  );
  const source = {
    sha256: createHash('sha256').update(png).digest('hex'),
    byteLength: png.length,
    capturedAt,
  };
  const view = {
    id,
    state: 'COMPLETED',
    pendingStep: null,
    cameraSelection: 'a'.repeat(64),
    reference: source,
    disturbed: source,
  };
  let corrupt = true;
  await page.route('**/api/experiments/bos-captures**', async (route) => {
    const url = new URL(route.request().url()),
      p = url.pathname.split('/api/experiments/bos-captures')[1];
    expect(route.request().method()).toBe('GET');
    if (!p) return route.fulfill({ json: [view] });
    if (p === '/cameras') return route.fulfill({ json: [] });
    if (p === '/' + id) return route.fulfill({ json: view });
    if (p.startsWith('/' + id + '/images/')) {
      const data = Buffer.from(png);
      if (corrupt && p.endsWith('/disturbed')) data[data.length - 1] ^= 1;
      return route.fulfill({ contentType: 'image/png', body: data });
    }
    throw Error('Unexpected capture path ' + p);
  });
  await page.getByRole('button', { name: 'Open paired Photographer capture' }).click();
  const acquisition = page.locator('#bos-camera-acquisition');
  await acquisition.getByLabel('Fresnel username').fill('operator');
  await acquisition.getByLabel('Password', { exact: true }).fill('pw');
  await acquisition.getByRole('button', { name: 'Sign in', exact: true }).click();
  await acquisition.getByLabel('Saved captures').selectOption(id);
  const use = acquisition.getByRole('button', { name: 'Use verified pair in BOS analysis' });
  await use.click();
  await expect(acquisition.getByRole('alert').last()).toContainText('SHA-256');
  const analysis = page.getByRole('region', { name: 'Still-pair displacement' });
  await expect(analysis.getByRole('button', { name: 'Analyze still pair' })).toBeDisabled();
  corrupt = false;
  await use.click();
  await expect(analysis.getByText(new RegExp('Photographer capture ' + id))).toBeVisible();
  await analysis.getByRole('button', { name: 'Analyze still pair' }).click();
  await expect(analysis.getByRole('status')).toContainText('Registered 12 of 12');
  const download = page.waitForEvent('download');
  await analysis.getByRole('link', { name: 'Download numeric grid' }).click();
  const report = JSON.parse(await readFile((await (await download).path())!, 'utf8'));
  expect(report.captureProvider).toBe('photographer-rest');
  expect(report.captureOrigin).toEqual({
    provider: 'photographer-rest',
    captureId: id,
    cameraSelection: 'a'.repeat(64),
    reference: source,
    disturbed: source,
  });
  expect(report.sources.reference.sha256).toBe(source.sha256);
});
