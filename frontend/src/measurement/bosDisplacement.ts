import { fitCamera, stabilize, validFootprint, type CameraModel, type Matrix } from './bosCameraModel';

// All coordinates and displacements refer to the orientation-corrected analysis raster.
export interface GrayFrame {
  width: number;
  height: number;
  pixels: Uint8Array;
}
export interface Vector {
  x: number;
  y: number;
  dx: number | null;
  dy: number | null;
  correlation: number;
}
export const settings = {
  minimumSide: 96,
  maximumSide: 512,
  windowRadius: 7,
  globalRadius: 9,
  localRadius: 5,
  minimumCorrelation: 0.65,
  peakSeparation: 0.055,
  minimumStdDev: 7,
  registrationTolerance: 0.85,
  minimumAnchorFraction: 0.75,
  minimumVectorFraction: 0.45,
} as const;

export type LocalSearch = 'single-scale' | 'pyramid';
export const pyramidSettings = {
  decimation: 2,
  lowPassKernel: [1, 4, 6, 4, 1],
  coarseWindowRadius: 4,
  coarseRadius: 7,
  refineRadius: 3,
  smallWindowRadius: 4,
  maximumComponent: 10,
} as const;

function halfSize(frame: GrayFrame): GrayFrame {
  const width = Math.floor(frame.width / 2),
    height = Math.floor(frame.height / 2);
  // Separable binomial low pass before decimation reduces aliasing when the dot
  // texture shifts by a fraction of a coarse pixel. Each sample is centered at (2x, 2y).
  const kernel = pyramidSettings.lowPassKernel,
    horizontal = new Uint16Array(width * frame.height);
  for (let y = 0; y < frame.height; y++)
    for (let x = 0; x < width; x++) {
      let sum = 0;
      for (let k = -2; k <= 2; k++)
        sum +=
          kernel[k + 2] * frame.pixels[y * frame.width + Math.max(0, Math.min(frame.width - 1, 2 * x + k))];
      horizontal[y * width + x] = sum;
    }
  const pixels = new Uint8Array(width * height);
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      let sum = 0;
      for (let k = -2; k <= 2; k++)
        sum += kernel[k + 2] * horizontal[Math.max(0, Math.min(frame.height - 1, 2 * y + k)) * width + x];
      pixels[y * width + x] = Math.round(sum / 256);
    }
  return { width, height, pixels };
}

function median(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b);
  return (sorted[(sorted.length - 1) >> 1] + sorted[sorted.length >> 1]) / 2;
}

function peakOffset(before: number, center: number, after: number) {
  const curvature = before - 2 * center + after;
  return curvature < -1e-8 ? Math.max(-0.5, Math.min(0.5, (before - after) / (2 * curvature))) : 0;
}

// Exhaustive integer ZNCC within the bounded window, then parabolic peak refinement.
// Keep separated competing peaks to reject periodic/ambiguous patterns.
function match(
  reference: GrayFrame,
  disturbed: GrayFrame,
  x: number,
  y: number,
  baseX: number,
  baseY: number,
  radius: number,
  h: number = settings.windowRadius,
) {
  if (
    x - h < 0 ||
    y - h < 0 ||
    x + h >= reference.width ||
    y + h >= reference.height ||
    x + baseX - h - radius < 0 ||
    y + baseY - h - radius < 0 ||
    x + baseX + h + radius >= disturbed.width ||
    y + baseY + h + radius >= disturbed.height
  )
    return null;
  const side = 2 * h + 1,
    n = side * side;
  const a = reference.pixels,
    b = disturbed.pixels,
    w = reference.width;
  let sumA = 0,
    sumA2 = 0;
  for (let j = -h; j <= h; j++)
    for (let i = -h; i <= h; i++) {
      const value = a[(y + j) * w + x + i];
      sumA += value;
      sumA2 += value * value;
    }
  const varA = sumA2 - (sumA * sumA) / n;
  const minimumVariance = n * settings.minimumStdDev ** 2;
  if (varA < minimumVariance) return null;
  const span = 2 * radius + 1,
    scores = new Float64Array(span * span);
  let best = -2,
    bestIndex = 0;
  for (let j = 0; j < span; j++)
    for (let i = 0; i < span; i++) {
      const delta = (baseY + j - radius) * w + baseX + i - radius;
      let sumB = 0,
        sumB2 = 0,
        cross = 0;
      for (let row = -h; row <= h; row++) {
        const start = (y + row) * w + x - h;
        for (let k = start; k < start + side; k++) {
          const value = b[k + delta];
          sumB += value;
          sumB2 += value * value;
          cross += a[k] * value;
        }
      }
      const varB = sumB2 - (sumB * sumB) / n;
      const score = varB >= minimumVariance ? (cross - (sumA * sumB) / n) / Math.sqrt(varA * varB) : -1;
      const index = j * span + i;
      scores[index] = score;
      if (score > best) {
        best = score;
        bestIndex = index;
      }
    }
  const col = bestIndex % span,
    row = Math.floor(bestIndex / span);
  if (best < settings.minimumCorrelation || !col || !row || col === span - 1 || row === span - 1) return null;
  for (let i = 0; i < scores.length; i++) {
    const separated = Math.abs((i % span) - col) > 1 || Math.abs(Math.floor(i / span) - row) > 1;
    if (separated && best - scores[i] < settings.peakSeparation) return null;
  }
  return {
    dx: baseX + col - radius + peakOffset(scores[bestIndex - 1], best, scores[bestIndex + 1]),
    dy: baseY + row - radius + peakOffset(scores[bestIndex - span], best, scores[bestIndex + span]),
    correlation: Math.min(1, best),
  };
}

