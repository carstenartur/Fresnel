import {type Geometry} from './data';

export interface Region { left:number;top:number;right:number;bottom:number }
export const fullRegion:Region={left:0,top:0,right:1,bottom:1};
export function regionFrom(value:unknown):Region {
  if (value===undefined || value===null) return fullRegion;
  const r=value as Region;
  if (![r.left,r.top,r.right,r.bottom].every(n=>Number.isFinite(n)&& n>=0 && n<=1) || r.left>=r.right || r.top>=r.bottom)
    throw new Error('Select a nonempty region inside the camera image.');
  return {left:r.left,top:r.top,right:r.right,bottom:r.bottom};
}
export function inRegion(i:number,w:number,h:number,r:Region) {
  const x=((i%w)+.5)/w,y=(Math.floor(i/w)+.5)/h;
  return x>=r.left && x<r.right && y>=r.top && y<r.bottom;
}

export function profileValues(g:Geometry) {
  return {screenWidthMm:g.screenWidthMm,screenHeightMm:g.screenHeightMm,widthPx:g.widthPx,heightPx:g.heightPx};
}
export async function profileIdentity(g:Geometry) {
  const publicValues=profileValues(g);
  const bytes=new TextEncoder().encode('display-profile/1\n'+JSON.stringify(publicValues));
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('');
  return {version:'display-profile/1',...publicValues,sha256};
}

export interface Preflight {
  pixels:number;flatMean:number;flatClip:number;contrast:number;
  darkFraction:number|null;oppositeResponse:number|null;messages:string[];
}
export function assess(w:number,h:number,r:Region,flat:Float32Array,dark?:Float32Array,a?:Float32Array,b?:Float32Array):Preflight {
  let n=0,clipped=0,sum=0,ambient=0,response=0,opposedPixels=0,local=0,pairs=0;
  for(let i=0;i<flat.length;i++) if(inRegion(i,w,h,r)){
    n++;sum+=flat[i];clipped+=Number(flat[i]>=.98);
    if(dark)ambient+=dark[i];
    if(a&&b&&Number.isFinite(a[i])&&Number.isFinite(b[i])){response+=Math.abs(a[i]-b[i]);opposedPixels++;}
    if(i%w+1<w && inRegion(i+1,w,h,r)){local+=Math.abs(flat[i+1]-flat[i]);pairs++;}
    if(i+w<flat.length && inRegion(i+w,w,h,r)){local+=Math.abs(flat[i+w]-flat[i]);pairs++;}
  }
  if(n===0)throw new Error('The selected object region is smaller than one analysis pixel.');
  const check:Preflight={pixels:n,flatMean:sum/n,flatClip:clipped/n,contrast:pairs?local/pairs:0,
    darkFraction:dark&&sum>0?ambient/sum:null,oppositeResponse:opposedPixels?response/opposedPixels:null,messages:[]};
  if(n<64)check.messages.push('The object occupies fewer than 64 analysis pixels. Move the camera closer or enlarge the region.');
  if(check.flatClip>.1)check.messages.push('Camera highlights are clipping. Lower exposure or monitor brightness.');
  if(check.flatMean<.04)check.messages.push('Flat exposure is dark. Increase display brightness or exposure.');
  if(check.contrast<.002)check.messages.push('No distinct edges are visible. Check focus on the preview; a smooth object can also produce this result.');
  if(check.darkFraction!==null&&check.darkFraction>.35)check.messages.push('Ambient light is strong relative to the display. Reduce room lighting.');
  if(check.oppositeResponse!==null&&check.oppositeResponse<.015)check.messages.push('Opposite patches produced similar exposures. Verify mapping and try a matte object with more relief.');
  return check;
}
