export const registrationMethod='gradient-translation/1';
export const registrationLimits={searchRadius:6,maxShift:3,minCorrelation:.60,minImprovement:.04,
  minStandardDeviation:.001} as const;
export interface Transform {
  dx:number; dy:number; correlation:number|null; status:'IDENTITY'|'ALIGNED'|'LOW_TEXTURE';
}
interface Reference {width:number;height:number;gradient:Float32Array}
function gradient(image:Float32Array,w:number,h:number) {
  const result=new Float32Array(image.length);
  for (let y=1;y<h-1;y++) for(let x=1;x<w-1;x++){
    const i=y*w+x;
    result[i]=Math.hypot(image[i+1]-image[i-1],image[i+w]-image[i-w]);
  }
  return result;
}
export function registrationReference(image:Float32Array,width:number,height:number):Reference {
  return {width,height,gradient:gradient(image,width,height)};
}
function correlate(ref:Reference,target:Float32Array,dx:number,dy:number,radius:number,
    region?:{x0:number;y0:number;x1:number;y1:number}):number|null {
  const {width:w,height:h}=ref,margin=radius+1;
  const stride=Math.max(1,Math.floor(Math.sqrt(w*h/6000)));
  const x0=Math.max(margin,region?.x0??0),y0=Math.max(margin,region?.y0??0);
  const x1=Math.min(w-margin,region?.x1??w),y1=Math.min(h-margin,region?.y1??h);
  let n=0,sa=0,sb=0,saa=0,sbb=0,sab=0;
  for(let y=y0;y<y1;y+=stride) for(let x=x0;x<x1;x+=stride){
    const i=y*w+x,a=ref.gradient[i],b=target[i+dx+dy*w];
    sa+=a;sb+=b;saa+=a*a;sbb+=b*b;sab+=a*b;n++;
  }
  if(n<16) return null;
  const va=saa-sa*sa/n,vb=sbb-sb*sb/n;
  const floor=n*registrationLimits.minStandardDeviation**2;
  return va>floor && vb>floor ? Math.min(1,Math.max(-1,(sab-sa*sb/n)/Math.sqrt(va*vb))) : null;
}

/** Coordinates are camera pixels on the bounded analysis grid; sample source(x+dx,y+dy) at reference(x,y). */
export function register(ref:Reference,image:Float32Array):{transform:Transform;samples:Float32Array} {
  const {width:w,height:h}=ref;
  const radius=Math.min(registrationLimits.searchRadius,Math.floor((Math.min(w,h)-6)/2));
  const target=gradient(image,w,h);
  const identity=radius>=1 ? correlate(ref,target,0,0,radius) : null;
  if(identity===null) return {transform:{dx:0,dy:0,correlation:null,status:'LOW_TEXTURE'},samples:image};
  let dx=0,dy=0,best=identity;
  for(let y=-radius;y<=radius;y++) for(let x=-radius;x<=radius;x++){
    if (x===0 && y===0) continue;
    const score=correlate(ref,target,x,y,radius);
    if(score!==null && score>best){best=score;dx=x;dy=y;}
  }
  if(best<registrationLimits.minCorrelation)
    throw new Error('Camera framing or surface appearance changed too much for reliable registration. Check focus, movement and reflections.');
  if(best-identity<registrationLimits.minImprovement){dx=0;dy=0;best=identity;}
  if(Math.max(Math.abs(dx),Math.abs(dy))>registrationLimits.maxShift)
    throw new Error('Camera or object movement exceeds three analysis pixels. Reacquire the sequence with fixed framing.');
  // Independent textured quadrants should support the same translation. Reject obvious local
  // disagreement instead of deforming a moving surface into a plausible result.
  for(let row=0;row<2;row++) for(let col=0;col<2;col++){
    const region={x0:Math.floor(col*w/2),x1:Math.floor((col+1)*w/2),y0:Math.floor(row*h/2),y1:Math.floor((row+1)*h/2)};
    const local=correlate(ref,target,dx,dy,radius,region);
    if(local!==null && local<.2) throw new Error('Local structure disagrees with the estimated translation. Check moving or deforming regions.');
  }
  const transform:Transform={dx,dy,correlation:best,status:dx||dy?'ALIGNED':'IDENTITY'};
  if(!dx && !dy) return {transform,samples:image};
  const samples=new Float32Array(image.length).fill(NaN);
  for(let y=Math.max(0,-dy);y<Math.min(h,h-dy);y++){
    const x0=Math.max(0,-dx),x1=Math.min(w,w-dx);
    samples.set(image.subarray((y+dy)*w+x0+dx,(y+dy)*w+x1+dx),y*w+x0);
  }
  return {transform,samples};
}
