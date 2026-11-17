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
