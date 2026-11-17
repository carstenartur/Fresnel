import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

test('analyzes two local images, re-renders a stored grid and exports it', async ({ page }) => {
  await page.goto('/plugins/background-oriented-schlieren');
  const panel = page.getByRole('region', { name: 'Still-pair displacement' });
  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 160;
    canvas.height = 128;
    const ctx = canvas.getContext('2d')!,
      image = ctx.createImageData(160, 128);
    let seed = 23;
    for (let i = 0; i < image.data.length; i += 4) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      image.data[i] = image.data[i + 1] = image.data[i + 2] = seed >>> 24;
      image.data[i + 3] = 255;
    }
    ctx.putImageData(image, 0, 0);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const file = { name: 'dots.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') };
  await panel.getByLabel('Reference image', { exact: true }).setInputFiles(file);
  const quality = panel.getByRole('region', { name: 'Reference image quality' });
  await expect(quality).toContainText('Luminance contrast');
  await quality.getByText('Adjust analysis region').click();
  await quality.getByLabel('Left (%)').fill('5');
  await quality.getByLabel('Top (%)').fill('5');
  await quality.getByLabel('Width (%)').fill('90');
  await quality.getByLabel('Height (%)').fill('90');
  await quality.getByRole('button', { name: 'Apply region' }).click();
  await expect(quality).toContainText('Using the manual region');
  await panel.getByLabel('Disturbed image', { exact: true }).setInputFiles(file);
  await panel.getByRole('button', { name: 'Analyze still pair' }).click();
  await expect(panel.getByRole('status')).toContainText('Registered 12 of 12');
  await panel.getByLabel('Map', { exact: true }).selectOption('horizontal');
  await panel.getByLabel('Amplification').fill('2');
  await expect(panel.getByText(/Blue is negative/)).toBeVisible();
  const download = page.waitForEvent('download');
  await panel.getByRole('link', { name: 'Download numeric grid' }).click();
  const result = JSON.parse(await readFile((await (await download).path())!, 'utf8'));
  expect(result.sources.reference.sourceRegion).toEqual({ x: 8, y: 6, width: 144, height: 116 });
  expect(result.referenceQuality.selection).toBe('manual');
  expect(result.referenceQuality.referenceSha256).toBe(result.sources.reference.sha256);
  expect(result.width).toBe(144);
  expect(result.height).toBe(116);
  expect(result.sources.reference.sha256).toMatch(/^[0-9a-f]{64}$/);
  expect(result.sources.disturbed.sha256).toBe(result.sources.reference.sha256);
  expect(result.visualization).toBe('horizontal');
  expect(result.amplification).toBe(2);
  expect(result.vectors.length).toBe(result.accepted);
  expect(
    Math.max(...result.vectors.map((v: { dx: number; dy: number }) => Math.hypot(v.dx, v.dy))),
  ).toBeLessThan(0.04);
  const mapDownload = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Download map PNG' }).click();
  expect((await mapDownload).suggestedFilename()).toBe('bos-horizontal.png');

  await panel
    .getByLabel('Disturbed image', { exact: true })
    .setInputFiles({ name: 'invalid.png', mimeType: 'image/png', buffer: Buffer.from('invalid image') });
  await panel.getByRole('button', { name: 'Analyze still pair' }).click();
  await expect(panel.getByRole('alert')).toContainText('valid PNG');
  await expect(panel.getByRole('link', { name: 'Download numeric grid' })).toHaveCount(0);
});
