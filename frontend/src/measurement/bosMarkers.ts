import type { BosFiducialShape, BosTargetManifest } from '../bosTargetApi';
import type { GrayFrame } from './bosDisplacement';
import type { Box, Region } from './bosReferenceQuality';

export type TargetGeometry = Pick<
  BosTargetManifest,
  'widthPx' | 'heightPx' | 'activeRegion' | 'fiducials' | 'invertPattern'
>;
type Point = { x: number; y: number };
type Match = Point & { shape: BosFiducialShape; score: number; size: number };
const shapes: BosFiducialShape[] = ['SOLID_SQUARE', 'RING', 'CROSS', 'L_SHAPE'];

function pattern(shape: BosFiducialShape, x: number, y: number) {
  const r = Math.hypot(x - 0.5, y - 0.5);
  switch (shape) {
    case 'SOLID_SQUARE':
      return true;
    case 'RING':
      return r <= 0.5 && r >= 0.25;
    case 'L_SHAPE':
      return x < 0.24 || y > 0.76;
    case 'CROSS':
      return Math.abs(x - 0.5) < 0.1 || Math.abs(y - 0.5) < 0.1;
  }
}

function shapeMatch(
  frame: GrayFrame,
  box: Box,
  threshold: number,
  dark: boolean,
  shape: BosFiducialShape,
  turn: number,
) {
  let intersection = 0,
    union = 0;
  for (let j = 0; j < 14; j++)
    for (let i = 0; i < 14; i++) {
      let u = (i + 0.5) / 14,
        v = (j + 0.5) / 14;
      const x = box.x + Math.floor(u * box.width),
        y = box.y + Math.floor(v * box.height);
      const ink = frame.pixels[y * frame.width + x] <= threshold === dark;
      for (let k = 0; k < turn; k++) [u, v] = [v, 1 - u];
      const expected = pattern(shape, u, v);
      if (ink && expected) intersection++;
      if (ink || expected) union++;
    }
  return intersection / Math.max(1, union);
}

// Map the four marker centers to the image quadrilateral, including perspective.
function projection(p: Match[]) {
  const [a, b, c, d] = p;
  const bx = b.x - c.x,
    by = b.y - c.y,
    dx = d.x - c.x,
    dy = d.y - c.y;
  const sx = a.x - b.x + c.x - d.x,
    sy = a.y - b.y + c.y - d.y;
  const det = bx * dy - dx * by;
  if (Math.abs(det) < 1e-5) return null;
  const g = (sx * dy - dx * sy) / det,
    h = (bx * sy - sx * by) / det;
  return (u: number, v: number): Point => ({
    x: (a.x + (b.x * (1 + g) - a.x) * u + (d.x * (1 + h) - a.x) * v) / (1 + g * u + h * v),
    y: (a.y + (b.y * (1 + g) - a.y) * u + (d.y * (1 + h) - a.y) * v) / (1 + g * u + h * v),
  });
}
const cross = (a: Point, b: Point, p: Point) => (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);

function insideRectangle(polygon: Point[]): Region {
  const xs = polygon.map((p) => p.x),
    ys = polygon.map((p) => p.y);
  const left = Math.min(...xs),
    right = Math.max(...xs),
    top = Math.min(...ys),
    bottom = Math.max(...ys);
  const cx = xs.reduce((a, b) => a + b) / 4,
    cy = ys.reduce((a, b) => a + b) / 4;
  const hw = Math.min(cx - left, right - cx),
    hh = Math.min(cy - top, bottom - cy);
  let lo = 0,
    hi = 1;
  for (let k = 0; k < 20; k++) {
    const f = (lo + hi) / 2;
    const corners = [
      { x: cx - hw * f, y: cy - hh * f },
      { x: cx + hw * f, y: cy - hh * f },
      { x: cx + hw * f, y: cy + hh * f },
      { x: cx - hw * f, y: cy + hh * f },
    ];
    if (corners.every((p) => polygon.every((a, i) => cross(a, polygon[(i + 1) % 4], p) >= -1e-9))) lo = f;
    else hi = f;
  }
  return { x: cx - hw * lo, y: cy - hh * lo, width: 2 * hw * lo, height: 2 * hh * lo };
}

