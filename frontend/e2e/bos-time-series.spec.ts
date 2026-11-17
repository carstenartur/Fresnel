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
  expect(result.frames.map((f: { timeSeconds: number }) => f.timeSeconds)).toEqual([0.25, 0.75, 1.25, 1.75]);
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

test('fits camera rotation both when preparing the temporal median and when measuring each frame', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const panel = page.getByRole('region', { name: 'BOS time series' });
  const frames = await page.evaluate(() => {
    const texture = document.createElement('canvas');
    texture.width = texture.height = 160;
    const original = texture.getContext('2d')!;
    const image = original.createImageData(160, 160);
    const noise = new Uint8Array(160 * 160);
    let seed = 2352;
    for (let i = 0; i < noise.length; i++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      noise[i] = seed >>> 24;
    }
    for (let y = 1; y < 159; y++)
      for (let x = 1; x < 159; x++) {
        const i = y * 160 + x;
        const value = (4 * noise[i] + noise[i - 1] + noise[i + 1] + noise[i - 160] + noise[i + 160]) / 8;
        image.data[4 * i] = image.data[4 * i + 1] = image.data[4 * i + 2] = value;
        image.data[4 * i + 3] = 255;
      }
    original.putImageData(image, 0, 0);
    return [0, 0.037, -0.032].map((angle) => {
      const c = document.createElement('canvas');
      c.width = c.height = 160;
      const ctx = c.getContext('2d')!;
      ctx.translate(80, 80);
      ctx.rotate(angle);
      ctx.drawImage(texture, -80, -80);
      return c.toDataURL().split(',')[1];
    });
  });
  await panel.getByLabel('Ordered frames', { exact: true }).setInputFiles(
    frames.map((bytes, i) => ({
      name: 'rotation-' + i + '.png',
      mimeType: 'image/png',
      buffer: Buffer.from(bytes, 'base64'),
    })),
  );
  await panel.getByLabel('Reference policy').selectOption('median');
  await panel.getByLabel('Camera motion model').selectOption('affine');
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByRole('status')).toHaveText('3 of 3 frames registered.');
  const download = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Download time-series grid' }).click();
  const report = JSON.parse(await readFile((await (await download).path())!, 'utf8'));
  expect(report.method).toBe('sequence-affine-pyramid-zncc/2');
  for (const frame of report.frames) {
    expect(frame.result.registration.model).toBe('affine');
    expect(frame.result.accepted / frame.result.vectors.length).toBeGreaterThan(0.85);
    expect(frame.result.autoLimitPx).toBeLessThan(0.4);
  }
});
