import { register, registrationReference } from './registration';
import { reconstruct, limits, type Light } from './solve';
import type { Manifest, Source, Result } from './data';

// Read untrusted dimensions before invoking a browser image decoder (one bounded bitmap at a time).
function dimensions(b: Uint8Array) {
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  if (b.length >= 24 && v.getUint32(0) === 0x89504e47 && v.getUint32(4) === 0x0d0a1a0a &&
      v.getUint32(12) === 0x49484452) return [v.getUint32(16), v.getUint32(20)];
  if (b[0] === 0xff && b[1] === 0xd8) {
    let i = 2;
    while (i + 8 < b.length && b[i] === 0xff) {
      while (b[i] === 0xff) i++;
      const marker = b[i++];
      if (marker === 0xda || marker === 0xd9 || i + 7 > b.length) break;
      const length = v.getUint16(i);
      if (length < 2 || i + length > b.length) break;
      if ([0xc0, 0xc1, 0xc2].includes(marker)) return [v.getUint16(i + 5), v.getUint16(i + 3)];
      i += length;
    }
  }
  throw new Error('Select a supported JPEG or PNG camera exposure.');
}

self.onmessage = async (event: MessageEvent<{ manifest: Manifest; files: Record<string,File> }>) => {
  try {
    const { manifest, files } = event.data;
    const sources: Source[] = [], lights: Light[] = [], frames: Float32Array[] = [];
    let dark: Float32Array | undefined, flat: Float32Array | undefined, width = 0, height = 0;
    let reference:ReturnType<typeof registrationReference> | undefined;
    const warnings:string[]=[];
    const lut = Float32Array.from({ length: 256 }, (_, i) => (i/255) ** manifest.geometry.cameraGamma);
    for (const p of manifest.patterns) {
      const file = files[p.id];
      if (!file || file.size > 16*1024*1024) throw new Error('Each pattern needs a PNG or JPEG of at most 16 MiB.');
      const bytes = new Uint8Array(await file.arrayBuffer());
      const [w,h] = dimensions(bytes);
      if (!w || !h || w*h > 24_000_000 || Math.max(w,h) > 12000)
        throw new Error('Camera images must be at most 24 megapixels and 12,000 pixels on each side.');
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
        n => n.toString(16).padStart(2, '0')).join('');
      const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
      try {
        if (sources.length && (bitmap.width !== sources[0].width || bitmap.height !== sources[0].height))
          throw new Error('All camera images must have the same upright dimensions.');
        const scale = Math.min(1, Math.sqrt(limits.maxPixels / (bitmap.width * bitmap.height)), 512/Math.max(bitmap.width, bitmap.height));
        width = Math.max(1, Math.floor(bitmap.width * scale));
        height = Math.max(1, Math.floor(bitmap.height * scale));
        const ctx = new OffscreenCanvas(width,height).getContext('2d', { willReadFrequently: true });
        if (!ctx) throw new Error('Canvas decoding is unavailable in this browser.');
        ctx.drawImage(bitmap,0,0,width,height);
        const rgba = ctx.getImageData(0,0,width,height).data, intensities = new Float32Array(width*height);
        let clipped=0;
        for (let i = 0; i < intensities.length; i++) {
          const j = 4*i;
          // Mark individual clipped color channels, even when their luminance is below saturation.
          if (rgba[j+3] !== 255) throw new Error('Camera frames must be opaque.');
          if (Math.max(rgba[j],rgba[j+1],rgba[j+2])>=254) clipped++;
          intensities[i] = Math.max(rgba[j],rgba[j+1],rgba[j+2]) >= 254 ? 1
            : .2126*lut[rgba[j]] + .7152*lut[rgba[j+1]] + .0722*lut[rgba[j+2]];
        }
        let transform:Source['transform']=null;
        if (p.role === 'DARK') dark = intensities;
        else if (p.role === 'FLAT') {
          flat = intensities;
          reference=registrationReference(flat,width,height);
        } else if (p.direction && reference) {
          try {
            const registered=register(reference,intensities);
            transform=registered.transform; frames.push(registered.samples);
          } catch(error) { throw new Error(p.id+': '+(error instanceof Error ? error.message : String(error))); }
          lights.push({ ...p.direction, relativeIntensity:p.relativeIntensity });
        }
        sources.push({ patternId:p.id, sha256:hash, name:file.name, width:bitmap.width, height:bitmap.height,
          lastModified:file.lastModified, transform, clippedFraction:clipped/intensities.length });
      } finally { bitmap.close(); }
    }
    if (!dark || !flat) throw new Error('Dark and flat exposures are required.');
    if (sources.some(s=>s.transform?.status==='LOW_TEXTURE'))
      warnings.push('Some exposures lack enough texture to verify alignment. The solve assumes the object and camera remained fixed.');
    if (sources.some(s=>s.transform && s.clippedFraction>.15))
      warnings.push('More than 15% of a directional exposure clipped. Reduce exposure and repeat to recover those observations.');
    let ambient=0,lit=0;
    for (let i=0;i<flat.length;i++) { ambient+=dark[i]; lit+=flat[i]; }
    if (lit>0 && ambient/lit>.35) warnings.push('Ambient light is strong relative to the display. Reduce room light before repeating the capture.');
    const grid = reconstruct(lights, dark, flat, frames);
    if (!grid.valid.some(x => x === 1)) throw new Error('No supported surface pixels. Check pattern mapping, exposure, camera locks and object motion.');
    const result: Result = { width, height, grid, sources, warnings };
    self.postMessage({ result });
  } catch (e) { self.postMessage({ error: e instanceof Error ? e.message : String(e) }); }
};
