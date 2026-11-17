import { settings, type GrayFrame } from './bosDisplacement';
import { detectMarkers, type TargetGeometry } from './bosMarkers';

export interface Region {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface Box extends Region {
  area: number;
}
export const fullFrame: Region = { x: 0, y: 0, width: 1, height: 1 };
export function readRegion(value: unknown): Region | null {
  if (typeof value !== 'object' || value === null) return null;
  const { x, y, width, height } = value as Region;
  if (
    ![x, y, width, height].every(Number.isFinite) ||
    x < 0 ||
    y < 0 ||
    width <= 0 ||
    height <= 0 ||
    x + width > 1.000001 ||
    y + height > 1.000001
  )
    return null;
  return { x, y, width, height };
}
export function pixelRegion(region: Region, width: number, height: number): Region {
  const x = Math.max(0, Math.min(width - 1, Math.round(region.x * width))),
    y = Math.max(0, Math.min(height - 1, Math.round(region.y * height)));
  return {
    x,
    y,
    width: Math.max(1, Math.min(width, Math.round((region.x + region.width) * width)) - x),
    height: Math.max(1, Math.min(height, Math.round((region.y + region.height) * height)) - y),
  };
}
function histogram(frame: GrayFrame, r: Region) {
  const bins = new Uint32Array(256);
  for (let y = r.y; y < r.y + r.height; y++)
    for (let x = r.x; x < r.x + r.width; x++) bins[frame.pixels[y * frame.width + x]]++;
  return bins;
}
function otsu(bins: Uint32Array) {
  let total = 0,
    sum = 0;
  bins.forEach((n, i) => {
    total += n;
    sum += n * i;
  });
  let left = 0,
    leftSum = 0,
    best = -1,
    threshold = 0;
  for (let i = 0; i < 255; i++) {
    left += bins[i];
    leftSum += bins[i] * i;
    if (left === 0 || left === total) continue;
    const score = left * (total - left) * (leftSum / left - (sum - leftSum) / (total - left)) ** 2;
    if (score > best) {
      best = score;
      threshold = i;
    }
  }
  return threshold;
}
function quantile(bins: Uint32Array, q: number) {
  const count = bins.reduce((a, b) => a + b, 0);
  let target = Math.max(1, Math.ceil(count * q));
  for (let i = 0; i < bins.length; i++) {
    target -= bins[i];
    if (target <= 0) return i;
  }
  return 255;
}
function components(frame: GrayFrame, r: Region, threshold: number, dark: boolean) {
  const { width: w, height: h } = r,
    mask = new Uint8Array(w * h),
    queue = new Int32Array(w * h);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++)
      mask[y * w + x] = Number(frame.pixels[(r.y + y) * frame.width + r.x + x] <= threshold === dark);
  const found: Box[] = [];
  let count = 0;
  for (let i = 0; i < mask.length; i++) {
    if (!mask[i]) continue;
    let first = 0,
      last = 1,
      minX = w,
      minY = h,
      maxX = 0,
      maxY = 0;
    queue[0] = i;
    mask[i] = 0;
    while (first < last) {
      const n = queue[first++],
        x = n % w,
        y = Math.floor(n / w);
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      if (x > 0 && mask[n - 1]) {
        mask[n - 1] = 0;
        queue[last++] = n - 1;
      }
      if (x + 1 < w && mask[n + 1]) {
        mask[n + 1] = 0;
        queue[last++] = n + 1;
      }
      if (y > 0 && mask[n - w]) {
        mask[n - w] = 0;
        queue[last++] = n - w;
      }
      if (y + 1 < h && mask[n + w]) {
        mask[n + w] = 0;
        queue[last++] = n + w;
      }
    }
    if (minX === 0 || minY === 0 || maxX === w - 1 || maxY === h - 1) continue;
    count++;
    if (found.length < 100_000)
      found.push({
        x: r.x + minX,
        y: r.y + minY,
        width: maxX - minX + 1,
        height: maxY - minY + 1,
        area: last,
      });
  }
  return { boxes: found, overflow: count > found.length };
}
const median = (xs: number[]) => (xs.length ? xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)] : null);

