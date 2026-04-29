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

export interface ChromaticShift { wavelengthNm: number; focalLengthMm: number; }
export interface DefocusEntry { wallDistanceMm: number; blurDiameterMm: number; }

export interface DesignMetrics {
  outerZoneWidthMicrons: number;
  printerPixelMicrons: number;
  pixelsPerOuterZone: number;
  estimatedTransmission: number;
  estimatedFirstOrderEfficiency: number;
  numberOfZones: number;
  chromaticShifts?: ChromaticShift[];
  defocusBlurs?: DefocusEntry[];
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

// --- Hex macro cell (Use Case B) ---
export interface HexMacroCellRequest {
  macroRadiusMm: number;
  subDiameterMm: number;
  subPitchMm: number;
  focalLengthMm: number;
  targetOffsetXmm?: number;
  targetOffsetYmm?: number;
  wavelengthNm: number;
  dpi: number;
  maskType?: MaskType;
  polarity?: Polarity;
}

// --- Window foil (Use Case C) ---
export interface CellSpecDto {
  focalLengthMm: number;
  targetOffsetXmm?: number;
  targetOffsetYmm?: number;
}
export interface WindowFoilRequest {
  sheetWidthMm: number;
  sheetHeightMm: number;
  macroRadiusMm: number;
  subDiameterMm: number;
  subPitchMm: number;
  wavelengthNm: number;
  dpi: number;
  maskType?: MaskType;
  polarity?: Polarity;
  cellSpecs?: CellSpecDto[];
  drawCropMarks?: boolean;
}

// --- Multi-focus (Mode 4) ---
export interface FocusPointDto { xMm: number; yMm: number; zMm: number; }
export interface MultiFocusRequest {
  apertureDiameterMm: number;
  focusPoints: FocusPointDto[];
  wavelengthNm: number;
  dpi: number;
  maskType?: MaskType;
  polarity?: Polarity;
}

// --- RGB (Mode 5) ---
export interface RgbZonePlateRequest {
  base: SingleZonePlateRequest;
  redNm: number;
  greenNm: number;
  blueNm: number;
}

// --- Hologram (Use Case D) ---
export type HologramOutputType = 'BINARY_PHASE' | 'GREYSCALE_PHASE';
export interface HologramRequest {
  targetImageBase64: string;
  sidePx: number;
  iterations: number;
  outputType?: HologramOutputType;
  dpi: number;
}

const BASE = ''; // same-origin via Vite proxy or packaged jar

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json();
}

