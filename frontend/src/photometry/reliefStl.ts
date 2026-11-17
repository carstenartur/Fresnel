import type { Relief } from './height';

/** User-scaled, closed relief tiles. Only complete valid four-pixel cells receive faces. */
export function reliefStl(w:number,h:number,r:Relief,widthMm:number,rangeMm:number,baseMm:number): ArrayBuffer {
  if (![widthMm,rangeMm,baseMm].every(v=>Number.isFinite(v) && v>=.01 && v<=2000))
    throw new Error('Enter an explicit positive user-assigned scale between 0.01 and 2000 mm.');
  const cells=new Uint8Array((w-1)*(h-1));
  for (let y=0;y<h-1;y++) for (let x=0;x<w-1;x++) {
    const i=y*w+x;
    cells[y*(w-1)+x]=r.valid[i]&r.valid[i+1]&r.valid[i+w]&r.valid[i+w+1];
  }
  function cell(x:number,y:number) { return x>=0 && y>=0 && x<w-1 && y<h-1 && cells[y*(w-1)+x]===1; }
  let faces=0, surfaceMin=Infinity, surfaceMax=-Infinity;
  for (let y=0;y<h-1;y++) for (let x=0;x<w-1;x++) if (cell(x,y)) {
    const i=y*w+x;
    for (const j of [i,i+1,i+w,i+w+1]) { surfaceMin=Math.min(surfaceMin,r.values[j]); surfaceMax=Math.max(surfaceMax,r.values[j]); }
    faces+=4+2*(Number(!cell(x-1,y))+Number(!cell(x+1,y))+Number(!cell(x,y-1))+Number(!cell(x,y+1)));
  }
  if (!faces || faces>1_100_000) throw new Error('Relief mesh exceeds its supported size or contains no complete surface cells.');
  if (surfaceMax-surfaceMin<1e-6) throw new Error('Relief has no supported height range to scale.');
  const bytes=new ArrayBuffer(84+50*faces), view=new DataView(bytes), text=new TextEncoder();
  new Uint8Array(bytes).set(text.encode('Fresnel USER-ASSIGNED mm W='+widthMm+' H='+rangeMm+' base='+baseMm).subarray(0,80));
  view.setUint32(80,faces,true);
  let offset=84;
  const pitch=widthMm/(w-1);
  type Point=[number,number,number];
  function point(i:number,top:boolean):Point {
    return [pitch*(i%w),pitch*(h-1-Math.floor(i/w)),top ? baseMm+rangeMm*(r.values[i]-surfaceMin)/(surfaceMax-surfaceMin) : 0];
  }
  function triangle(a:Point,b:Point,c:Point) {
    const ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
    const nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,length=Math.hypot(nx,ny,nz);
    for (const value of [nx/length,ny/length,nz/length,...a,...b,...c]) { view.setFloat32(offset,value,true); offset+=4; }
    offset+=2;
  }
  function wall(i:number,j:number) {
    const a=point(i,true),b=point(j,true),c=point(i,false),d=point(j,false);
    triangle(b,a,c); triangle(b,c,d);
  }
  for (let y=0;y<h-1;y++) for (let x=0;x<w-1;x++) if (cell(x,y)) {
    const a=y*w+x,b=a+1,c=a+w,d=c+1;
    triangle(point(a,true),point(c,true),point(b,true)); triangle(point(b,true),point(c,true),point(d,true));
    triangle(point(a,false),point(b,false),point(c,false)); triangle(point(b,false),point(d,false),point(c,false));
    if (!cell(x-1,y)) wall(a,c);
    if (!cell(x+1,y)) wall(d,b);
    if (!cell(x,y-1)) wall(b,a);
    if (!cell(x,y+1)) wall(c,d);
  }
  return bytes;
}
