import { readFile } from 'node:fs/promises';
import { expect, test, type Download, type Locator, type Page } from '@playwright/test';

function jobInput(page: Page): Locator {
  return page.locator('input[type="file"][accept*=".fresnel"]');
}

async function clickDownload(page: Page, button: Locator): Promise<Download> {
  await expect(button).toBeEnabled({ timeout: 30_000 });
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    button.click(),
  ]);
  const stream = await download.createReadStream();
  let bytes = 0;
  for await (const chunk of stream) bytes += chunk.length;
  expect(bytes).toBeGreaterThan(64);
  return download;
}

test('BOS target generates reproducible evidence, downloads and job round trip', async ({ page }) => {
  await page.goto('/plugins/background-oriented-schlieren');

  await expect(page.getByRole('heading', { name: 'Background-Oriented Schlieren target' }))
    .toBeVisible();
  await expect(page.getByText('This is not a thermal camera'))
    .toBeVisible();

  const form = page.locator('[data-plugin-schema="background-oriented-schlieren"]');
  await expect(form).toBeVisible();
  await form.getByLabel('Target width (px)').fill('640');
  await form.getByLabel('Target height (px)').fill('480');
  await form.getByLabel('Intended print resolution (dpi)').fill('100');
  await form.getByLabel('Pattern seed').fill('123456');
  await form.getByLabel('Dot diameter (px)').fill('5');
  await form.getByLabel('Requested dot fill').fill('0.10');
  await form.getByLabel('Minimum dot spacing (px)').fill('2');
  await form.getByLabel('Quiet border (px)').fill('32');

  const generate = page.getByRole('button', { name: 'Generate target preview' });
  await expect(generate).toBeEnabled({ timeout: 30_000 });
  await generate.click();

  await expect(page.getByRole('img', {
    name: 'Background-Oriented Schlieren dot target',
  })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Show target full screen' }))
    .toBeEnabled();
  const manifest = page.getByRole('heading', { name: 'Reproducible target manifest' })
    .locator('..');
  await expect(manifest).toContainText(/bos-[0-9a-f]{12}/);
  await expect(manifest).toContainText('background-oriented-schlieren-target/1');
  await expect(manifest).toContainText(/Semantic SHA-256/);
  await expect(manifest).toContainText(/640 × 480 px/);
  await expect(manifest).toContainText(/576 × 416 px/);

  const targetDownload = await clickDownload(
    page,
    page.getByRole('button', { name: 'Download target PNG' }),
  );
  expect(targetDownload.suggestedFilename()).toMatch(/^bos-[0-9a-f]{12}\.png$/);

  const manifestDownload = await clickDownload(
    page,
    page.getByRole('button', { name: 'Download target manifest' }),
  );
  expect(manifestDownload.suggestedFilename())
    .toMatch(/^bos-[0-9a-f]{12}-manifest\.json$/);

  const saved = await clickDownload(
    page,
    page.getByRole('button', { name: 'Save job (.fresnel)' }),
  );
  const savedPath = await saved.path();
  expect(savedPath).not.toBeNull();
  const savedJob = JSON.parse(await readFile(savedPath!, 'utf8')) as {
    plugin: { id: string; parameterSchemaVersion: number; algorithmVersion: string };
    parameters: { patternSeed: number };
  };
  expect(savedJob.plugin).toEqual({
    id: 'background-oriented-schlieren',
    parameterSchemaVersion: 1,
    algorithmVersion: 'background-oriented-schlieren/1',
  });
  expect(savedJob.parameters.patternSeed).toBe(123456);

  await form.getByLabel('Pattern seed').fill('654321');
  await jobInput(page).setInputFiles(savedPath!);
  await expect(page).toHaveURL(/\/plugins\/background-oriented-schlieren$/);
  await expect(form.getByLabel('Pattern seed')).toHaveValue('123456');
});
