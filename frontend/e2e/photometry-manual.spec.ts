import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import type { Manifest } from '../src/photometry/data';

test('manually maps a physical illumination sequence and exports measured relighting', async ({ page }) => {
  await page.goto('/plugins/display-photometric-stereo');
  const workflow = page.getByRole('region',{name:'Measurement workflow'});
  await expect(workflow).toHaveAttribute('data-state','DRAFT');
  await page.getByText('Physical setup and saved screens',{exact:true}).click();
  await page.getByLabel('Screen profile name').fill('Bench monitor');
  await page.getByRole('button',{name:'Save display profile'}).click();
  await page.getByLabel('Active screen width').fill('510');
  await page.getByLabel('Saved display profile',{exact:true}).selectOption('Bench monitor');
  await expect(page.getByLabel('Active screen width')).toHaveValue('530');
  await page.getByLabel('Camera placement',{exact:true}).selectOption('above');
  const response = page.waitForResponse(r => r.url().endsWith('/display-photometric-stereo/sequence'));
  await page.getByRole('button',{ name:'Prepare illumination sequence' }).click();
  await expect(workflow).toHaveAttribute('data-state','TARGET_READY');
  await workflow.getByRole('button',{name:'Collect sources'}).click();
  await expect(workflow).toHaveAttribute('data-state','READY_TO_CAPTURE');
  const manifest = await (await response).json() as Manifest;
  expect(manifest.patterns).toHaveLength(10);
  expect(manifest.conditionNumber).toBeLessThan(25);
  const picker = page.getByLabel('Pattern', { exact:true });
  for (const p of [...manifest.patterns].reverse()) {
    await picker.selectOption(p.id);
    const image = await page.evaluate(({ p, gamma }) => {
      const canvas = document.createElement('canvas');
      canvas.width = 24; canvas.height = 14;
      const ctx = canvas.getContext('2d')!;
      let linear = p.role === 'DARK' ? .02 : .8;
      if (p.direction) {
        const {x,y,z} = p.direction;
        linear = .02 + p.relativeIntensity*.5*(.3*x - .4*y + Math.sqrt(.75)*z);
      }
      const v = Math.round(255 * linear**(1/gamma));
      ctx.fillStyle = 'rgb(' + v + ',' + v + ',' + v + ')';
      ctx.fillRect(0,0,24,14);
      return canvas.toDataURL().split(',')[1];
    }, { p, gamma:manifest.geometry.cameraGamma });
    await page.getByLabel('Camera exposure for ' + p.id).setInputFiles({
      name:'camera.png',mimeType:'image/png',buffer:Buffer.from(image,'base64'),
    });
  }
  await expect(page.getByText('10 / 10 exposures mapped')).toBeVisible();
  await expect(workflow).toHaveAttribute('data-state','READY_TO_ANALYZE');
  await page.getByRole('button',{name:'Reconstruct surface'}).click();
  await expect(workflow).toHaveAttribute('data-state','COMPLETED');
  await expect(page.getByText('336 of 336 pixels passed the solve.',{exact:false})).toBeVisible();
  const canvas = page.locator('canvas[aria-label="Measured surface result"]');
  await expect(canvas).toBeVisible();
  await page.getByLabel('Result layer').selectOption('Camera exposure');
  await expect(page.getByAltText('Camera exposure for flat')).toBeVisible();
  await page.getByLabel('Result layer').selectOption('Relighting');
  const before = await canvas.evaluate((c: HTMLCanvasElement) => c.toDataURL());
  await page.getByLabel('Light horizontal').press('Home');
  await expect.poll(() => canvas.evaluate((c: HTMLCanvasElement) => c.toDataURL())).not.toBe(before);
  await page.getByRole('button',{name:'Integrate relative height'}).click();
  await expect(page.getByLabel('Relative height map')).toBeVisible();
  const imageDownload=page.waitForEvent('download');
  await page.getByRole('button',{name:'Download relighting PNG'}).click();
  const png=await readFile((await (await imageDownload).path())!);
  expect(png.includes(Buffer.from('Fresnel\0'))).toBe(true);
  expect(png.includes(Buffer.from(manifest.sequenceId))).toBe(true);
  const output = page.waitForEvent('download');
  await page.getByRole('button',{name:'Download result grid and provenance'}).click();
  const json = JSON.parse(await readFile((await (await output).path())!, 'utf8'));
  expect(json.grid.normals[0]).toBeCloseTo(.3, 1);
  expect(json.grid.normals[1]).toBeCloseTo(-.4, 1);
  expect(json.grid.albedo[0]).toBeCloseTo(.5, 1);
  expect(json.sources.map((s: {patternId:string})=>s.patternId)).toEqual(manifest.patterns.map(p=>p.id));
  expect(json.sources.every((s: {sha256:string}) => /^[a-f0-9]{64}$/.test(s.sha256))).toBeTruthy();
  expect(json.scale).toBe('Relative');
  expect(json.alignment.method).toBe('gradient-translation/1');
  expect(json.sources.filter((s: {transform:unknown})=>s.transform).every(
    (s: {transform:{status:string}}) => s.transform.status==='LOW_TEXTURE')).toBe(true);
  expect(json.warnings.join(' ')).toContain('lack enough texture');
  expect(json.relief.method).toBe('masked-poisson/1');
  expect(json.relief.components).toBe(1);
  expect(json.relief.curlRms).toBeLessThan(.00001);
  await page.getByText('Test exposures and object region',{exact:true}).click();
  await page.getByRole('button',{name:'Inspect test exposures'}).click();
  await expect(page.getByAltText('Upright flat camera exposure, drag to select the object')).toBeVisible();
  await expect(page.getByText(/336 object pixels/)).toBeVisible();
  await page.getByLabel('Region left').fill('25');
  await page.getByRole('button',{name:'Reconstruct surface'}).click();
  await expect(page.getByText('252 of 336 pixels passed the solve.',{exact:false})).toBeVisible();
  await page.getByText('Saved experiments', {exact:true}).click();
  await page.getByLabel('Fresnel username').fill(process.env.E2E_USER ?? 'user');
  await page.getByLabel('Password',{exact:true}).fill(process.env.E2E_PASSWORD ?? 'user');
  await page.getByRole('button',{name:'Sign in to saved experiments'}).click();
  await page.getByRole('button',{name:'Save current captures'}).click();
  await expect(page.getByText('Saved 11 files.')).toBeVisible();
  const bundleEvent=page.waitForEvent('download');
  await page.getByRole('button',{name:'Download experiment bundle'}).click();
  const bundle=await bundleEvent;
  await page.getByLabel('Import experiment bundle').setInputFiles((await bundle.path())!);
  await expect(page.getByText('Opened 10 source images.')).toBeVisible();
  await page.getByRole('button',{name:'Reconstruct surface'}).click();
  await expect(page.getByText('252 of 336 pixels passed the solve.',{exact:false})).toBeVisible();
});
