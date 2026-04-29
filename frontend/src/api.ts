/** REST DTOs and helpers for talking to the Fresnel backend. */

export type MaskType = 'BINARY_AMPLITUDE' | 'GREYSCALE_PHASE';
export type Polarity = 'POSITIVE' | 'NEGATIVE';

export interface SingleZonePlateRequest {
  apertureDiameterMm: number;
  focalLengthMm: number;
  wavelengthNm: number;
  dpi: number;
  targetOffsetXmm?: number;
  targetOffsetYmm?: number;
  maskType?: MaskType;
  polarity?: Polarity;
}

export interface DesignMetrics {
  outerZoneWidthMicrons: number;
  printerPixelMicrons: number;
  pixelsPerOuterZone: number;
  estimatedTransmission: number;
  estimatedFirstOrderEfficiency: number;
  numberOfZones: number;
}

export interface Warning {
  code: string;
  message: string;
  severity: 'INFO' | 'WARNING' | 'ERROR';
}

export interface ValidationResponse {
  valid: boolean;
  warnings: Warning[];
  metrics: DesignMetrics;
}

const BASE = ''; // same-origin via Vite proxy or packaged jar

export async function validate(req: SingleZonePlateRequest): Promise<ValidationResponse> {
  const res = await fetch(`${BASE}/api/designs/validate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Validation failed: HTTP ${res.status}`);
  }
  return res.json();
}

export async function fetchPreviewPng(req: SingleZonePlateRequest): Promise<Blob> {
  const res = await fetch(`${BASE}/api/designs/preview.png`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'image/png' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Preview failed: HTTP ${res.status}`);
  }
  return res.blob();
}

export async function downloadExportPng(req: SingleZonePlateRequest, filename: string): Promise<void> {
  const res = await fetch(`${BASE}/api/designs/export.png`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'image/png' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Export failed: HTTP ${res.status}`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
