/** Photographer Capture v1 commitment over the staged opaque sRGB canvas pixels. */
export async function patternDigest(width: number, height: number, rgba: Uint8ClampedArray) {
  if (rgba.length !== 4*width*height) throw new Error('Unexpected presentation buffer size');
  const prefix = new TextEncoder().encode('FRESNEL-PATTERN-RGB8-V1\0');
  const bytes = new Uint8Array(prefix.length+8+3*width*height);
  bytes.set(prefix);
  const view = new DataView(bytes.buffer);
  view.setUint32(prefix.length,width);
  view.setUint32(prefix.length+4,height);
  let dest = prefix.length+8;
  for (let i=0; i<rgba.length; i+=4) {
    if (rgba[i+3] !== 255) throw new Error('Illumination raster must be opaque');
    bytes[dest++] = rgba[i]; bytes[dest++] = rgba[i+1]; bytes[dest++] = rgba[i+2];
  }
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),
    n => n.toString(16).padStart(2,'0')).join('');
}
