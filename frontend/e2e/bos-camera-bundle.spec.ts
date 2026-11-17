import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test, expect, type Page } from '@playwright/test';

async function numericGrid(page: Page) {
  const download = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Download numeric grid' }).click();
  return JSON.parse(await readFile((await (await download).path())!, 'utf8'));
}

test('a generated target and paired originals survive an experiment bundle round trip with the same grid', async ({
  page,
}, testInfo) => {
  const capturedAt = '2026-11-17T14:00:00Z';
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
  const form = page.locator('[data-plugin-schema="background-oriented-schlieren"]');
  await form.getByLabel('Target width (px)').fill('480');
  await form.getByLabel('Target height (px)').fill('360');
  await form.getByLabel('Dot diameter (px)').fill('4');
  await form.getByLabel('Minimum dot spacing (px)').fill('2');
  await form.getByLabel('Quiet border (px)').fill('28');
  await form.getByLabel('Pattern seed').fill('1944');
  await page.getByRole('button', { name: 'Generate target preview' }).click();
  await expect(page.getByRole('img', { name: 'Background-Oriented Schlieren dot target' })).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download target PNG' }).click();
  const png = await readFile((await (await download).path())!);
  const disturbed = Buffer.from(
    await page.evaluate(async (encoded) => {
      const image = new Image();
      image.src = 'data:image/png;base64,' + encoded;
      await image.decode();
      const c = document.createElement('canvas');
      c.width = image.width;
      c.height = image.height;
      const ctx = c.getContext('2d')!;
      ctx.drawImage(image, 0, 0);
      ctx.drawImage(image, 180, 135, 100, 90, 182, 135, 100, 90);
      return c.toDataURL().split(',')[1];
    }, png.toString('base64')),
    'base64',
  );
  const exposure = (data: Buffer) => ({
    sha256: createHash('sha256').update(data).digest('hex'),
    byteLength: data.length,
    capturedAt,
  });
  const view = {
    id: '',
    state: 'WAITING_REFERENCE',
    pendingStep: null,
    reference: null as ReturnType<typeof exposure> | null,
    disturbed: null as ReturnType<typeof exposure> | null,
    cameraSelection: '2'.repeat(64),
  };
  const triggers: string[] = [];
  await page.route('**/api/experiments/bos-captures**', (route) => {
    const req = route.request(),
      p = new URL(req.url()).pathname.split('/api/experiments/bos-captures')[1];
    if (!p) return route.fulfill({ json: [] });
    if (p === '/cameras')
      return route.fulfill({ json: [{ id: 'bos-camera', name: 'Paired mock camera' }] });
    if (req.method() === 'PUT') {
      expect(req.postDataJSON()).toEqual({ cameraId: 'bos-camera' });
      view.id = p.slice(1);
      return route.fulfill({ json: view });
    }
    if (req.method() === 'POST') {
      const step = triggers.length ? 'disturbed' : 'reference';
      expect(p).toBe('/' + view.id + '/steps/' + step);
      expect(req.postDataJSON()).toEqual({ ready: true });
      view[step] = exposure(step === 'reference' ? png : disturbed);
      view.state = step === 'reference' ? 'WAITING_DISTURBED' : 'COMPLETED';
      triggers.push(step);
      return route.fulfill({ json: view });
    }
    if (p.startsWith('/' + view.id + '/images/'))
      return route.fulfill({ contentType: 'image/png', body: p.endsWith('/reference') ? png : disturbed });
    throw Error('Unexpected capture endpoint ' + p);
  });
  await page.getByRole('button', { name: 'Open paired Photographer capture' }).click();
  const capture = page.locator('#bos-camera-acquisition');
  await capture.getByLabel('Fresnel username').fill('mock-camera-operator');
  await capture.getByLabel('Password', { exact: true }).fill('mock');
  await capture.getByRole('button', { name: 'Sign in', exact: true }).click();
  await capture.getByRole('combobox', { name: 'Camera', exact: true }).selectOption('bos-camera');
  await capture.getByRole('button', { name: 'Start BOS capture' }).click();
  await expect(capture.getByRole('status')).toHaveText('Awaiting reference');
  await capture.getByRole('checkbox', { name: 'The undisturbed reference condition is ready.' }).check();
  await capture.getByRole('button', { name: 'Capture reference' }).click();
  await expect(capture.getByRole('status')).toHaveText('Awaiting disturbed condition');
  await capture.getByRole('checkbox', { name: 'The disturbed condition is ready.' }).check();
  await capture.getByRole('button', { name: 'Capture disturbed' }).click();
  await expect(capture.getByRole('status')).toHaveText('Completed');
  await capture.getByRole('button', { name: 'Use verified pair in BOS analysis' }).click();
  const pair = page.getByRole('region', { name: 'Still-pair displacement' });
  await pair.getByRole('button', { name: 'Analyze still pair' }).click();
  await expect(pair.getByRole('status')).toContainText('Registered');
  await pair.getByLabel('Amplification').fill('2');
  await pair.getByLabel('Map', { exact: true }).selectOption('vectors');
  await expect(pair.getByText(/Arrows show image-plane displacement/)).toBeVisible();
  await pair.getByLabel('Source view').selectOption('stabilized');
  await expect(pair.getByRole('img', { name: 'Disturbed image after registration' })).toBeVisible();
  const comparison = pair.getByLabel('Displacement map').locator('../..');
  await testInfo.attach('bos-camera-displacement-map', {
    body: await comparison.screenshot(),
    contentType: 'image/png',
  });
  const original = await numericGrid(page);
  expect(original.amplification).toBe(2);
  expect(original.vectors.some((v: { dx: number | null }) => v.dx !== null && v.dx > 1)).toBe(true);
  expect(original.targetParameters.patternSeed).toBe(1944);
  expect(original.sources.reference.sha256).toBe(exposure(png).sha256);
  expect(original.captureProvider).toBe('photographer-rest');
  expect(original.accepted).toBeGreaterThan(20);
  await pair.getByText('Saved experiments', { exact: true }).click();
  await pair.getByLabel('Fresnel username').fill(process.env.E2E_USER ?? 'user');
  await pair.getByLabel('Password', { exact: true }).fill(process.env.E2E_PASSWORD ?? 'user');
  await pair.getByRole('button', { name: 'Sign in to saved experiments' }).click();
  await pair.getByRole('button', { name: 'Save current captures' }).click();
  await expect(pair.getByText('Saved 3 files.')).toBeVisible();
  const bundle = page.waitForEvent('download');
  await pair.getByRole('button', { name: 'Download experiment bundle' }).click();
  const archive = await bundle;
  expect(archive.suggestedFilename()).toMatch(/\.fresnel-exp$/);
  await pair.getByLabel('Import experiment bundle').setInputFiles((await archive.path())!);
  await expect(pair.getByText('Opened 2 source images.')).toBeVisible();
  await pair.getByRole('button', { name: 'Analyze still pair' }).click();
  const restored = await numericGrid(page);
  expect(restored.configuredTarget).toEqual(original.configuredTarget);
  expect(restored.targetParameters).toEqual(original.targetParameters);
  expect(restored.captureOrigin).toEqual(original.captureOrigin);
  expect(restored.sources.disturbed.sha256).toEqual(exposure(disturbed).sha256);
  expect(restored.vectors).toEqual(original.vectors);
  for (const [label, data] of [
    ['Reference image', png],
    ['Disturbed image', disturbed],
  ] as const) {
    await pair
      .getByLabel(label, { exact: true })
      .setInputFiles({ name: 'manual.png', mimeType: 'image/png', buffer: data });
  }
  await pair.getByRole('button', { name: 'Analyze still pair' }).click();
  const manual = await numericGrid(page);
  expect(manual.captureProvider).toBe('manual');
  expect(manual.vectors).toEqual(original.vectors);
  expect(triggers).toEqual(['reference', 'disturbed']);
});
