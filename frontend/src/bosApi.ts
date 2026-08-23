const BASE = '';

export interface BosTargetParameters {
  widthPx: number;
  heightPx: number;
  intendedDpi: number;
  patternSeed: number;
  dotDiameterPx: number;
  targetFillRatio: number;
  minimumDotSpacingPx: number;
  borderPx: number;
  fiducialsEnabled: boolean;
  invertPattern: boolean;
}

export interface BosRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface BosFiducial {
  id: string;
  shape: 'SOLID_SQUARE' | 'RING' | 'L_SHAPE' | 'CROSS';
  region: BosRegion;
}

export interface BosTargetManifest {
  algorithmVersion: string;
  targetId: string;
  semanticSha256: string;
  widthPx: number;
  heightPx: number;
  intendedDpi: number;
  intendedWidthMm: number;
  intendedHeightMm: number;
  patternSeed: number;
  dotDiameterPx: number;
  requestedFillRatio: number;
  actualFillRatio: number;
  minimumDotSpacingPx: number;
  cellPitchPx: number;
  dotCount: number;
  borderPx: number;
  fiducialsEnabled: boolean;
  invertPattern: boolean;
  activeRegion: BosRegion;
  fiducials: BosFiducial[];
}

export interface BosTargetPreview {
  objectUrl: string;
  targetId: string;
  semanticSha256: string;
}

export const DEFAULT_BOS_TARGET_PARAMETERS: BosTargetParameters = {
  widthPx: 1600,
  heightPx: 1000,
  intendedDpi: 150,
  patternSeed: 20260823,
  dotDiameterPx: 7,
  targetFillRatio: 0.12,
  minimumDotSpacingPx: 3,
  borderPx: 64,
  fiducialsEnabled: true,
  invertPattern: false,
};

const ENDPOINT = '/api/measurements/background-oriented-schlieren/target';

export async function fetchBosTargetManifest(
  parameters: BosTargetParameters,
  signal?: AbortSignal,
): Promise<BosTargetManifest> {
  const response = await post(`${ENDPOINT}/manifest`, parameters, 'application/json', signal);
  return response.json() as Promise<BosTargetManifest>;
}

export async function fetchBosTargetPreview(
  parameters: BosTargetParameters,
  signal?: AbortSignal,
): Promise<BosTargetPreview> {
  const response = await post(`${ENDPOINT}/preview.png`, parameters, 'image/png', signal);
  const blob = await response.blob();
  return {
    objectUrl: URL.createObjectURL(blob),
    targetId: response.headers.get('X-Fresnel-Target-Id') ?? 'bos-target',
    semanticSha256: response.headers.get('X-Fresnel-Target-SHA256') ?? '',
  };
}

export async function downloadBosTarget(
  parameters: BosTargetParameters,
  fallbackTargetId = 'bos-target',
): Promise<void> {
  const response = await post(`${ENDPOINT}/export.png`, parameters, 'image/png');
  const blob = await response.blob();
  const targetId = response.headers.get('X-Fresnel-Target-Id') ?? fallbackTargetId;
  downloadBlob(blob, `${targetId}.png`);
}

async function post(
  url: string,
  parameters: BosTargetParameters,
  accept: string,
  signal?: AbortSignal,
): Promise<Response> {
  const response = await fetch(`${BASE}${url}`, {
    method: 'POST',
    headers: {
      Accept: accept,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(parameters),
    signal,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `BOS target request failed (HTTP ${response.status})`);
  }
  return response;
}

function downloadBlob(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}
