import type { PairResult } from './bosDisplacement';

export type MapMode = 'magnitude' | 'horizontal' | 'vertical' | 'confidence';
export function paintMap(canvas: HTMLCanvasElement, result: PairResult, mode: MapMode, gain: number) {
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
    } else if (mode === 'magnitude') {
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
}
