// Synthetic upright diffuse plane, with fixed sensor noise texture so registration can be checked.
import { crc32, deflateSync } from 'node:zlib';
export function sourceImage(step) {
  const w = 64, h = 64;
  const bytes = Buffer.alloc((w + 1) * h);
  const lightIndex = Number(step.slice(6)) - 1;
  const angle = lightIndex * Math.PI / 2;
  const cosine = 450 / Math.hypot(450, .4 * 530 * Math.cos(angle), .4 * 300 * Math.sin(angle));
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const texture = .5 + .08*Math.sin(.41*x+.7*y) + .09*Math.cos(.19*x-.63*y) + .06*Math.sin(.55*x-.22*y);
    const linear = step === 'dark' ? .015 : step === 'flat' ? .015 + texture : .015 + texture*cosine**4;
    bytes[y*(w+1)+1+x] = Math.round(255*linear**(1/2.2));
  }
  return encodeGray(w,h,bytes);
}

export function bosFrame(step) {
  const w=160, h=128, stride=w+1;
  const source=Buffer.alloc(w*h);
  let seed=1944;
  for(let i=0;i<source.length;i++) {
    seed=(Math.imul(1664525,seed)+1013904223)>>>0;
    source[i]=seed>>>24;
  }
  const amount=step==='reference'?0:Number(step.slice(6));
  const bytes=Buffer.alloc(stride*h);
  for(let y=0;y<h;y++)for(let x=0;x<w;x++) {
    const dx=x>52 && x<108 && y>38 && y<98 ? amount : 0;
    bytes[y*stride+x+1]=source[y*w+x-dx];
  }
  return encodeGray(w,h,bytes);
}

function encodeGray(w,h,bytes) {
  const chunk = (name, data) => {
    const type = Buffer.from(name);
    const header = Buffer.alloc(4); header.writeUInt32BE(data.length);
    const sum = Buffer.alloc(4); sum.writeUInt32BE(crc32(Buffer.concat([type,data])));
    return Buffer.concat([header,type,data,sum]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w); ihdr.writeUInt32BE(h,4); ihdr[8] = 8;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),
    chunk('IDAT',deflateSync(bytes)),chunk('IEND',Buffer.alloc(0))]);
}
