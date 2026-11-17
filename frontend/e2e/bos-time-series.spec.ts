import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

test('samples a finite MP4, exports quantitative times and scrubs its median-referenced maps', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const panel = page.getByRole('region', { name: 'BOS time series' });
  await panel.getByLabel('Source type', { exact: true }).selectOption('video');
  await panel
    .getByLabel('MP4 clip', { exact: true })
    .setInputFiles(fileURLToPath(new URL('./fixtures/bos-eight-frames.mp4', import.meta.url)));
  await panel.getByLabel('Reference policy', { exact: true }).selectOption('median');
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByRole('status')).toHaveText('4 of 4 frames registered.');
  await panel.getByLabel('Timeline', { exact: true }).fill('3');
  await expect(panel.getByText('Frame 4 · 1.750 s · frame-04.png')).toBeVisible();
  const jsonDownload = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Download time-series grid' }).click();
  const result = JSON.parse(await readFile((await (await jsonDownload).path())!, 'utf8'));
  expect(result.referenceFrameIndices).toEqual([0, 2, 3]);
  expect(result.sourceVideo.sha256).toMatch(/^[a-f0-9]{64}$/);
  expect(result.frames.map((f: { timeSeconds: number }) => f.timeSeconds)).toEqual([
    0.25, 0.75, 1.25, 1.75,
  ]);
  expect(result.frames[3].result.accepted).toBeGreaterThan(15);
  const review = page.waitForEvent('download');
  await panel.getByRole('button', { name: /Download review WebM/ }).click();
  const webm = await review;
  expect(webm.suggestedFilename()).toBe('bos-review.webm');
  const bytes = await readFile((await webm.path())!);
  expect(bytes.subarray(0, 4).toString('hex')).toBe('1a45dfa3');
});

test('ordered stills preserve the declared time base and cancel cleanly', async ({ page }) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const panel = page.getByRole('region', { name: 'BOS time series' });
  const source = await page.evaluate(() => {
    const c = document.createElement('canvas');
    c.width = 180;
    c.height = 128;
    const ctx = c.getContext('2d')!,
      image = ctx.createImageData(c.width, c.height);
    let seed = 928;
    for (let i = 0; i < image.data.length; i += 4) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      image.data[i] = image.data[i + 1] = image.data[i + 2] = seed >>> 24;
      image.data[i + 3] = 255;
    }
    ctx.putImageData(image, 0, 0);
    return c.toDataURL().split(',')[1];
  });
  const inputs = ['a', 'b', 'c'].map((name) => ({
    name: name + '.png',
    mimeType: 'image/png',
    buffer: Buffer.from(source, 'base64'),
  }));
  await panel.getByLabel('Ordered frames', { exact: true }).setInputFiles(inputs);
  await panel.getByRole('button', { name: 'Move earlier' }).first().click();
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByRole('status')).toHaveText('3 of 3 frames registered.');
  await expect(panel.getByText('Frame 1 · 0.000 s · b.png')).toBeVisible();
  await panel.getByLabel('Timeline', { exact: true }).fill('2');
  await expect(panel.getByText('Frame 3 · 1.000 s · c.png')).toBeVisible();
  await panel.getByRole('button', { name: /Download review WebM/ }).click();
  await panel.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(panel.getByText('Encoding review…')).toHaveCount(0);
});
