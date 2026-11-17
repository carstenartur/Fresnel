import {expect,test} from '@playwright/test';
import {assess,fullRegion,inRegion,profileIdentity,regionFrom} from '../src/photometry/setup';
import {defaults} from '../src/photometry/data';

test('profiles identify only resolved display settings and preserve the selected camera region',async()=>{
  const first=await profileIdentity(defaults);
  expect(first.sha256).toMatch(/^[a-f0-9]{64}$/);
  expect(await profileIdentity({...defaults,cameraOffsetYmm:0})).toEqual(first);
  expect((await profileIdentity({...defaults,screenWidthMm:600})).sha256).not.toBe(first.sha256);
  expect(regionFrom(undefined)).toEqual(fullRegion);
  expect(()=>regionFrom({left:.8,right:.3,top:0,bottom:1})).toThrow();
  expect(inRegion(0,4,2,{left:.25,top:0,right:1,bottom:1})).toBe(false);
});

test('setup quality is measured within the object region and ignores unobserved registration borders',()=>{
  const flat=Float32Array.from([.1,1,.1,1]),dark=Float32Array.from([0,.6,0,.6]);
  const check=assess(2,2,{left:.5,top:0,right:1,bottom:1},flat,dark,
    Float32Array.from([0,.4,0,NaN]),Float32Array.from([0,.7,0,NaN]));
  expect(check.pixels).toBe(2);
  expect(check.flatClip).toBe(1);
  expect(check.darkFraction).toBeCloseTo(.6);
  expect(check.oppositeResponse).toBeCloseTo(.3);
  expect(check.messages.join(' ')).toContain('clipping');
});