async function postBlob(url: string, body: unknown, accept = 'image/png'): Promise<Blob> {
  const res = await fetch(`${BASE}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: accept },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.blob();
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// --- Single zone plate ---

export async function validate(req: SingleZonePlateRequest): Promise<ValidationResponse> {
  return postJson('/api/designs/validate', req);
}
export async function fetchPreviewPng(req: SingleZonePlateRequest): Promise<Blob> {
  return postBlob('/api/designs/preview.png', req);
}
export async function downloadExportPng(req: SingleZonePlateRequest, filename: string): Promise<void> {
  const blob = await postBlob('/api/designs/export.png', req);
  downloadBlob(blob, filename);
}
export async function downloadExportSvg(req: SingleZonePlateRequest, filename: string): Promise<void> {
  const blob = await postBlob('/api/designs/export.svg', req, 'image/svg+xml');
  downloadBlob(blob, filename);
}
export async function downloadExportPdf(req: SingleZonePlateRequest, sheet: string, filename: string): Promise<void> {
  const blob = await postBlob(`/api/designs/export.pdf?sheet=${sheet}`, req, 'application/pdf');
  downloadBlob(blob, filename);
}

// --- Hex macro cell ---

export interface HexInfo { subElements: number; imageSidePx: number; }
export async function hexInfo(req: HexMacroCellRequest): Promise<HexInfo> {
  return postJson('/api/designs/hex/info', req);
}
export async function fetchHexPreviewPng(req: HexMacroCellRequest): Promise<Blob> {
  return postBlob('/api/designs/hex/preview.png', req);
}
export async function downloadHexPng(req: HexMacroCellRequest, filename: string): Promise<void> {
  const blob = await postBlob('/api/designs/hex/export.png', req);
  downloadBlob(blob, filename);
}
export async function downloadHexPdf(req: HexMacroCellRequest, sheet: string, filename: string): Promise<void> {
  const blob = await postBlob(`/api/designs/hex/export.pdf?sheet=${sheet}`, req, 'application/pdf');
  downloadBlob(blob, filename);
}

// --- Window foil ---

export interface FoilInfo { cells: number; imageWidthPx: number; imageHeightPx: number; }
export async function foilInfo(req: WindowFoilRequest): Promise<FoilInfo> {
  return postJson('/api/designs/foil/info', req);
}
export async function fetchFoilPreviewPng(req: WindowFoilRequest): Promise<Blob> {
  return postBlob('/api/designs/foil/preview.png', req);
}
export async function downloadFoilPdf(req: WindowFoilRequest, sheet: string, filename: string): Promise<void> {
  const blob = await postBlob(`/api/designs/foil/export.pdf?sheet=${sheet}`, req, 'application/pdf');
  downloadBlob(blob, filename);
}

// --- Multi-focus ---

export async function fetchMultiFocusPreviewPng(req: MultiFocusRequest): Promise<Blob> {
  return postBlob('/api/designs/multifocus/preview.png', req);
}
export async function downloadMultiFocusPng(req: MultiFocusRequest, filename: string): Promise<void> {
  const blob = await postBlob('/api/designs/multifocus/export.png', req);
  downloadBlob(blob, filename);
}

// --- RGB ---

export async function fetchRgbPreviewPng(req: RgbZonePlateRequest): Promise<Blob> {
  return postBlob('/api/designs/rgb/preview.png', req);
}
export async function downloadRgbPng(req: RgbZonePlateRequest, filename: string): Promise<void> {
  const blob = await postBlob('/api/designs/rgb/export.png', req);
  downloadBlob(blob, filename);
}

// --- Hologram ---

export async function synthesizeHologramPng(req: HologramRequest): Promise<Blob> {
  return postBlob('/api/holograms/synthesize.png', req);
}
export async function reconstructHologramPng(req: HologramRequest, previewOnly = false): Promise<Blob> {
  return postBlob(`/api/holograms/reconstruct.png?previewOnly=${previewOnly}`, req);
}
export async function downloadHologramPng(req: HologramRequest, filename: string): Promise<void> {
  const blob = await synthesizeHologramPng(req);
  downloadBlob(blob, filename);
}

/** Read a File / Blob as a base64 string (no data: URL prefix). */
export function fileToBase64(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => {
      const result = String(r.result);
      const comma = result.indexOf(',');
      resolve(comma >= 0 ? result.substring(comma + 1) : result);
    };
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

// --- Async render-jobs (SSE) ---

export interface JobStatus {
  jobId: string;
  label: string;
  state: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  progress: number;
  message: string;
  error: string;
}

export async function submitSingleJob(req: SingleZonePlateRequest): Promise<string> {
  const r = await postJson<{ jobId: string }>('/api/jobs/single', req);
  return r.jobId;
}
export async function submitHexJob(req: HexMacroCellRequest): Promise<string> {
  const r = await postJson<{ jobId: string }>('/api/jobs/hex', req);
  return r.jobId;
}
export async function submitFoilJob(req: WindowFoilRequest): Promise<string> {
  const r = await postJson<{ jobId: string }>('/api/jobs/foil', req);
  return r.jobId;
}

/** Subscribe to job progress over Server-Sent Events. Returns cleanup function. */
export function subscribeJobEvents(jobId: string, onUpdate: (s: JobStatus) => void): () => void {
  const es = new EventSource(`${BASE}/api/jobs/${jobId}/events`);
  es.addEventListener('progress', (ev) => {
    try { onUpdate(JSON.parse((ev as MessageEvent).data)); } catch { /* ignore */ }
  });
  es.onerror = () => es.close();
  return () => es.close();
}

export function jobResultUrl(jobId: string): string {
  return `${BASE}/api/jobs/${jobId}/result.png`;
}
