const BASE = '';

export interface BackgroundOrientedSchlierenRequest {
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

export async function fetchBackgroundOrientedSchlierenPreviewPng(
  request: BackgroundOrientedSchlierenRequest,
): Promise<Blob> {
  return postForBlob('/api/designs/background-oriented-schlieren/preview.png', request);
}

export async function downloadBackgroundOrientedSchlierenPng(
  request: BackgroundOrientedSchlierenRequest,
  filename = 'fresnel-background-oriented-schlieren.png',
): Promise<void> {
  const blob = await postForBlob(
    '/api/designs/background-oriented-schlieren/export.png',
    request,
  );
  downloadBlob(blob, filename);
}

async function postForBlob(
  path: string,
  request: BackgroundOrientedSchlierenRequest,
): Promise<Blob> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'image/png',
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `HTTP ${response.status}`);
  }
  return response.blob();
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