export function detectMarkers(frame: GrayFrame, geometry: TargetGeometry, boxes: Box[], threshold: number) {
  if (geometry.fiducials.length !== 4) return null;
  const source = shapes.map((shape) => geometry.fiducials.find((f) => f.shape === shape)?.region);
  if (source.some((s) => !s)) return null;
  const tl = source[0]!,
    tr = source[1]!,
    bl = source[3]!;
  const x0 = tl.x + tl.width / 2,
    y0 = tl.y + tl.height / 2;
  const sourceSpanX = tr.x + tr.width / 2 - x0,
    sourceSpanY = bl.y + bl.height / 2 - y0;
  if (sourceSpanX <= 0 || sourceSpanY <= 0) return null;
  const possible = boxes.filter(
    (b) =>
      Math.min(b.width, b.height) >= 5 &&
      Math.max(b.width, b.height) <= Math.max(frame.width, frame.height) * 0.16 &&
      Math.min(b.width, b.height) / Math.max(b.width, b.height) >= 0.62,
  );
  // Bound template work even on highly textured scenes. Fiducials are larger than target dots.
  possible.sort((a, b) => b.area - a.area);
  const candidates = possible.slice(0, 400);
  let best: { matches: Match[]; polygon: Point[]; region: Region; meanAgreement: number } | null = null;
  for (let turn = 0; turn < 4; turn++) {
    const byShape = shapes.map((shape) =>
      candidates
        .map((b) => ({
          shape,
          score: shapeMatch(frame, b, threshold, !geometry.invertPattern, shape, turn),
          x: (b.x + b.width / 2) / frame.width,
          y: (b.y + b.height / 2) / frame.height,
          size: Math.sqrt(b.width * b.height),
        }))
        .filter((m) => m.score >= (shape === 'SOLID_SQUARE' ? 0.87 : 0.65))
        .sort((a, b) => b.score - a.score)
        .slice(0, 8),
    );
    for (const a of byShape[0])
      for (const b of byShape[1])
        for (const c of byShape[2])
          for (const d of byShape[3]) {
            const quad = [a, b, c, d],
              area =
                quad.reduce((sum, p, i) => sum + p.x * quad[(i + 1) % 4].y - p.y * quad[(i + 1) % 4].x, 0) /
                2;
            if (area < 0.04 || quad.some((p, i) => cross(p, quad[(i + 1) % 4], quad[(i + 2) % 4]) <= 0))
              continue;
            const px = (p: Point, q: Point) =>
              Math.hypot((p.x - q.x) * frame.width, (p.y - q.y) * frame.height);
            const direction =
              (b.x - a.x) * frame.width * Math.cos((turn * Math.PI) / 2) +
              (b.y - a.y) * frame.height * Math.sin((turn * Math.PI) / 2);
            if (
              direction < 0.78 * px(a, b) ||
              Math.min(px(a, b), px(d, c)) / Math.max(px(a, b), px(d, c)) < 0.6 ||
              Math.min(px(a, d), px(b, c)) / Math.max(px(a, d), px(b, c)) < 0.6
            )
              continue;
            const predicted = tl.width * Math.sqrt((px(a, b) * px(a, d)) / (sourceSpanX * sourceSpanY));
            if (quad.some((p) => p.size < predicted * 0.62 || p.size > predicted * 1.55)) continue;
            const meanAgreement = quad.reduce((sum, p) => sum + p.score, 0) / 4;
            if (meanAgreement < 0.78 || (best && best.meanAgreement >= meanAgreement)) continue;
            const project = projection(quad);
            if (!project) continue;
            const roi = geometry.activeRegion;
            const u = (roi.x - x0) / sourceSpanX,
              v = (roi.y - y0) / sourceSpanY;
            const w = roi.width / sourceSpanX,
              h = roi.height / sourceSpanY;
            const polygon = [project(u, v), project(u + w, v), project(u + w, v + h), project(u, v + h)];
            if (polygon.some((p) => ![p.x, p.y].every((n) => Number.isFinite(n) && n >= 0 && n <= 1)))
              continue;
            best = { matches: quad, polygon, region: insideRectangle(polygon), meanAgreement };
          }
  }
  return best;
}
