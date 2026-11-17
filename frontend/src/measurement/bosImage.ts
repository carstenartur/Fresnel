import { settings, type GrayFrame } from './bosDisplacement';
import { fullFrame, pixelRegion, readRegion, type Region } from './bosReferenceQuality';

const maxBytes = 20 * 1024 * 1024;
const maxPixels = 24_000_000;

function declaredDimensions(bytes: Uint8Array): [number, number] {
  const get16 = (i: number) => bytes[i] * 256 + bytes[i + 1];
  if (
    [137, 80, 78, 71, 13, 10, 26, 10].every((n, i) => bytes[i] === n) &&
    bytes.length >= 33 &&
    get16(10) === 13 &&
    String.fromCharCode(...bytes.subarray(12, 16)) === 'IHDR'
  ) {
    const view = new DataView(bytes.buffer, bytes.byteOffset);
    return [view.getUint32(16), view.getUint32(20)];
  }
  if (bytes[0] === 255 && bytes[1] === 216) {
    let pos = 2;
    while (pos + 4 <= bytes.length && bytes[pos] === 255) {
      while (pos < bytes.length && bytes[pos] === 255) pos++;
      const marker = bytes[pos++],
        length = get16(pos);
      if (marker === 0xda || marker === 0xd9 || length < 2 || pos + length > bytes.length) break;
      if ([0xc0, 0xc1, 0xc2].includes(marker) && length >= 8) return [get16(pos + 5), get16(pos + 3)];
      pos += length;
    }
  }
  throw new Error('Choose a valid PNG or baseline/progressive JPEG image.');
}

export async function decodeFrame(
  file: File,
  region: Region = fullFrame,
  maximumSide: number = settings.maximumSide,
) {
  if (!readRegion(region) || ![settings.maximumSide, 1024].includes(maximumSide))
    throw new Error('Invalid analysis region or resolution.');
  if (!file.size || file.size > maxBytes) throw new Error('Each image must be between 1 byte and 20 MiB.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const [w, h] = declaredDimensions(bytes);
  if (!w || !h || w > 16384 || h > 16384 || w * h > maxPixels) {
    throw new Error('Each source image is limited to 24 megapixels and 16,384 pixels per side.');
  }
  const image = await createImageBitmap(file, { imageOrientation: 'from-image' });
  try {
    const sourceWidth = image.width,
      sourceHeight = image.height;
    if (!((sourceWidth === w && sourceHeight === h) || (sourceWidth === h && sourceHeight === w))) {
      throw new Error('Image geometry differs from its header.');
    }
    const crop = pixelRegion(region, sourceWidth, sourceHeight);
    const scale = Math.min(1, maximumSide / Math.max(crop.width, crop.height));
    const width = Math.round(crop.width * scale),
      height = Math.round(crop.height * scale);
    if (Math.min(width, height) < settings.minimumSide)
      throw new Error('Use an image with at least 96 analysis pixels in each direction.');
    const canvas = new OffscreenCanvas(width, height);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) throw new Error('A 2D image decoder is unavailable.');
    ctx.fillStyle = 'white';
    ctx.fillRect(0, 0, width, height);
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(image, crop.x, crop.y, crop.width, crop.height, 0, 0, width, height);
    const rgba = ctx.getImageData(0, 0, width, height).data,
      pixels = new Uint8Array(width * height);
    for (let i = 0; i < pixels.length; i++)
      pixels[i] = Math.round(0.2126 * rgba[4 * i] + 0.7152 * rgba[4 * i + 1] + 0.0722 * rgba[4 * i + 2]);
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    const sha256 = Array.from(hash, (b) => b.toString(16).padStart(2, '0')).join('');
    const frame: GrayFrame = { width, height, pixels };
    return {
      frame,
      canvas,
      metadata: {
        name: file.name,
        byteLength: file.size,
        sha256,
        sourceWidth,
        sourceHeight,
        sourceRegion: crop,
        sourcePixelsPerAnalysisPixel: { x: crop.width / width, y: crop.height / height },
      },
    };
  } finally {
    image.close();
  }
}
