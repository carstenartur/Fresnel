import { expect, test } from '@playwright/test';
import { reconstruct, type Light } from '../src/photometry/solve';
import { patternDigest } from '../src/photometry/patternDigest';
import { integrate } from '../src/photometry/height';
import type { Grid } from '../src/photometry/solve';
import { reliefStl } from '../src/photometry/reliefStl';

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
  expect(grid.confidence[0]).toBeCloseTo(.75, 5);
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
  expect(grid.confidence[0]).toBeCloseTo(.875, 5);
  normal.forEach((n, i) => expect(grid.normals[i]).toBeCloseTo(n, 5));
  const clipped = reconstruct(lights, dark, flat, samples.map(() => Float32Array.of(1)));
  expect(clipped.valid[0]).toBe(0);
  expect(clipped.confidence[0]).toBe(0);
  expect(reconstruct(lights.map(() => lights[0]), dark, flat, observations()).valid[0]).toBe(0);
});

test('staged RGB bytes match the Photographer protocol asymmetric-2x2 fixture', async () => {
  const rgba = new Uint8ClampedArray([1,2,3,255,9,8,7,255,17,34,51,255,200,201,202,255]);
  expect(await patternDigest(2,2,rgba)).toBe('b4cdaed2adc75c59cad1e968b64c3164ffa5aed74e4034f643f4731e460bfce5');
});


function field(width: number, height: number, slope: (x:number,y:number)=>number[]) {
  const n = width*height;
  const grid: Grid = { normals:new Float32Array(3*n),valid:new Uint8Array(n).fill(1),
    confidence:new Float32Array(n).fill(1),albedo:new Float32Array(n),lightCount:new Uint8Array(n),residual:new Float32Array(n) };
  for (let i=0; i<n; i++) {
    const [dx,dy] = slope(i%width,Math.floor(i/width)), norm = Math.hypot(dx,dy,1);
    grid.normals.set([-dx/norm,dy/norm,1/norm],3*i);
  }
  return grid;
}

test('masked Poisson relief preserves quadratic height differences and separate component datums', () => {
  const w=23,h=19, grid=field(w,h,(x,y)=>[.02*x,.04*y]);
  // Low-confidence normals must not connect two separately referenced surfaces.
  for (let y=0;y<h;y++) grid.confidence[y*w+11]=.05;
  const relief=integrate(w,h,grid,false);
  expect(relief.components).toBe(2);
  expect(relief.valid[11]).toBe(0);
  const origin=w+1, endpoint=10*w+8;
  expect((relief.values[endpoint]-relief.values[origin]) - (.01*(8*8-1)+.02*(10*10-1))).toBeCloseTo(0,4);
  expect(relief.curlRms).toBeLessThan(.00001);
  const plane=integrate(w,h,field(w,h,()=>[.2,-.3]),true);
  expect(Math.max(...plane.values)-Math.min(...plane.values)).toBeLessThan(.0001);
});

test('rejects non-integrable rotating slopes before exporting height', () => {
  expect(()=>integrate(20,20,field(20,20,(x,y)=>[-.14*(y-10),.14*(x-10)]),false))
    .toThrow(/curl/);
});

test('STL requires explicit dimensions and includes only supported cells in a closed relative relief', () => {
  const relief=integrate(3,3,field(3,3,()=>[.2,.1]),false);
  relief.valid[8]=0;
  expect(()=>reliefStl(3,3,relief,0,2,1)).toThrow(/scale/);
  const bytes=reliefStl(3,3,relief,20,2,1);
  const view=new DataView(bytes);
  expect(new TextDecoder().decode(bytes.slice(0,80))).toContain('USER-ASSIGNED');
  expect(view.getUint32(80,true)).toBe(28); // three top/base quads and eight boundary walls
  const incidence=new Map<string,number>();
  let maximumZ=0;
  for (let face=0;face<28;face++) {
    const vertices=[];
    for (let vertex=0;vertex<3;vertex++) {
      const offset=84+50*face+12+12*vertex;
      maximumZ=Math.max(maximumZ,view.getFloat32(offset+8,true));
      vertices.push([0,4,8].map(i=>view.getFloat32(offset+i,true)).join(','));
    }
    for (let i=0;i<3;i++) { const edge=[vertices[i],vertices[(i+1)%3]].sort().join('/'); incidence.set(edge,1+(incidence.get(edge)??0)); }
  }
  expect(maximumZ).toBeCloseTo(3,6);
  expect([...incidence.values()].every(count=>count===2)).toBe(true);
});
