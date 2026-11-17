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

export interface BosTargetPreview {
  blob: Blob;
  targetId: string;
  semanticSha256: string;
  activeRegionHeader: string;
}

export async function fetchBosTargetManifest(
  request: BosTargetRequest,
): Promise<BosTargetManifest> {
  const response = await postResponse(
    '/api/measurements/background-oriented-schlieren/target/manifest',
    request,
    'application/json',
  );
  return response.json() as Promise<BosTargetManifest>;
}

export async function fetchBosTargetPreviewPng(
  request: BosTargetRequest,
): Promise<BosTargetPreview> {
  const response = await postResponse(
    '/api/measurements/background-oriented-schlieren/target/preview.png',
    request,
    'image/png',
  );
  const targetId = requiredHeader(response, 'X-Fresnel-Target-Id');
  const semanticSha256 = requiredHeader(response, 'X-Fresnel-Target-SHA256');
  const activeRegionHeader = requiredHeader(response, 'X-Fresnel-Active-Region');
  if (!/^bos-[0-9a-f]{12}$/.test(targetId)) {
    throw new Error('BOS preview returned an invalid target identity.');
  }
  if (!/^[0-9a-f]{64}$/.test(semanticSha256)) {
    throw new Error('BOS preview returned an invalid semantic SHA-256.');
  }
  if (!/^\d+,\d+,\d+,\d+$/.test(activeRegionHeader)) {
    throw new Error('BOS preview returned an invalid active-region header.');
  }
  return {
    blob: await response.blob(),
    targetId,
    semanticSha256,
    activeRegionHeader,
  };
}

export async function downloadBosTargetPng(
  request: BosTargetRequest,
  filename: string,
): Promise<void> {
  const response = await postResponse(
    '/api/measurements/background-oriented-schlieren/target/export.png',
    request,
    'image/png',
  );
  downloadBlob(await response.blob(), filename);
}

export function downloadBosTargetManifest(
  manifest: BosTargetManifest,
  filename: string,
): void {
  const json = `${JSON.stringify(manifest, null, 2)}\n`;
  downloadBlob(new Blob([json], { type: 'application/json' }), filename);
}

async function postResponse(
  path: string,
  request: BosTargetRequest,
  expectedMediaType: string,
): Promise<Response> {
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
  return response;
}

function requiredHeader(response: Response, name: string): string {
  const value = response.headers.get(name);
  if (!value) throw new Error(`BOS target response is missing ${name}.`);
  return value;
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
