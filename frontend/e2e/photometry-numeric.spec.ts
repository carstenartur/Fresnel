import { expect, test } from '@playwright/test';
import { reconstruct, type Light } from '../src/photometry/solve';

const lights: Light[] = Array.from({ length: 8 }, (_, i) => {
  const phi = i * Math.PI / 4;
  return { x: .6 * Math.cos(phi), y: .6 * Math.sin(phi), z: .8, relativeIntensity: 1 };
});
const albedo = .46;
const normal = [.3, -.4, Math.sqrt(.75)];

function observations() {
  return lights.map(l => Float32Array.of(.04 + albedo * (l.x * normal[0] + l.y * normal[1] + l.z * normal[2])));
}
const dark = Float32Array.of(.04), flat = Float32Array.of(.8);

test('recovers a tilted Lambertian normal and relative albedo with a clipped and a shadowed exposure', () => {
  const samples = observations();
  samples[1][0] = 1;
  samples[5][0] = 0;
  const grid = reconstruct(lights, dark, flat, samples);
  expect(grid.valid[0]).toBe(1);
  expect(grid.lightCount[0]).toBe(6);
  expect(grid.albedo[0]).toBeCloseTo(albedo, 5);
  expect(grid.residual[0]).toBeLessThan(.00001);
  normal.forEach((n, i) => expect(grid.normals[i]).toBeCloseTo(n, 5));
});

test('rejects one sparse specular outlier and preserves an unsupported pixel as invalid', () => {
  const samples = observations();
  samples[2][0] += .3;
  const grid = reconstruct(lights, dark, flat, samples);
  expect(grid.valid[0]).toBe(1);
  expect(grid.lightCount[0]).toBe(7);
  normal.forEach((n, i) => expect(grid.normals[i]).toBeCloseTo(n, 5));
  expect(reconstruct(lights, dark, flat, samples.map(() => Float32Array.of(1))).valid[0]).toBe(0);
  expect(reconstruct(lights.map(() => lights[0]), dark, flat, observations()).valid[0]).toBe(0);
});

import { patternDigest } from '../src/photometry/patternDigest';
test('staged RGB bytes match the Photographer protocol asymmetric-2x2 fixture', async () => {
  const rgba = new Uint8ClampedArray([1,2,3,255,9,8,7,255,17,34,51,255,200,201,202,255]);
  expect(await patternDigest(2,2,rgba)).toBe('b4cdaed2adc75c59cad1e968b64c3164ffa5aed74e4034f643f4731e460bfce5');
});
