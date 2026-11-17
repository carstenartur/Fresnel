import { expect, test } from '@playwright/test';
import { assessReference, fullFrame, readRegion } from '../src/measurement/bosReferenceQuality';
import type { GrayFrame } from '../src/measurement/bosDisplacement';
import type { BosTargetManifest } from '../src/bosTargetApi';

const width = 320,
  height = 240,
  size = 14;
const fiducials: BosTargetManifest['fiducials'] = [
  { id: 'top-left', shape: 'SOLID_SQUARE', region: { x: 4, y: 4, width: size, height: size } },
  { id: 'top-right', shape: 'RING', region: { x: 297, y: 4, width: size, height: size } },
  { id: 'bottom-left', shape: 'L_SHAPE', region: { x: 4, y: 215, width: size, height: size } },
  { id: 'bottom-right', shape: 'CROSS', region: { x: 297, y: 215, width: size, height: size } },
];
const geometry = {
  widthPx: width,
  heightPx: height,
  activeRegion: { x: 24, y: 24, width: 272, height: 192 },
  fiducials,
  invertPattern: false,
};

function reference(): GrayFrame {
  const pixels = new Uint8Array(width * height).fill(230);
  for (let y = 28; y < height - 24; y += 9)
    for (let x = 28; x < width - 24; x += 9) {
      for (let dy = -2; dy <= 2; dy++)
        for (let dx = -2; dx <= 2; dx++) {
          if (dx * dx + dy * dy <= 4) pixels[(y + dy) * width + x + dx] = 25;
        }
    }
  for (const { shape, region } of fiducials)
    for (let y = 0; y < size; y++)
      for (let x = 0; x < size; x++) {
        const d = Math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2);
        if (
          shape === 'SOLID_SQUARE' ||
          (shape === 'RING' && d <= 7 && d >= 3.5) ||
          (shape === 'L_SHAPE' && (x < 3 || y >= 11)) ||
          (shape === 'CROSS' && ((x >= 6 && x < 8) || (y >= 6 && y < 8)))
        )
          pixels[(region.y + y) * width + region.x + x] = 25;
      }
  return { width, height, pixels };
}

test('measures dot diameter, edge width and occupancy from the selected pixels', () => {
  const result = assessReference(reference(), geometry, null);
  expect(result.markers?.matches).toHaveLength(4);
  expect(result.region.x).toBeCloseTo(24 / width, 2);
  expect(result.region.width).toBeCloseTo(272 / width, 2);
  expect(result.contrast).toBe(205);
  expect(result.dotDiameterPx).toBeGreaterThan(3);
  expect(result.dotDiameterPx).toBeLessThan(5);
  expect(result.sharpnessPx).toBeCloseTo(1, 1);
  expect(result.occupiedFraction).toBeGreaterThan(0.75);
  expect(result.darkClippedFraction + result.brightClippedFraction).toBe(0);
});

test('finds rotated inverted targets and rejects blank or incomplete marker evidence', () => {
  const src = reference(),
    pixels = new Uint8Array(width * height);
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) pixels[x * height + (height - 1 - y)] = 255 - src.pixels[y * width + x];
  const result = assessReference(
    { width: height, height: width, pixels },
    { ...geometry, invertPattern: true },
    null,
  );
  expect(result.markers?.matches).toHaveLength(4);
  const blank = assessReference({ ...src, pixels: new Uint8Array(width * height).fill(255) }, geometry, null);
  expect(blank.markers).toBeNull();
  expect(blank.dotDiameterPx).toBeNull();
  expect(blank.guidance.join(' ')).toMatch(/contrast|exposure/i);
  expect(blank.brightClippedFraction).toBe(1);
  expect(readRegion({ x: 0, y: 0, width: 3, height: 0.2 })).toBeNull();
  const partial = reference();
  for (let y = 0; y < 24; y++) partial.pixels.fill(230, y * width, y * width + 24);
  expect(assessReference(partial, geometry, null).markers).toBeNull();
});

test('reports low contrast in a manual region independently of a high-contrast surrounding field', () => {
  const frame = reference();
  const region = { x: 0.25, y: 0.25, width: 0.4, height: 0.45 };
  for (let y = 60; y < 168; y++) frame.pixels.fill(123, y * width + 80, y * width + 208);
  const selected = assessReference(frame, null, region);
  expect(selected.region).toEqual(region);
  expect(selected.contrast).toBe(0);
  expect(assessReference(frame, null, fullFrame).contrast).toBeGreaterThan(100);
});

test('detects a mildly perspective-warped field and degrades gracefully when edges blur', () => {
  const src = reference(),
    pixels = new Uint8Array(width * height).fill(230);
  // Inverse of a slanted pinhole-plane projection. Horizontal magnification varies with depth.
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const v = (y - 8) / (0.91 - 0.00018 * (y - 8));
      const u = ((x - 7) * (1 + 0.00018 * v) - 0.055 * v) / 0.91;
      const a = Math.round(u),
        b = Math.round(v);
      if (a >= 0 && b >= 0 && a < width && b < height) pixels[y * width + x] = src.pixels[b * width + a];
    }
  const warped = assessReference({ width, height, pixels }, geometry, null);
  expect(warped.markers?.meanAgreement).toBeGreaterThan(0.78);
  expect(warped.region.width).toBeGreaterThan(0.65);
  expect(warped.region.width).toBeLessThan(0.85);
  const blurred = new Uint8Array(width * height).fill(230);
  for (let y = 2; y < height - 2; y++)
    for (let x = 2; x < width - 2; x++) {
      let sum = 0;
      for (let dy = -2; dy <= 2; dy++)
        for (let dx = -2; dx <= 2; dx++) sum += src.pixels[(y + dy) * width + x + dx];
      blurred[y * width + x] = Math.round(sum / 25);
    }
  const region = { x: 0.1, y: 0.15, width: 0.8, height: 0.7 };
  const sharp = assessReference(src, null, region),
    soft = assessReference({ width, height, pixels: blurred }, null, region);
  expect(soft.sharpnessPx!).toBeGreaterThan(sharp.sharpnessPx! * 2);
  expect(soft.guidance.join(' ')).toMatch(/focus/i);
});
