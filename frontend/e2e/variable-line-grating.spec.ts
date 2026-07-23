import { expect, test } from '@playwright/test';

const ORIENTATIONS = [
  {
    value: 'VERTICAL',
    label: 'Vertical lines — test page X',
    otherLabel: 'Horizontal lines — test page Y',
    testedDeviceAxis: 'X',
    filenamePart: 'vertical',
  },
  {
    value: 'HORIZONTAL',
    label: 'Horizontal lines — test page Y',
    otherLabel: 'Vertical lines — test page X',
    testedDeviceAxis: 'Y',
    filenamePart: 'horizontal',
  },
] as const;

for (const orientation of ORIENTATIONS) {
  test(`${orientation.value.toLowerCase()} grating is an independent preview, job and PCL flow`, async ({ page }) => {
    const schemaResponsePromise = page.waitForResponse((response) =>
      response.url().endsWith('/api/plugins/variable-line-grating/schema'));

    await page.goto('/plugins/variable-line-grating');
    expect((await schemaResponsePromise).ok()).toBeTruthy();
    await expect(page).toHaveURL(/\/plugins\/variable-line-grating$/);
    await expect(page.getByRole('tab', { name: 'Line grating' }))
      .toHaveAttribute('aria-selected', 'true');

    const form = page.locator('[data-plugin-schema="variable-line-grating"]');
    await expect(form).toBeVisible();
    await expect(form.locator('[data-schema-group="sheet"]')).toBeVisible();
    await expect(form.locator('[data-schema-group="progression"]')).toBeVisible();

    await form.getByLabel(orientation.label).check();
    await expect(form.getByLabel(orientation.label)).toBeChecked();
    await expect(form.getByLabel(orientation.otherLabel)).not.toBeChecked();
    await form.getByRole('spinbutton', { name: /^Sheet width/ }).fill('20');
    await form.getByRole('spinbutton', { name: /^Sheet height/ }).fill('20');
    await form.getByRole('spinbutton', { name: /^Start pitch/ }).fill('500');
    await form.getByRole('spinbutton', { name: /^End pitch/ }).fill('80');
    await form.getByRole('spinbutton', { name: /^Outer margin/ }).fill('2');
    await form.getByRole('spinbutton', { name: /^Axis annotation area/ }).fill('4');
    await form.getByRole('spinbutton', { name: /^Reference-band size/ }).fill('2');
    await form.getByRole('spinbutton', { name: /^Preview\/export DPI/ }).fill('150');

    const actionBar = page.locator('[data-plugin-action-bar="true"]');
    await expect(actionBar.getByRole('button', { name: 'PNG' })).toBeVisible();
    await expect(actionBar.getByRole('button', { name: 'SVG' })).toBeVisible();
    await expect(actionBar.getByRole('button', { name: 'PDF A4' })).toBeVisible();
    const pcl = actionBar.getByRole('button', { name: 'PCL 1-bit' });
    await expect(pcl).toBeVisible();

    const render = actionBar.getByRole('button', { name: 'Render preview' });
    await expect(render).toBeEnabled({ timeout: 30_000 });
    await render.click();
    await expect(page.getByRole('img', { name: 'Variable-line grating preview' }))
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(new RegExp(`tested device axis ${orientation.testedDeviceAxis}`)))
      .toBeVisible();

    const save = page.getByRole('button', { name: 'Save job (.fresnel)' });
    await expect(save).toBeEnabled({ timeout: 30_000 });
    const [jobDownload] = await Promise.all([
      page.waitForEvent('download'),
      save.click(),
    ]);
    const jobStream = await jobDownload.createReadStream();
    let json = '';
    for await (const chunk of jobStream) json += chunk.toString();
    const job = JSON.parse(json);
    expect(job.plugin.id).toBe('variable-line-grating');
    expect(job.parameters.lineOrientation).toBe(orientation.value);
    expect(job.parameters.widthMm).toBe(20);
    expect(job.parameters.heightMm).toBe(20);
    expect(job.parameters.startPitchUm).toBe(500);
    expect(job.parameters.endPitchUm).toBe(80);

    await expect(pcl).toBeEnabled({ timeout: 30_000 });
    const [pclDownload] = await Promise.all([
      page.waitForEvent('download'),
      pcl.click(),
    ]);
    expect(pclDownload.suggestedFilename())
      .toBe(`fresnel-grating-${orientation.filenamePart}.pcl`);
    const pclStream = await pclDownload.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of pclStream) chunks.push(Buffer.from(chunk));
    const bytes = Buffer.concat(chunks);
    expect(bytes.length).toBeGreaterThan(100);
    expect(bytes.subarray(0, 2)).toEqual(Buffer.from([0x1b, 0x45]));
  });
}