export function register(reference: GrayFrame, disturbed: GrayFrame, model: CameraModel = 'translation') {
  const anchors = [];
  const fractions = [0.18, 0.39, 0.61, 0.82];
  for (const fy of fractions)
    for (const fx of fractions) {
      if (fx > 0.18 && fx < 0.82 && fy > 0.18 && fy < 0.82) continue;
      const x = Math.round((reference.width - 1) * fx),
        y = Math.round((reference.height - 1) * fy);
      const vector = match(reference, disturbed, x, y, 0, 0, settings.globalRadius);
      if (vector) anchors.push({ x, y, ...vector, quadrant: (fx > 0.5 ? 1 : 0) + (fy > 0.5 ? 2 : 0) });
    }
  if (model !== 'translation')
    return fitCamera(anchors, model, reference.width, reference.height, settings.registrationTolerance);
  const error =
    'Registration failed. Keep the camera fixed and the outer dot field sharp and undisturbed. Check for rotation, clipping or excessive camera motion.';
  if (anchors.length < 9) throw new Error(error);
  const initialX = median(anchors.map((a) => a.dx)),
    initialY = median(anchors.map((a) => a.dy));
  const inliers = anchors.filter(
    (a) => Math.hypot(a.dx - initialX, a.dy - initialY) <= settings.registrationTolerance,
  );
  if (
    inliers.length / 12 < settings.minimumAnchorFraction ||
    new Set(inliers.map((a) => a.quadrant)).size !== 4
  ) {
    throw new Error(error);
  }
  const dx = median(inliers.map((a) => a.dx)),
    dy = median(inliers.map((a) => a.dy));
  const residualRms = Math.sqrt(
    inliers.reduce((s, a) => s + (a.dx - dx) ** 2 + (a.dy - dy) ** 2, 0) / inliers.length,
  );
  const matrix: Matrix = [1, 0, dx, 0, 1, dy, 0, 0, 1];
  return { model, matrix, dx, dy, residualRms, inliers: inliers.length, anchors: 12 };
}

