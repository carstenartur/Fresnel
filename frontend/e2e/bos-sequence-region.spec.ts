import { expect, test, type Locator, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

async function selectRegion(panel: Locator, left: string, top: string, width: string, height: string) {
  await panel.getByText('Adjust analysis region', { exact: true }).click();
  for (const [name, value] of [
    ['Left (%)', left],
    ['Top (%)', top],
    ['Width (%)', width],
    ['Height (%)', height],
  ]) {
    await panel.getByLabel(name, { exact: true }).fill(value);
  }
  await panel.getByRole('button', { name: 'Apply region' }).click();
}
async function analyze(page: Page, panel: Locator) {
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  const download = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Download time-series grid' }).click();
  return JSON.parse(await readFile((await (await download).path())!, 'utf8'));
}

test('a sequence explicitly adopts a manual still-pair crop before native-resolution resizing', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  const still = page.getByRole('region', { name: 'Still-pair displacement' });
  const data = await page.evaluate(() => {
    const c = document.createElement('canvas');
    c.width = 1000;
    c.height = 800;
    const ctx = c.getContext('2d')!,
      image = ctx.createImageData(c.width, c.height);
    let seed = 932825;
    for (let i = 0; i < image.data.length; i += 4) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      image.data[i] = image.data[i + 1] = image.data[i + 2] = seed >>> 24;
      image.data[i + 3] = 255;
    }
    ctx.putImageData(image, 0, 0);
    return c.toDataURL().split(',')[1];
  });
  const source = { name: 'reference.png', mimeType: 'image/png', buffer: Buffer.from(data, 'base64') };
  await still.getByLabel('Reference image', { exact: true }).setInputFiles(source);
  const inspector = still.getByRole('region', { name: 'Reference image quality' });
  await selectRegion(inspector, '25', '20', '40', '35');
  await expect(inspector).toContainText('Using the manual region.');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const seq = page.getByRole('region', { name: 'BOS time series' });
  await seq
    .getByLabel('Ordered frames', { exact: true })
    .setInputFiles([source, { ...source, name: 'frame2.png' }]);
  await expect(seq.getByText(/Sequence region starts/)).toHaveCount(0);
  await seq.getByRole('button', { name: 'Use the manually selected still-pair region' }).click();
  const report = await analyze(page, seq);
  expect(report.analysisRegion).toEqual({ x: 0.25, y: 0.2, width: 0.4, height: 0.35 });
  expect(report.referenceRaster).toMatchObject({ width: 400, height: 280 });
  for (const frame of report.frames) {
    expect(frame).toMatchObject({
      sourceWidth: 1000,
      sourceHeight: 800,
      sourceRegion: { x: 250, y: 160, width: 400, height: 280 },
      sourcePixelsPerAnalysisPixel: { x: 1, y: 1 },
    });
    expect(frame.result).toMatchObject({ width: 400, height: 280 });
    expect(frame.result.accepted).toBe(frame.result.vectors.length);
  }
});

test('the same native-source crop is used for every video median sample and measured frame', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const seq = page.getByRole('region', { name: 'BOS time series' });
  await seq.getByLabel('Source type', { exact: true }).selectOption('video');
  await seq
    .getByLabel('MP4 clip', { exact: true })
    .setInputFiles(fileURLToPath(new URL('./fixtures/bos-eight-frames.mp4', import.meta.url)));
  await seq.getByLabel('Reference policy', { exact: true }).selectOption('median');
  await selectRegion(seq, '10', '10', '80', '80');
  const report = await analyze(page, seq);
  expect(report.referenceRaster).toMatchObject({ width: 116, height: 102 });
  expect(report.frames).toHaveLength(4);
  for (const frame of report.frames) {
    expect(frame).toMatchObject({
      sourceWidth: 144,
      sourceHeight: 128,
      sourceRegion: { x: 14, y: 13, width: 116, height: 102 },
      sourcePixelsPerAnalysisPixel: { x: 1, y: 1 },
    });
    expect(frame.result).toMatchObject({ width: 116, height: 102 });
  }
});
