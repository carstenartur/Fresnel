import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { analyzePair, type GrayFrame, type PairResult } from '../src/measurement/bosDisplacement';

const side = 224,
  center = (side - 1) / 2;
type Field = (x: number, y: number) => [number, number];

function frames(field: Field) {
  const noise = new Uint8Array(side * side);
  let seed = 8490232;
  for (let i = 0; i < noise.length; i++) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    noise[i] = seed >>> 24;
  }
  const pixels = noise.slice();
  for (let y = 1; y < side - 1; y++)
    for (let x = 1; x < side - 1; x++) {
      const i = y * side + x;
      pixels[i] = (4 * noise[i] + noise[i - 1] + noise[i + 1] + noise[i - side] + noise[i + side]) / 8;
    }
  const reference: GrayFrame = { width: side, height: side, pixels };
  const disturbed: GrayFrame = { ...reference, pixels: new Uint8Array(pixels.length) };
  for (let y = 0; y < side; y++)
    for (let x = 0; x < side; x++) {
      let sx = x,
        sy = y;
      for (let k = 0; k < 10; k++) {
        const [dx, dy] = field(sx, sy);
        sx = x - dx;
        sy = y - dy;
      }
      sx = Math.max(0, Math.min(side - 1.001, sx));
      sy = Math.max(0, Math.min(side - 1.001, sy));
      const ix = Math.floor(sx),
        iy = Math.floor(sy),
        fx = sx - ix,
        fy = sy - iy,
        i = iy * side + ix;
      disturbed.pixels[y * side + x] = Math.round(
        (1 - fy) * ((1 - fx) * pixels[i] + fx * pixels[i + 1]) +
          fy * ((1 - fx) * pixels[i + side] + fx * pixels[i + side + 1]),
      );
    }
  return { reference, disturbed };
}

function largeShift(x: number, y: number): [number, number] {
  const r = Math.hypot(x - center, y - center);
  const t = Math.max(0, Math.min(1, (r - 19) / 39));
  const profile = 1 - t * t * (3 - 2 * t);
  return [7.25 * profile, -5.75 * profile];
}
function opposed(x: number, y: number): [number, number] {
  let dx = 0,
    dy = 0;
  for (const direction of [-1, 1]) {
    const rx = x - center - direction * 24,
      ry = y - center;
    const strength = (direction * 3.4 * Math.exp(-(rx * rx + ry * ry) / (2 * 14 ** 2))) / 14;
    dx -= ry * strength;
    dy += rx * strength;
  }
  return [dx, dy];
}
function evaluate(result: PairResult, field: Field) {
  const valid = result.vectors.filter((v) => v.dx !== null && v.dy !== null);
  const errors = valid
    .map((v) => {
      const [dx, dy] = field(v.x, v.y);
      return Math.hypot(v.dx! - dx, v.dy! - dy);
    })
    .sort((a, b) => a - b);
  return {
    valid,
    fraction: valid.length / result.vectors.length,
    p95: errors[Math.floor(0.95 * (errors.length - 1))],
  };
}

test('a coarse estimate recovers a signed 7.25 by -5.75 px plateau beyond the legacy search', () => {
  const { reference, disturbed } = frames(largeShift);
  const result = analyzePair(reference, disturbed, 'translation', 'pyramid');
  const quality = evaluate(result, largeShift);
  expect(quality.fraction).toBeGreaterThan(0.85);
  const inner = quality.valid.filter((v) => Math.hypot(v.x - center, v.y - center) < 17);
  expect(inner.length).toBeGreaterThanOrEqual(4);
  for (const v of inner) expect(Math.hypot(v.dx! - 7.25, v.dy! + 5.75)).toBeLessThan(0.45);
  const legacy = analyzePair(reference, disturbed, 'translation', 'single-scale');
  expect(
    legacy.vectors.filter((v) => Math.hypot(v.x - center, v.y - center) < 17).every((v) => v.dx === null),
  ).toBe(true);
});

test('an over-limit disturbance is masked in the central plateau', () => {
  const { reference, disturbed } = frames((x, y) => {
    const [dx, dy] = largeShift(x, y);
    return [1.9 * dx, 1.9 * dy];
  });
  const result = analyzePair(reference, disturbed);
  const central = result.vectors.filter((v) => Math.hypot(v.x - center, v.y - center) < 17);
  expect(central.length).toBeGreaterThanOrEqual(4);
  expect(central.every((v) => v.dx === null && v.dy === null)).toBe(true);
});

test('opposing rotational image fields preserve their signed vectors and reject false motion at rest', () => {
  const { reference, disturbed } = frames(opposed);
  const quality = evaluate(analyzePair(reference, disturbed), opposed);
  expect(quality.fraction).toBeGreaterThan(0.9);
  expect(quality.p95).toBeLessThan(0.42);
  for (const direction of [-1, 1]) {
    const core = quality.valid.filter((v) => Math.hypot(v.x - center - direction * 24, v.y - center) < 20);
    expect(core.length).toBeGreaterThanOrEqual(6);
    for (const v of core) {
      const [dx, dy] = opposed(v.x, v.y);
      expect(Math.hypot(v.dx! - dx, v.dy! - dy)).toBeLessThan(0.54);
    }
  }
  const far = quality.valid.filter((v) => Math.hypot(v.x - center, v.y - center) > 73);
  expect(far.filter((v) => Math.hypot(v.dx!, v.dy!) > 0.32).length / far.length).toBeLessThan(0.03);
});

test('JPEG sources retain the signed opposing field on the exported displacement grid', async ({ page }) => {
  const { reference, disturbed } = frames(opposed);
  await page.goto('/plugins/background-oriented-schlieren');
  const panel = page.getByRole('region', { name: 'Still-pair displacement' });
  for (const [role, input] of [
    ['Reference image', reference],
    ['Disturbed image', disturbed],
  ] as const) {
    const encoded = await page.evaluate(
      ({ width, height, values }) => {
        const c = document.createElement('canvas');
        c.width = width;
        c.height = height;
        const ctx = c.getContext('2d')!,
          image = ctx.createImageData(width, height);
        for (let i = 0; i < values.length; i++) {
          image.data[4 * i] = image.data[4 * i + 1] = image.data[4 * i + 2] = values[i];
          image.data[4 * i + 3] = 255;
        }
        ctx.putImageData(image, 0, 0);
        return c.toDataURL('image/jpeg', 0.68).split(',')[1];
      },
      { width: side, height: side, values: Array.from(input.pixels) },
    );
    await panel
      .getByLabel(role, { exact: true })
      .setInputFiles({ name: role + '.jpg', mimeType: 'image/jpeg', buffer: Buffer.from(encoded, 'base64') });
  }
  await panel.getByRole('button', { name: 'Analyze still pair' }).click();
  const download = page.waitForEvent('download');
  await panel.getByRole('link', { name: 'Download numeric grid' }).click();
  const result = JSON.parse(await readFile((await (await download).path())!, 'utf8'));
  expect(result.localSearch).toBe('pyramid');
  const quality = evaluate(result, opposed);
  expect(quality.fraction).toBeGreaterThan(0.84);
  expect(quality.p95).toBeLessThan(0.5);
  const moving = quality.valid.filter((v) => Math.hypot(...opposed(v.x, v.y)) > 1.7);
  expect(moving.length).toBeGreaterThan(8);
  for (const v of moving) {
    const [dx, dy] = opposed(v.x, v.y);
    expect(Math.hypot(v.dx! - dx, v.dy! - dy)).toBeLessThan(0.65);
  }
});
