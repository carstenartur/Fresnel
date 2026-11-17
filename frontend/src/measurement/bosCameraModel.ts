import type { GrayFrame } from './bosDisplacement';

export type CameraModel = 'translation' | 'affine' | 'homography';
export type Matrix = [number, number, number, number, number, number, number, number, number];
type Anchor = { x: number; y: number; dx: number; dy: number; quadrant: number };
export const cameraLimits = {
  minimumInliers: 9,
  maximumCornerMotion: 18,
  maximumHeldOutError: 1.35,
} as const;

export function project(m: Matrix, x: number, y: number) {
  const w = m[6] * x + m[7] * y + m[8];
  return { x: (m[0] * x + m[1] * y + m[2]) / w, y: (m[3] * x + m[4] * y + m[5]) / w };
}

// Least squares in normalized analysis coordinates. Pivoting rejects deficient geometry.
function fit(anchors: Anchor[], model: Exclude<CameraModel, 'translation'>, scale: number): Matrix | null {
  const n = model === 'affine' ? 6 : 8;
  const equations = Array.from({ length: n }, () => new Float64Array(n + 1));
  function add(row: number[], observed: number) {
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) equations[i][j] += row[i] * row[j];
      equations[i][n] += row[i] * observed;
    }
  }
  for (const a of anchors) {
    const x = a.x / scale,
      y = a.y / scale,
      u = (a.x + a.dx) / scale,
      v = (a.y + a.dy) / scale;
    add([x, y, 1, 0, 0, 0, -x * u, -y * u], u);
    add([0, 0, 0, x, y, 1, -x * v, -y * v], v);
  }
  for (let i = 0; i < n; i++) {
    let pivot = i;
    for (let k = i + 1; k < n; k++) if (Math.abs(equations[k][i]) > Math.abs(equations[pivot][i])) pivot = k;
    if (Math.abs(equations[pivot][i]) < 1e-9) return null;
    [equations[i], equations[pivot]] = [equations[pivot], equations[i]];
    const factor = equations[i][i];
    for (let j = i; j <= n; j++) equations[i][j] /= factor;
    for (let k = 0; k < n; k++) {
      if (k === i) continue;
      const multiple = equations[k][i];
      for (let j = i; j <= n; j++) equations[k][j] -= multiple * equations[i][j];
    }
  }
  const [a, b, c, d, e, f, g = 0, h = 0] = equations.map((row) => row[n]);
  return [a, b, c * scale, d, e, f * scale, g / scale, h / scale, 1];
}

function error(matrix: Matrix, anchor: Anchor) {
  const point = project(matrix, anchor.x, anchor.y);
  return Math.hypot(point.x - anchor.x - anchor.dx, point.y - anchor.y - anchor.dy);
}

function bounded(matrix: Matrix, width: number, height: number) {
  // A positive denominator everywhere on the rectangle precludes a projective horizon.
  return [0, width - 1].every((x) =>
    [0, height - 1].every((y) => {
      const p = project(matrix, x, y);
      return (
        matrix[6] * x + matrix[7] * y + matrix[8] > 0.5 &&
        Math.hypot(p.x - x, p.y - y) <= cameraLimits.maximumCornerMotion
      );
    }),
  );
}

export function fitCamera(
  anchors: Anchor[],
  model: Exclude<CameraModel, 'translation'>,
  width: number,
  height: number,
  tolerance: number,
) {
  const failure =
    'Registration failed. The outer dot field does not support the selected camera model. Keep all four sides sharp and undisturbed and reduce camera motion.';
  const min = cameraLimits.minimumInliers;
  const scale = Math.max(width, height) - 1;
  let best: Anchor[] = [],
    lowestError = Infinity;
  const sample: Anchor[] = [];
  // With twelve possible anchors there are at most 220 nine-anchor fits. Every admissible
  // consensus has at least one uncontaminated seed, irrespective of anchor order.
  function visit(start: number) {
    if (sample.length < min) {
      for (let i = start; i <= anchors.length - (min - sample.length); i++) {
        sample.push(anchors[i]);
        visit(i + 1);
        sample.pop();
      }
      return;
    }
    const candidate = fit(sample, model, scale);
    if (!candidate || !bounded(candidate, width, height)) return;
    const inliers = anchors.filter((a) => error(candidate, a) <= tolerance);
    if (inliers.length < Math.max(min, best.length) || new Set(inliers.map((a) => a.quadrant)).size < 4)
      return;
    const score = inliers.reduce((s, a) => s + error(candidate, a) ** 2, 0);
    if (inliers.length > best.length || score < lowestError) {
      best = inliers;
      lowestError = score;
    }
  }
  visit(0);
  if (best.length < min) throw new Error(failure);
  const matrix = fit(best, model, scale);
  if (!matrix || !bounded(matrix, width, height)) throw new Error(failure);
  const errors = best.map((a) => error(matrix, a));
  if (Math.max(...errors) > tolerance) throw new Error(failure);
  const maxHeldOutError = Math.max(
    ...best.map((a) => {
      const holdOut = fit(
        best.filter((other) => other !== a),
        model,
        scale,
      );
      return holdOut ? error(holdOut, a) : Infinity;
    }),
  );
  if (maxHeldOutError > cameraLimits.maximumHeldOutError) throw new Error(failure);
  const cx = (width - 1) / 2,
    cy = (height - 1) / 2,
    center = project(matrix, cx, cy);
  return {
    model,
    matrix,
    dx: center.x - cx,
    dy: center.y - cy,
    maxHeldOutError,
    residualRms: Math.sqrt(errors.reduce((sum, e) => sum + e * e, 0) / errors.length),
    inliers: best.length,
    anchors: 12,
  };
}

export function validFootprint(
  matrix: Matrix,
  x: number,
  y: number,
  radius: number,
  width: number,
  height: number,
) {
  return [x - radius, x + radius].every((u) =>
    [y - radius, y + radius].every((v) => {
      const p = project(matrix, u, v);
      return p.x >= 0 && p.y >= 0 && p.x < width - 1 && p.y < height - 1;
    }),
  );
}

export function stabilize(frame: GrayFrame, matrix: Matrix): GrayFrame {
  const { width, height } = frame,
    source = frame.pixels,
    pixels = new Uint8Array(source.length);
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const p = project(matrix, x, y),
        ix = Math.floor(p.x),
        iy = Math.floor(p.y);
      if (ix < 0 || iy < 0 || ix >= width - 1 || iy >= height - 1) continue;
      const fx = p.x - ix,
        fy = p.y - iy,
        i = iy * width + ix;
      pixels[y * width + x] = Math.round(
        (1 - fy) * ((1 - fx) * source[i] + fx * source[i + 1]) +
          fy * ((1 - fx) * source[i + width] + fx * source[i + width + 1]),
      );
    }
  return { width, height, pixels };
}
