import type { PairResult } from './bosDisplacement';

export type MapMode = 'magnitude' | 'horizontal' | 'vertical' | 'confidence' | 'vectors';
export function paintMap(
  canvas: HTMLCanvasElement,
  result: Pick<PairResult, 'width' | 'height' | 'autoLimitPx' | 'grid' | 'vectors'>,
  mode: MapMode,
  gain: number,
) {
  canvas.width = result.width;
  canvas.height = result.height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.fillStyle = '#25282d';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const { step } = result.grid;
  for (const v of result.vectors) {
    if (v.dx === null || v.dy === null) continue;
    if (mode === 'confidence') {
      const value = Math.round(255 * v.correlation);
      ctx.fillStyle = 'rgb(' + [value, value, value].join(',') + ')';
    } else if (mode === 'magnitude' || mode === 'vectors') {
      const t = Math.min(1, (Math.hypot(v.dx, v.dy) * gain) / result.autoLimitPx);
      ctx.fillStyle =
        'rgb(' +
        [Math.round(240 * t), Math.round(175 * Math.sqrt(t)), Math.round(80 * (1 - t))].join(',') +
        ')';
    } else {
      const t = Math.max(
        -1,
        Math.min(1, ((mode === 'horizontal' ? v.dx : v.dy) * gain) / result.autoLimitPx),
      );
      const fading = Math.round(245 * (1 - Math.abs(t)));
      ctx.fillStyle = 'rgb(' + [t > 0 ? 245 : fading, fading, t < 0 ? 245 : fading].join(',') + ')';
    }
    ctx.fillRect(Math.floor(v.x - step / 2), Math.floor(v.y - step / 2), step, step);
  }
  if (mode !== 'vectors') return;
  // Draw arrows only after every background tile, so neighboring tiles cannot cover them.
  ctx.beginPath();
  for (let row = 0; row < result.grid.rows; row += 2) {
    for (let column = 0; column < result.grid.cols; column += 2) {
      const v = result.vectors[row * result.grid.cols + column];
      if (v.dx === null || v.dy === null) continue;
      const magnitude = Math.hypot(v.dx, v.dy);
      const length = step * Math.min(1, (magnitude * gain) / result.autoLimitPx);
      if (length < 0.75) continue;
      const ux = v.dx / magnitude,
        uy = v.dy / magnitude;
      const x = v.x + length * ux,
        y = v.y + length * uy;
      const head = Math.min(3.5, length * 0.4);
      ctx.moveTo(v.x, v.y);
      ctx.lineTo(x, y);
      ctx.moveTo(x - head * ux + head * 0.5 * uy, y - head * uy - head * 0.5 * ux);
      ctx.lineTo(x, y);
      ctx.lineTo(x - head * ux - head * 0.5 * uy, y - head * uy + head * 0.5 * ux);
    }
  }
  ctx.lineJoin = 'round';
  ctx.lineWidth = 2.5;
  ctx.strokeStyle = '#18202b';
  ctx.stroke();
  ctx.lineWidth = 1.1;
  ctx.strokeStyle = '#ffffff';
  ctx.stroke();
}
