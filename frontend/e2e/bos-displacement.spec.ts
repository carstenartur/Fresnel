import { expect, test } from '@playwright/test';
import { analyzePair, type GrayFrame } from '../src/measurement/bosDisplacement';

const size = 160;
function texture(): GrayFrame {
  let seed = 39438;
  const noise = new Uint8Array(size * size);
  for (let i = 0; i < noise.length; i++) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    noise[i] = seed >>> 24;
  }
  const pixels = noise.slice();
  for (let y = 1; y < size - 1; y++)
    for (let x = 1; x < size - 1; x++) {
      const i = y * size + x;
      pixels[i] = (4 * noise[i] + noise[i - 1] + noise[i + 1] + noise[i - size] + noise[i + size]) / 8;
    }
  return { width: size, height: size, pixels };
}

function warp(source: GrayFrame, field: (x: number, y: number) => number[], gain = 1, offset = 0): GrayFrame {
  const pixels = new Uint8Array(size * size);
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      const [dx, dy] = field(x, y);
      const fx = Math.min(size - 2, Math.max(0, x - dx)),
        fy = Math.min(size - 2, Math.max(0, y - dy));
      const ix = Math.floor(fx),
        iy = Math.floor(fy),
        a = fx - ix,
        b = fy - iy;
      const i = iy * size + ix,
        p = source.pixels;
      const value =
        (1 - b) * ((1 - a) * p[i] + a * p[i + 1]) + b * ((1 - a) * p[i + size] + a * p[i + size + 1]);
      pixels[y * size + x] = Math.round(gain * value + offset);
    }
  return { ...source, pixels };
}

test('stabilizes fractional camera translation and tolerates a brightness change', () => {
  const ref = texture();
  const r = analyzePair(
    ref,
    warp(ref, () => [2.35, -1.7], 0.8, 18),
  );
  expect(Math.abs(r.registration.dx - 2.35)).toBeLessThan(0.22);
  expect(Math.abs(r.registration.dy + 1.7)).toBeLessThan(0.22);
  const accepted = r.vectors.filter((v) => v.dx !== null && v.dy !== null);
  expect(accepted.length / r.vectors.length).toBeGreaterThan(0.9);
  expect(accepted.filter((v) => Math.hypot(v.dx!, v.dy!) > 0.3).length / accepted.length).toBeLessThan(0.03);
});

test('measures a smooth local shift after stabilization and masks occlusion', () => {
  const ref = texture();
  const peak = (x: number, y: number) => Math.exp(-((x - 80) ** 2 + (y - 80) ** 2) / 450);
  const frame = warp(ref, (x, y) => [2 + 1.8 * peak(x, y), -1]);
  for (let y = 41; y < 65; y++) frame.pixels.fill(125, y * size + 40, y * size + 65);
  const r = analyzePair(ref, frame);
  const center = r.vectors.filter((v) => v.dx !== null && Math.hypot(v.x - 80, v.y - 80) < 21);
  expect(center.length).toBeGreaterThanOrEqual(4);
  for (const v of center) expect(Math.abs(v.dx! - 1.8 * peak(v.x, v.y))).toBeLessThan(0.4);
  expect(r.vectors.some((v) => v.x > 46 && v.x < 60 && v.y > 46 && v.y < 60 && v.dx === null)).toBeTruthy();
});

test('rejects incompatible geometry, ambiguous patterns, and unregistrable motion', () => {
  const ref = texture();
  expect(() => analyzePair(ref, { ...ref, height: 120 })).toThrow(/dimensions/i);
  const flat = { ...ref, pixels: new Uint8Array(size * size).fill(80) };
  expect(() => analyzePair(flat, flat)).toThrow(/register|registration/i);
  const stripes = {
    ...ref,
    pixels: Uint8Array.from(ref.pixels, (_, i) => ((i % size) % 4 < 2 ? 50 : 190)),
  };
  expect(() => analyzePair(stripes, stripes)).toThrow(/register|registration/i);
  expect(() =>
    analyzePair(
      ref,
      warp(ref, () => [14, 0]),
    ),
  ).toThrow(/register|registration/i);
  expect(() =>
    analyzePair(
      ref,
      warp(ref, (x, y) => [0.052 * (y - 80), -0.052 * (x - 80)]),
    ),
  ).toThrow(/register|registration/i);
});

for (const model of ['affine', 'homography'] as const) {
  const center = (size - 1) / 2;
  function camera(x: number, y: number) {
    const u = x - center,
      v = y - center;
    const w = model === 'homography' ? 1 + 0.00047 * u - 0.00031 * v : 1;
    return [center + (1.009 * u - 0.039 * v + 1.4) / w, center + (0.035 * u + 0.992 * v - 1.1) / w];
  }
  const peak = (x: number, y: number) => Math.exp(-((x - center) ** 2 + (y - center) ** 2) / 360);
  // Render from an independent, analytic forward mapping by inverting it at each destination pixel.
  function render(ref: GrayFrame, local: boolean) {
    return warp(ref, (x, y) => {
      let sx = x,
        sy = y;
      for (let k = 0; k < 8; k++) {
        const strength = local ? peak(sx, sy) : 0;
        const [tx, ty] = camera(sx + 1.8 * strength, sy - 0.9 * strength);
        sx += x - tx;
        sy += y - ty;
      }
      return [x - sx, y - sy];
    });
  }
  test(model + ' removes camera warp and preserves signed local displacement in reference pixels', () => {
    const ref = texture();
    for (const local of [false, true]) {
      const result = analyzePair(ref, render(ref, local), model);
      const registration = result.registration;
      expect(registration.model).toBe(model);
      expect(registration.inliers).toBeGreaterThanOrEqual(9);
      const m = registration.matrix;
      for (const x of [12, 80, 147])
        for (const y of [12, 80, 147]) {
          const [tx, ty] = camera(x, y),
            w = m[6] * x + m[7] * y + m[8];
          expect(
            Math.hypot((m[0] * x + m[1] * y + m[2]) / w - tx, (m[3] * x + m[4] * y + m[5]) / w - ty),
          ).toBeLessThan(0.55);
        }
      const good = result.vectors.filter((v) => v.dx !== null && v.dy !== null);
      expect(good.length / result.vectors.length).toBeGreaterThan(0.88);
      const errors = good
        .map((v) => {
          const strength = local ? peak(v.x, v.y) : 0;
          return Math.hypot(v.dx! - 1.8 * strength, v.dy! + 0.9 * strength);
        })
        .sort((a, b) => a - b);
      expect(errors[Math.floor(0.95 * (errors.length - 1))]).toBeLessThan(0.4);
      const central = good.filter((v) => Math.hypot(v.x - center, v.y - center) < 18);
      expect(central.length).toBeGreaterThanOrEqual(4);
      if (local)
        for (const v of central) {
          expect(Math.hypot(v.dx! - 1.8 * peak(v.x, v.y), v.dy! + 0.9 * peak(v.x, v.y))).toBeLessThan(0.48);
        }
    }
  });

  test(model + ' rejects insufficient spatial support', () => {
    const ref = texture();
    const partial = { ...ref, pixels: ref.pixels.slice() };
    for (let y = 0; y < size / 2; y++) partial.pixels.fill(128, y * size, (y + 1) * size);
    expect(() => analyzePair(ref, partial, model)).toThrow(/registration/i);
  });
}

test('default and explicit translation preserve the same vectors and registration', () => {
  const ref = texture(),
    shifted = warp(ref, () => [2.35, -1.7]);
  expect(analyzePair(ref, shifted)).toEqual(analyzePair(ref, shifted, 'translation'));
});