export function assessReference(
  frame: GrayFrame,
  geometry: TargetGeometry | null,
  manual: Region | null,
  source: { sourceWidth: number; sourceHeight: number } = {
    sourceWidth: frame.width,
    sourceHeight: frame.height,
  },
) {
  const all = { x: 0, y: 0, width: frame.width, height: frame.height };
  const allBins = histogram(frame, all),
    globalThreshold = otsu(allBins);
  const markerBoxes =
    geometry?.fiducials.length === 4
      ? components(frame, all, globalThreshold, !geometry.invertPattern)
      : null;
  const markers =
    geometry && markerBoxes && !markerBoxes.overflow
      ? detectMarkers(frame, geometry, markerBoxes.boxes, globalThreshold)
      : null;
  const region = manual ?? markers?.region ?? fullFrame,
    r = pixelRegion(region, frame.width, frame.height);
  const bins = histogram(frame, r),
    count = r.width * r.height;
  const contrast = quantile(bins, 0.99) - quantile(bins, 0.01),
    threshold = otsu(bins);
  const darkPixels = bins.slice(0, threshold + 1).reduce((a, b) => a + b, 0);
  const dark = markers && geometry ? !geometry.invertPattern : darkPixels < count / 2;
  const blobs = contrast >= 10 ? components(frame, r, threshold, dark) : { boxes: [], overflow: false };
  const dots = (blobs.overflow ? [] : blobs.boxes).filter(
    (b) => b.area / count < 0.01 && Math.min(b.width, b.height) / Math.max(b.width, b.height) >= 0.5,
  );
  const dotDiameterPx = median(dots.map((b) => 2 * Math.sqrt(b.area / Math.PI)));
  const edgeSteps = new Uint32Array(256);
  for (let y = r.y + 1; y < r.y + r.height; y++)
    for (let x = r.x + 1; x < r.x + r.width; x++) {
      const i = y * frame.width + x,
        v = frame.pixels[i];
      for (const other of [frame.pixels[i - 1], frame.pixels[i - frame.width]]) {
        if (v <= threshold !== other <= threshold) edgeSteps[Math.abs(v - other)]++;
      }
    }
  const sharpnessPx =
    edgeSteps.some((n) => n > 0) && contrast > 0 ? contrast / Math.max(1, quantile(edgeSteps, 0.5)) : null;
  // A manual crop can regain native detail that was reduced in the full-frame quality preview.
  const nativeRegion = pixelRegion(region, source.sourceWidth, source.sourceHeight);
  const reduction = Math.min(1, settings.maximumSide / Math.max(nativeRegion.width, nativeRegion.height));
  const analysisWidth = Math.round(nativeRegion.width * reduction),
    analysisHeight = Math.round(nativeRegion.height * reduction);
  const analysisScale = Math.sqrt((analysisWidth * analysisHeight) / (r.width * r.height));
  const windowSize = 2 * settings.windowRadius + 1;
  const windowWidth = (windowSize * r.width) / analysisWidth,
    windowHeight = (windowSize * r.height) / analysisHeight;
  const cols = Math.floor(analysisWidth / windowSize),
    rows = Math.floor(analysisHeight / windowSize);
  const cells = new Uint32Array(cols * rows);
  for (const b of dots) {
    const x = Math.floor((b.x + b.width / 2 - r.x) / windowWidth),
      y = Math.floor((b.y + b.height / 2 - r.y) / windowHeight);
    if (x < cols && y < rows) cells[y * cols + x]++;
  }
  const occupiedFraction = cells.length ? cells.filter((n) => n >= 2).length / cells.length : 0;
  const darkClippedFraction = (bins[0] + bins[1] + bins[2] + bins[3]) / count;
  const brightClippedFraction = (bins[252] + bins[253] + bins[254] + bins[255]) / count;
  const guidance: string[] = [];
  if (Math.min(analysisWidth, analysisHeight) < settings.minimumSide)
    guidance.push('Enlarge the selected region to provide at least 96 analysis pixels on each side.');
  if (blobs.overflow)
    guidance.push(
      'More than 100,000 isolated features exceed the dot-count limit. Use larger dots or a smaller active region.',
    );
  if (contrast < 35) guidance.push('Increase dot-to-background contrast; check lighting and exposure.');
  if (contrast < 80 && brightClippedFraction > 0.25)
    guidance.push('Lower the display brightness or exposure to recover bright detail.');
  if (contrast < 80 && darkClippedFraction > 0.25)
    guidance.push('Increase illumination or exposure to recover dark detail.');
  if (dotDiameterPx !== null && dotDiameterPx * analysisScale < 1.8)
    guidance.push('Move closer or use larger dots so individual features resolve at analysis resolution.');
  if (sharpnessPx !== null && sharpnessPx > 2.5)
    guidance.push(
      'Check focus, shorten the exposure and keep the camera still; dot edges spread across several pixels.',
    );
  if (occupiedFraction < 0.65)
    guidance.push('Use a denser, sharper dot field or select a region containing more isolated dots.');
  if (markers && markers.region.width * markers.region.height < 0.25)
    guidance.push('Move closer so more of the camera frame covers the target.');
  if (!markers && !manual)
    guidance.push(
      'Set the active region manually if the full frame includes the target border or surroundings.',
    );
  return {
    region,
    selection: manual ? 'manual' : markers ? 'four-fiducial' : 'full-frame',
    markers,
    analysisWidth,
    analysisHeight,
    contrast,
    threshold,
    frameWidth: frame.width,
    frameHeight: frame.height,
    darkClippedFraction,
    brightClippedFraction,
    componentLimitExceeded: blobs.overflow,
    dotCount: dots.length,
    dotDiameterPx,
    sharpnessPx,
    occupiedFraction,
    windowCount: cells.length,
    windowSizeAnalysisPx: 2 * settings.windowRadius + 1,
    analysisScale,
    guidance,
  };
}
export type ReferenceQuality = ReturnType<typeof assessReference>;
