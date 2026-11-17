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

function warp(
  source: GrayFrame,
  field: (x: number, y: number) => number[],
  gain = 1,
  offset = 0,
): GrayFrame {
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
  expect(accepted.filter((v) => Math.hypot(v.dx!, v.dy!) > 0.3).length / accepted.length).toBeLessThan(
    0.03,
  );
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
