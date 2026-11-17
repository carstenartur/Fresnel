import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

test.skip(!process.env.FRESNEL_BOS_NETWORK_IT, 'Run with the paired Photographer network fixture.');

test('captures a reference and three ordered disturbed stills then reopens the verified time series', async ({
  page,
}) => {
  await page.goto('/plugins/background-oriented-schlieren');
  await page.getByRole('button', { name: 'Open paired Photographer capture' }).click();
  const acquisition = page.locator('#bos-camera-acquisition');
  const user = process.env.E2E_USER ?? 'user',
    password = process.env.E2E_PASSWORD ?? 'user';
  await acquisition.getByLabel('Fresnel username').fill(user);
  await acquisition.getByLabel('Password', { exact: true }).fill(password);
  await acquisition.getByRole('button', { name: 'Sign in', exact: true }).click();
  const count = acquisition.getByLabel('Disturbed frames');
  await expect(count).toHaveAttribute('max', '7');
  await count.fill('8');
  await expect(acquisition.getByRole('button', { name: 'Start BOS capture' })).toBeDisabled();
  await count.fill('3');
  await acquisition.getByRole('button', { name: 'Start BOS capture' }).click();
  for (const step of ['reference', 'frame-01', 'frame-02', 'frame-03']) {
    const fire = acquisition.getByRole('button', { name: 'Capture ' + step, exact: true });
    await expect(fire).toBeDisabled();
    await acquisition.getByRole('checkbox').check();
    await fire.click();
  }
  await acquisition.getByRole('button', { name: 'Use verified sequence in BOS analysis' }).click();
  const panel = page.getByRole('region', { name: 'BOS time series' });
  await expect(panel.getByText(/3 verified disturbed exposures/)).toBeVisible();
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByText('3 of 3 frames registered.', { exact: true })).toBeVisible();
  async function grid() {
    const download = page.waitForEvent('download');
    await panel.getByRole('button', { name: 'Download time-series grid' }).click();
    return JSON.parse(await readFile((await (await download).path())!, 'utf8'));
  }
  const result = await grid();
  expect(result.captureProvider).toBe('photographer-rest');
  expect(result.frames.map((f: { timeSeconds: number }) => f.timeSeconds)).toEqual([0, 1.25, 3.5]);
  expect(result.reference.sha256).toBe(result.captureOrigin.reference.sha256);
  expect(result.frames.map((f: { sha256: string }) => f.sha256)).toEqual(
    result.captureOrigin.frames.map((f: { sha256: string }) => f.sha256),
  );
  await panel.getByText('Saved experiments', { exact: true }).click();
  await panel.getByLabel('Fresnel username').fill(user);
  await panel.getByLabel('Password', { exact: true }).fill(password);
  await panel.getByRole('button', { name: 'Sign in to saved experiments' }).click();
  await panel.getByRole('button', { name: 'Save current captures' }).click();
  await expect(panel.getByText('Saved 5 files.')).toBeVisible();
  await page.reload();
  await page.getByText('Analyze a sequence or video', { exact: true }).click();
  await panel.getByText('Saved experiments', { exact: true }).click();
  await panel.getByLabel('Fresnel username').fill(user);
  await panel.getByLabel('Password', { exact: true }).fill(password);
  await panel.getByRole('button', { name: 'Sign in to saved experiments' }).click();
  await panel.getByRole('button', { name: 'Open saved captures' }).click();
  await expect(panel.getByText(/3 verified disturbed exposures/)).toBeVisible();
  await panel.getByRole('button', { name: 'Analyze time series' }).click();
  await expect(panel.getByText('3 of 3 frames registered.', { exact: true })).toBeVisible();
  const reopened = await grid();
  expect(reopened.captureOrigin).toEqual(result.captureOrigin);
  expect(reopened.frames).toEqual(result.frames);
});
