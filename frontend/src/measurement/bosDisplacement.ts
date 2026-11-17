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
) {
  const h = settings.windowRadius,
    side = 2 * h + 1,
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
  if (best < settings.minimumCorrelation || !col || !row || col === span - 1 || row === span - 1)
    return null;
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

export function register(reference: GrayFrame, disturbed: GrayFrame) {
  const anchors = [];
  const fractions = [0.18, 0.39, 0.61, 0.82];
  for (const fy of fractions)
    for (const fx of fractions) {
      if (fx > 0.18 && fx < 0.82 && fy > 0.18 && fy < 0.82) continue;
      const vector = match(
        reference,
        disturbed,
        Math.round((reference.width - 1) * fx),
        Math.round((reference.height - 1) * fy),
        0,
        0,
        settings.globalRadius,
      );
      if (vector) anchors.push({ ...vector, quadrant: (fx > 0.5 ? 1 : 0) + (fy > 0.5 ? 2 : 0) });
    }
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
  return { model: 'translation', dx, dy, residualRms, inliers: inliers.length, anchors: 12 };
}

export function analyzePair(reference: GrayFrame, disturbed: GrayFrame) {
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
  const registration = register(reference, disturbed);
  const baseX = Math.round(registration.dx),
    baseY = Math.round(registration.dy);
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
      const m = match(reference, disturbed, x, y, baseX, baseY, settings.localRadius);
      vectors.push({
        x,
        y,
        dx: m ? m.dx - registration.dx : null,
        dy: m ? m.dy - registration.dy : null,
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
    grid: { x0, y0, step, cols, rows },
    vectors,
    accepted: magnitudes.length,
    autoLimitPx: Math.max(0.15, magnitudes[Math.floor((magnitudes.length - 1) * 0.95)]),
  };
}
export type PairResult = ReturnType<typeof analyzePair>;