export function analyzePair(
  reference: GrayFrame,
  disturbed: GrayFrame,
  model: CameraModel = 'translation',
  localSearch: LocalSearch = 'pyramid',
) {
  const { width, height } = reference;
  if (width !== disturbed.width || height !== disturbed.height)
    throw new Error('Reference and disturbed image dimensions must match.');
  if (
    ![width, height].every(
      (d) => Number.isInteger(d) && d >= settings.minimumSide && d <= settings.maximumSide,
    ) ||
    [reference, disturbed].some((f) => f.pixels.length !== width * height)
  ) {
    throw new Error('Analysis images must be between 96 and 512 pixels on each side.');
  }
  const registration = register(reference, disturbed, model);
  const aligned = model === 'translation' ? disturbed : stabilize(disturbed, registration.matrix);
  const offsetX = model === 'translation' ? registration.dx : 0;
  const offsetY = model === 'translation' ? registration.dy : 0;
  const baseX = Math.round(offsetX),
    baseY = Math.round(offsetY);
  const coarse = localSearch === 'pyramid' ? { ref: halfSize(reference), frame: halfSize(aligned) } : null;
  function localMatch(x: number, y: number) {
    const valid = (cx: number, cy: number, radius: number) =>
      model === 'translation' || validFootprint(registration.matrix, cx, cy, radius, width, height);
    const halfRadius = pyramidSettings.coarseWindowRadius + pyramidSettings.coarseRadius;
    const guide =
      coarse && valid(x, y, 2 * halfRadius + 2)
        ? match(
            coarse.ref,
            coarse.frame,
            Math.round(x / 2),
            Math.round(y / 2),
            Math.round(offsetX / 2),
            Math.round(offsetY / 2),
            pyramidSettings.coarseRadius,
            pyramidSettings.coarseWindowRadius,
          )
        : null;
    const predictedX = guide ? Math.round(2 * guide.dx) : baseX;
    const predictedY = guide ? Math.round(2 * guide.dy) : baseY;
    const wide = guide && Math.max(Math.abs(predictedX - baseX), Math.abs(predictedY - baseY)) >= 4;
    const bx = wide ? predictedX : baseX,
      by = wide ? predictedY : baseY;
    const radius = wide ? pyramidSettings.refineRadius : settings.localRadius;
    const fullWindow = settings.windowRadius + radius;
    if (!valid(x + bx, y + by, fullWindow)) return null;
    let result = match(reference, aligned, x, y, bx, by, radius);
    // Strong local gradients can decorrelate a 15-pixel patch. An independent coarse
    // peak must support the smaller window within two full-resolution pixels.
    if (!result && guide) {
      const small = match(
        reference,
        aligned,
        x,
        y,
        predictedX,
        predictedY,
        pyramidSettings.refineRadius,
        pyramidSettings.smallWindowRadius,
      );
      if (
        small &&
        Math.hypot(small.dx - 2 * guide.dx, small.dy - 2 * guide.dy) <= 2 &&
        valid(
          x + predictedX,
          y + predictedY,
          pyramidSettings.smallWindowRadius + pyramidSettings.refineRadius,
        )
      )
        result = small;
    }
    if (
      result &&
      localSearch === 'pyramid' &&
      Math.max(Math.abs(result.dx - offsetX), Math.abs(result.dy - offsetY)) >
        pyramidSettings.maximumComponent
    )
      return null;
    return result;
  }
  const step = Math.max(12, Math.ceil(Math.max(width, height) / 28));
  const margin = settings.windowRadius + settings.globalRadius + settings.localRadius;
  const cols = Math.floor((width - 1 - 2 * margin) / step) + 1,
    rows = Math.floor((height - 1 - 2 * margin) / step) + 1;
  const x0 = Math.floor((width - 1 - (cols - 1) * step) / 2),
    y0 = Math.floor((height - 1 - (rows - 1) * step) / 2);
  const vectors: Vector[] = [];
  for (let row = 0; row < rows; row++)
    for (let col = 0; col < cols; col++) {
      const x = x0 + col * step,
        y = y0 + row * step;
      const m = localMatch(x, y);
      vectors.push({
        x,
        y,
        dx: m ? m.dx - offsetX : null,
        dy: m ? m.dy - offsetY : null,
        correlation: m?.correlation ?? 0,
      });
    }
  // A single spurious peak surrounded by a coherent neighbourhood is marked invalid.
  const rejected: number[] = [];
  for (let i = 0; i < vectors.length; i++) {
    const v = vectors[i];
    if (v.dx === null || v.dy === null) continue;
    const neighbors = [];
    for (
      let r = Math.max(0, Math.floor(i / cols) - 1);
      r <= Math.min(rows - 1, Math.floor(i / cols) + 1);
      r++
    ) {
      for (let c = Math.max(0, (i % cols) - 1); c <= Math.min(cols - 1, (i % cols) + 1); c++) {
        const n = vectors[r * cols + c];
        if (n !== v && n.dx !== null && n.dy !== null) neighbors.push({ dx: n.dx, dy: n.dy });
      }
    }
    if (
      neighbors.length >= 4 &&
      Math.hypot(v.dx - median(neighbors.map((n) => n.dx)), v.dy - median(neighbors.map((n) => n.dy))) > 2.5
    )
      rejected.push(i);
  }
  for (const i of rejected) vectors[i] = { ...vectors[i], dx: null, dy: null, correlation: 0 };
  const magnitudes = vectors
    .flatMap((v) => (v.dx === null || v.dy === null ? [] : [Math.hypot(v.dx, v.dy)]))
    .sort((a, b) => a - b);
  if (magnitudes.length / vectors.length < settings.minimumVectorFraction) {
    throw new Error(
      'Too few reliable displacement windows. Use a sharper dot pattern, a smaller displacement or less compression.',
    );
  }
  return {
    width,
    height,
    registration,
    localSearch,
    grid: { x0, y0, step, cols, rows },
    vectors,
    accepted: magnitudes.length,
    autoLimitPx: Math.max(0.15, magnitudes[Math.floor((magnitudes.length - 1) * 0.95)]),
  };
}
export type PairResult = ReturnType<typeof analyzePair>;
