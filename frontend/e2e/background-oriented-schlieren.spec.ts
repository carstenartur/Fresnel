import { expect, test } from '@playwright/test';

const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];

test('generates a reproducible BOS target from the graphical editor', async ({ page }) => {
  await page.goto('/');

  await page.getByRole('button', { name: /Background-Oriented Schlieren/i }).click();
  await expect(page.getByRole('heading', {
    name: 'Background-oriented schlieren target',
  })).toBeVisible();

  const seed = page.getByLabel('Pattern seed');
  await seed.fill('424242');
  await page.getByRole('button', { name: 'Render target preview' }).click();

  const preview = page.getByAltText(
    'Seeded background-oriented schlieren random-dot target',
  );
  await expect(preview).toBeVisible();

  const firstSrc = await preview.getAttribute('src');
  await seed.fill('424243');
  await page.getByRole('button', { name: 'Render target preview' }).click();
  await expect.poll(async () => preview.getAttribute('src')).not.toBe(firstSrc);

  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export target PNG' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe(
    'fresnel-background-oriented-schlieren.png',
  );
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  expect([...Buffer.concat(chunks).subarray(0, 8)]).toEqual(PNG_SIGNATURE);
});
