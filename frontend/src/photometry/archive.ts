import type { Manifest, Result } from './data';
import { describeRelief } from './RelativeRelief';
import type { Relief } from './height';
import { registrationLimits,registrationMethod } from './registration';
import { limits } from './solve';

// Preserve full Float32 precision within the bounded JSON result size, including the height grid.
function encode(values:Uint8Array|Float32Array) {
  const bytes=new Uint8Array(values.length*values.BYTES_PER_ELEMENT);
  if (values instanceof Float32Array) {
    const view=new DataView(bytes.buffer);
    for (let i=0;i<values.length;i++) view.setFloat32(4*i,values[i],true);
  } else bytes.set(values);
  let raw='';
  for (let i=0;i<bytes.length;i+=16384) raw+=String.fromCharCode(...bytes.subarray(i,i+16384));
  return {encoding:'base64',type:values instanceof Float32Array ? 'float32-le' : 'uint8',data:btoa(raw)};
}

export function archiveResult(manifest:Manifest,result:Result,relief:Relief|null) {
  return new Blob([JSON.stringify({manifest,sources:result.sources,width:result.width,height:result.height,
    solver:'trimmed-lambertian/1',limits,scale:'Relative',alignment:{method:registrationMethod,limits:registrationLimits},warnings:result.warnings,
    grid:Object.fromEntries(Object.entries(result.grid).map(([k,v])=>[k,encode(v)])),
    relief:relief && {...describeRelief(relief),values:encode(relief.values),valid:encode(relief.valid)},
  })],{type:'application/json'});
}
