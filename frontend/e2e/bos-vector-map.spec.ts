import { paintMap } from '../src/measurement/bosMap';
import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

test('draws stored vector direction and scales the timeline map without recomputing its grid', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  const panel = page.getByRole('region', { name: 'BOS time series' });
  await panel.getByLabel('Source type', { exact: true }).selectOption('video');
  await panel
    .getByLabel('MP4 clip', { exact: true })
    .setInputFiles(fileURLToPath(new URL('./fixtures/bos-eight-frames.mp4', import.meta.url)));
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByText('4 of 4 frames registered.', { exact: true })).toBeVisible();
  await panel.getByLabel('Timeline', { exact: true }).fill('3');
  const canvas = panel.getByLabel('Selected frame map');
  const pixels = () => canvas.evaluate((e) => (e as HTMLCanvasElement).toDataURL());
  const magnitude = await pixels();
  const grid = async () => {
    const event = page.waitForEvent('download');
    await panel.getByRole('button', { name: 'Download time-series grid' }).click();
    return JSON.parse(await readFile((await (await event).path())!, 'utf8'));
  };
  const before = await grid();
  await panel.getByLabel('Time-series map', { exact: true }).selectOption('vectors');
  await panel.getByLabel('Time-series amplification').fill('3');
  await expect(panel.getByText(/Arrows show image-plane displacement/)).toBeVisible();
  expect(await pixels()).not.toBe(magnitude);
  const after = await grid();
  expect(after.frames).toEqual(before.frames);
  expect(after.visualization).toBe('vectors');
  expect(after.amplification).toBe(3);
  expect(after.displayedLimitPx).toBeCloseTo(after.colorLimitPx / 3, 8);
});

test('paints the known image-plane vector and skips rejected or unsampled windows', () => {
  const points: number[][] = [];
  const context = {
    fillRect() {},
    beginPath() {},
    stroke() {},
    moveTo(x: number, y: number) {
      points.push([x, y]);
    },
    lineTo(x: number, y: number) {
      points.push([x, y]);
    },
  };
  const canvas = { width: 0, height: 0, getContext: () => context } as unknown as HTMLCanvasElement;
  const field = {
    width: 80,
    height: 40,
    autoLimitPx: 10,
    grid: { x0: 20, y0: 20, step: 20, cols: 3, rows: 1 },
    vectors: [
      { x: 20, y: 20, dx: -3, dy: 4, correlation: 1 },
      { x: 40, y: 20, dx: 5, dy: 2, correlation: 1 },
      { x: 60, y: 20, dx: null, dy: null, correlation: 0 },
    ],
  };
  paintMap(canvas, field, 'vectors', 1);
  expect(points.slice(0, 2)).toEqual([
    [20, 20],
    [14, 28],
  ]);
  expect(points).toHaveLength(5);
});
