import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

test('reuses a lost create request and captures only after each physical condition is acknowledged', async ({ page }) => {
  const root = '/api/experiments/bos-captures';
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO++EJkAAAAASUVORK5CYII=', 'base64');
  const source = { byteLength: png.length, sha256: createHash('sha256').update(png).digest('hex'),
    capturedAt: '2026-11-17T06:00:00Z' };
  const view = { id: '', state: 'WAITING_REFERENCE', pendingStep: null as string | null, reference: null as typeof source | null,
    disturbed: null as typeof source | null };
  let attempts = 0;
  const triggers: string[] = [];
  await page.route('**/api/capture-providers', route => route.fulfill({ json: {
    state: 'CONNECTED', checkedAt: source.capturedAt, providers: [
      { id: 'photographer-rest', displayName: 'Photographer', state: 'CONNECTED', devices: [] },
    ],
  } }));
  await page.route('**/api/experiments/bos-captures**', async route => {
    const req = route.request();
    const path = new URL(req.url()).pathname.slice(root.length);
    expect(req.headers().authorization).toBe('Basic ' + Buffer.from('bos-operator:operator-password').toString('base64'));
    if (!path) return route.fulfill({ json: attempts > 1 ? [view] : [] });
    if (path === '/cameras') return route.fulfill({ json: [
      { id: 'other-camera', name: 'Other camera' }, { id: 'lab-camera', name: 'Lab camera' },
    ] });
    if (req.method() === 'PUT') {
      expect(req.postDataJSON()).toEqual({ cameraId: 'lab-camera' });
      if (++attempts === 1) {
        view.id = path.slice(1);
        return route.abort('failed');
      }
      expect(path).toBe('/' + view.id);
      return route.fulfill({ json: view });
    }
    if (path.startsWith('/' + view.id + '/steps/')) {
      const role = path.split('/').at(-1);
      if (role === 'reference' && view.pendingStep === null) {
        expect(req.postDataJSON()).toEqual({ ready: true });
        view.pendingStep = role;
        return route.abort('failed');
      }
      expect(req.postDataJSON()).toEqual(view.pendingStep === role ? { ready: true, continuous: true } : { ready: true });
      view.pendingStep = null;
      expect(role).toBe(triggers.length ? 'disturbed' : 'reference');
      triggers.push(role!);
      if (role === 'reference') { view.reference = source; view.state = 'WAITING_DISTURBED'; }
      else { view.disturbed = source; view.state = 'COMPLETED'; }
      return route.fulfill({ json: view });
    }
    if (path.startsWith('/' + view.id + '/images/')) return route.fulfill({ contentType: 'image/png', body: png });
    expect(path).toBe('/' + view.id);
    return route.fulfill({ json: view });
  });
  async function signIn() {
    await page.getByText('Background-oriented schlieren capture', { exact: true }).click();
    await page.getByLabel('Fresnel username').fill('bos-operator');
    await page.getByLabel('Password', { exact: true }).fill('operator-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
  }
  await page.goto('/');
  await signIn();
  await page.getByRole('combobox', { name: 'Camera', exact: true }).selectOption('lab-camera');
  await page.getByRole('button', { name: 'Start BOS capture' }).click();
  await expect(page.getByRole('button', { name: 'Retry BOS capture creation' })).toBeVisible();
  const pending = await page.evaluate(() => sessionStorage.getItem('fresnel.bos.pending.bos-operator'));
  expect(JSON.parse(pending!)).toEqual({ id: view.id, cameraId: 'lab-camera' });
  await page.reload();
  await signIn();
  await page.getByRole('button', { name: 'Retry BOS capture creation' }).click();
  for (const [role, acknowledgement] of [
    ['reference', 'The undisturbed reference condition is ready.'],
    ['disturbed', 'The disturbed condition is ready.'],
  ]) {
    const trigger = page.getByRole('button', { name: 'Capture ' + role, exact: true });
    await expect(trigger).toBeDisabled();
    await page.getByRole('checkbox', { name: acknowledgement }).check();
    await trigger.click();
    if (role === 'reference') {
      const continuity = page.getByRole('checkbox', { name: 'The original reference condition has remained continuously in place.' });
      await expect(continuity).toBeVisible();
      await expect(trigger).toBeDisabled();
      await continuity.check();
      await trigger.click();
    }
    const link = page.getByRole('link', { name: 'Download ' + role + ' PNG' });
    const [download] = await Promise.all([page.waitForEvent('download'), link.click()]);
    expect(download.suggestedFilename()).toBe('bos-' + view.id + '-' + role + '.png');
    const downloaded = await readFile((await download.path())!);
    expect(createHash('sha256').update(downloaded).digest('hex')).toBe(source.sha256);
  }
  await expect(page.getByRole('status').filter({ hasText: /^Completed$/ })).toBeVisible();
  expect(triggers).toEqual(['reference', 'disturbed']);
  expect(attempts).toBe(2);
});
