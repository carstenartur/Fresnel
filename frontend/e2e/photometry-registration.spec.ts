import { expect, test } from '@playwright/test';
import { registrationReference, register } from '../src/photometry/registration';
import { reconstruct, type Light } from '../src/photometry/solve';
const w=64,h=64;
const reference=Float32Array.from({length:w*h},(_,i)=>{
  const x=i%w,y=Math.floor(i/w);
  const lattice=Math.sin(.63*x+.78*y)+Math.cos(.83*y-.45*x)+Math.sin(.72*x-.64*y);
  return .4+.14*lattice+.02*Math.cos(3.7*x+6*y);
});
function moved(x:number,y:number) {
  return Float32Array.from({length:w*h},(_,i)=>{
    const a=i%w-x,b=Math.floor(i/w)-y;
    return a>=0 && a<w && b>=0 && b<h ? .6*reference[a+b*w]+.06 : 0;
  });
}
test('recovers a bounded integer translation under changed illumination and masks exposed borders',()=>{
  const ref=registrationReference(reference,w,h);
  const r=register(ref,moved(2,-1));
  expect(r.transform).toMatchObject({dx:2,dy:-1,status:'ALIGNED'});
  expect(r.transform.correlation).toBeGreaterThan(.99);
  expect(r.samples[20*w+15]).toBeCloseTo(.6*reference[20*w+15]+.06,6);
  expect(Number.isNaN(r.samples[0])).toBe(true);
});
test('rejects a confidently displaced capture beyond three pixels and identifies unobservable motion',()=>{
  expect(()=>register(registrationReference(reference,w,h),moved(-5,4))).toThrow(/movement/);
  const uniform=new Float32Array(w*h).fill(.5);
  expect(register(registrationReference(uniform,w,h),uniform).transform.status).toBe('LOW_TEXTURE');
});

test('noisy Lambertian hemisphere recovers normals across varying albedo and natural cast shadows',()=>{
  const w=44,h=44;
  const lights:Light[]=Array.from({length:12},(_,i)=>({x:.6*Math.cos(i*Math.PI/6),y:.6*Math.sin(i*Math.PI/6),z:.8,relativeIntensity:.85}));
  const dark=new Float32Array(w*h).fill(.015),flat=new Float32Array(w*h);
  const samples=lights.map(()=>new Float32Array(w*h)), vectors:number[][]=[],albedo:number[]=[];
  for(let i=0;i<w*h;i++){
    const x=(i%w-21.5)/22,y=(Math.floor(i/w)-21.5)/22;
    const z=Math.sqrt(Math.max(0,1-x*x-y*y));vectors.push([x,y,z]);albedo.push(.32+.15*(x+1));
    if(x*x+y*y>.98) continue;
    flat[i]=.65;
    lights.forEach((l,j)=>{
      const noise=.0015*Math.sin(4.2*i-1.7*j);
      samples[j][i]=dark[i]+l.relativeIntensity*albedo[i]*Math.max(0,l.x*x+l.y*y+l.z*z)+noise;
    });
  }
  const grid=reconstruct(lights,dark,flat,samples);
  const errors:number[]=[],albedoErrors:number[]=[];
  for(let i=0;i<w*h;i++){
    if(!flat[i]) {expect(grid.valid[i]).toBe(0);continue;}
    if(!grid.valid[i]) continue;
    const n=vectors[i],cos=n.reduce((s,v,j)=>s+v*grid.normals[3*i+j],0);
    errors.push(Math.acos(Math.max(-1,Math.min(1,cos)))*180/Math.PI);
    albedoErrors.push(Math.abs(grid.albedo[i]-albedo[i]));
  }
  errors.sort((a,b)=>a-b);albedoErrors.sort((a,b)=>a-b);
  expect(errors.length).toBeGreaterThan(1350);
  expect(errors[Math.floor(errors.length*.95)]).toBeLessThan(1);
  expect(albedoErrors[Math.floor(albedoErrors.length*.95)]).toBeLessThan(.005);
});
