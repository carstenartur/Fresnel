const BASE = '';

export interface BosTargetRequest {
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

export interface BosTargetRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

export type BosFiducialShape = 'SOLID_SQUARE' | 'RING' | 'L_SHAPE' | 'CROSS';

export interface BosTargetFiducial {
  id: string;
  shape: BosFiducialShape;
  region: BosTargetRegion;
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
  activeRegion: BosTargetRegion;
  fiducials: BosTargetFiducial[];
}

export async function fetchBosTargetManifest(
  request: BosTargetRequest,
): Promise<BosTargetManifest> {
  return postJson('/api/measurements/background-oriented-schlieren/target/manifest', request);
}

export async function fetchBosTargetPreviewPng(
  request: BosTargetRequest,
): Promise<Blob> {
  return postBlob(
    '/api/measurements/background-oriented-schlieren/target/preview.png',
    request,
    'image/png',
  );
}

export async function downloadBosTargetPng(
  request: BosTargetRequest,
  filename: string,
): Promise<void> {
  const blob = await postBlob(
    '/api/measurements/background-oriented-schlieren/target/export.png',
    request,
    'image/png',
  );
  downloadBlob(blob, filename);
}

export function downloadBosTargetManifest(
  manifest: BosTargetManifest,
  filename: string,
): void {
  const json = `${JSON.stringify(manifest, null, 2)}\n`;
  downloadBlob(new Blob([json], { type: 'application/json' }), filename);
}

async function postJson<T>(path: string, request: BosTargetRequest): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw new Error(await responseError(response));
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().startsWith('application/json')) {
    throw new Error(`Expected JSON from ${path}, received ${contentType || 'no content type'}.`);
  }
  return response.json() as Promise<T>;
}

async function postBlob(
  path: string,
  request: BosTargetRequest,
  expectedMediaType: string,
): Promise<Blob> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      Accept: expectedMediaType,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw new Error(await responseError(response));
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().startsWith(expectedMediaType.toLowerCase())) {
    throw new Error(
      `Expected ${expectedMediaType} from ${path}, received ${contentType || 'no content type'}.`,
    );
  }
  return response.blob();
}

async function responseError(response: Response): Promise<string> {
  const text = await response.text();
  return text || `BOS target request failed (HTTP ${response.status}).`;
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
