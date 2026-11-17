import type { Transform } from './registration';
import type { Grid, Light } from './solve';
export const pluginId = 'display-photometric-stereo';
export const defaults = { screenWidthMm: 530, screenHeightMm: 300, widthPx: 1920, heightPx: 1080,
  objectDistanceMm: 450, cameraOffsetXmm: 0, cameraOffsetYmm: 160, lightCount: 8,
  patchDiameterPercent: 12, settleDelayMs: 500, cameraGamma: 2.2 };
export type Geometry = typeof defaults;
export interface Pattern {
  id: string; role: 'DARK' | 'FLAT' | 'DIRECTIONAL_LIGHT'; screenXmm: number; screenYmm: number;
  diameterMm: number; direction: Omit<Light,'relativeIntensity'> | null; relativeIntensity: number; pixelSha256: string;
}
export interface Manifest {
  algorithmVersion: string; sequenceId: string; coordinateSystem: string; geometry: Geometry;
  conditionNumber: number; patterns: Pattern[];
}
export interface Source { patternId: string; sha256: string; name: string; width: number; height: number; lastModified: number; transform: Transform|null; clippedFraction: number }
export interface Result { width: number; height: number; grid: Grid; sources: Source[]; warnings: string[] }

export async function generator(path: string, geometry: Geometry) {
  const r = await fetch('/api/measurements/display-photometric-stereo/' + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(geometry),
  });
  if (!r.ok) throw new Error(await r.text() || 'Pattern generation failed');
  return r;
}

export function download(blob: Blob, name: string) {
  const link = document.createElement('a');
  const url = URL.createObjectURL(blob);
  link.href = url; link.download = name; link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 10000);
}
