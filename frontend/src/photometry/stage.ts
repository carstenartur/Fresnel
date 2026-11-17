import { generator, type Manifest, type Pattern } from './data';
import { patternDigest } from './patternDigest';

/** Commit only opaque native-resolution canvas pixels after decode and staging. */
export async function stage(canvas:HTMLCanvasElement, manifest:Manifest, pattern:Pattern, active:()=>boolean) {
  const {widthPx,heightPx}=manifest.geometry;
  const blob=await (await generator('patterns/'+pattern.id,manifest.geometry)).blob();
  const image=await createImageBitmap(blob);
  try {
    if (!active()) throw new Error('Presentation interrupted.');
    const ctx=canvas.getContext('2d',{willReadFrequently:true});
    if (!ctx || canvas.width!==widthPx || canvas.height!==heightPx || image.width!==widthPx || image.height!==heightPx)
      throw new Error('Unexpected pattern raster.');
    ctx.drawImage(image,0,0);
    const hash=await patternDigest(widthPx,heightPx,ctx.getImageData(0,0,widthPx,heightPx).data);
    if (hash!==pattern.pixelSha256) throw new Error('Staged pixels differ from the sequence manifest.');
    if (!active()) throw new Error('Presentation interrupted.');
    return blob;
  } finally { image.close(); }
}

export function nativeSize(target:HTMLCanvasElement) {
  const box=target.getBoundingClientRect();
  return Math.round(box.width*devicePixelRatio)===target.width && Math.round(box.height*devicePixelRatio)===target.height;
}
export const displayFrame=()=>new Promise<void>(done=>requestAnimationFrame(()=>done()));
