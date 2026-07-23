const BASE = '';

export type LineOrientation = 'VERTICAL' | 'HORIZONTAL';
export type GratingProgression =
  | 'LINEAR_PITCH'
  | 'LINEAR_SPATIAL_FREQUENCY'
  | 'LOGARITHMIC_PITCH';
export type ProgressionDirection = 'NORMAL' | 'REVERSED';
export type GratingPolarity = 'POSITIVE' | 'NEGATIVE';
export type AxisQuantity = 'PITCH_UM' | 'LINES_PER_MM' | 'DEVICE_DOTS_PER_PERIOD';
export type DeviceAxis = 'X' | 'Y';
export type PclCompression = 'NONE' | 'TIFF';
export type PclPageOrientation = 'PORTRAIT' | 'LANDSCAPE';

export interface VariableLineGratingRequest {
  widthMm: number;
  heightMm: number;
  lineOrientation: LineOrientation;
  startPitchUm: number;
  endPitchUm: number;
  progression: GratingProgression;
  progressionDirection: ProgressionDirection;
  dutyCycle: number;
  phaseOffsetCycles: number;
  polarity: GratingPolarity;
  marginMm: number;
  annotationSizeMm: number;
  showAxis: boolean;
  axisQuantity: AxisQuantity;
  tickCount: number;
  showReferenceBands: boolean;
  referenceBandSizeMm: number;
  dpi: number;
}

export interface ThresholdCrossing {
  dotsPerPeriod: number;
  crossed: boolean;
  positionMm?: number;
  normalizedPosition?: number;
  pitchUm?: number;
}

export interface VariableLineGratingInfo {
  lineOrientation: LineOrientation;
  testedDeviceAxis: DeviceAxis;
  selectedAxisDpi: number;
  minPitchUm: number;
  maxPitchUm: number;
  minimumOpaqueFeatureUm: number;
  minimumClearFeatureUm: number;
  minDotsPerPeriod: number;
  maxDotsPerPeriod: number;
  minDotsPerOpaqueFeature: number;
  minDotsPerClearFeature: number;
  nominalCycleCount: number;
  thresholdCrossings: ThresholdCrossing[];
}

export interface PrinterRasterProfile {
  id: string;
  version: number;
  dialect: 'PCL_5E';
  dpiX: number;
  dpiY: number;
  pageWidthDots: number;
  pageHeightDots: number;
  printableOriginXDots: number;
  printableOriginYDots: number;
  printableWidthDots: number;
  printableHeightDots: number;
  mediaSize: string;
  pageOrientation: PclPageOrientation;
  pageXAxisMapsTo: DeviceAxis;
  pageYAxisMapsTo: DeviceAxis;
  compressionModes: PclCompression[];
}

export interface PrinterCalibrationResult {
  printerModel: string;
  printerProfileId: string;
  printerProfileVersion: number;
  mediumDescription: string;
  qualityMode: string;
  nominalDpiX: number;
  nominalDpiY: number;
  pageOrientation: PclPageOrientation;
  pageXAxisMapsTo: DeviceAxis;
  pageYAxisMapsTo: DeviceAxis;
  lineOrientation: LineOrientation;
  testedDeviceAxis: DeviceAxis;
  observedDegradationPositionMm?: number;
  firstResolvedPitchUm?: number;
  minimumUsefulFeatureWidthUm?: number;
  firstResolvedLinesPerMm?: number;
  effectiveDpi?: number;
  observationNotes: string;
  measurementAttachmentReference: string;
  measuredAt: string;
}

export async function fetchPrinterRasterProfiles(): Promise<PrinterRasterProfile[]> {
  const response = await fetch(`${BASE}/api/designs/variable-line-grating/printer-profiles`, {
    headers: { Accept: 'application/json' },
  });
  return parseJson(response);
}

export async function variableLineGratingInfo(
  request: VariableLineGratingRequest,
  printerProfileId?: string,
): Promise<VariableLineGratingInfo> {
  const query = printerProfileId
    ? `?printerProfileId=${encodeURIComponent(printerProfileId)}`
    : '';
  return postJson(`/api/designs/variable-line-grating/info${query}`, request);
}

export async function fetchVariableLineGratingPreviewPng(
  request: VariableLineGratingRequest,
): Promise<Blob> {
  return postBlob('/api/designs/variable-line-grating/preview.png', request, 'image/png');
}

export async function downloadVariableLineGratingPng(
  request: VariableLineGratingRequest,
  filename: string,
): Promise<void> {
  downloadBlob(
    await postBlob('/api/designs/variable-line-grating/export.png', request, 'image/png'),
    filename,
  );
}

export async function downloadVariableLineGratingSvg(
  request: VariableLineGratingRequest,
  filename: string,
): Promise<void> {
  downloadBlob(
    await postBlob('/api/designs/variable-line-grating/export.svg', request, 'image/svg+xml'),
    filename,
  );
}

export async function downloadVariableLineGratingPdf(
  request: VariableLineGratingRequest,
  filename: string,
): Promise<void> {
  downloadBlob(
    await postBlob('/api/designs/variable-line-grating/export.pdf?sheet=A4', request, 'application/pdf'),
    filename,
  );
}

export async function downloadVariableLineGratingPcl(
  request: VariableLineGratingRequest,
  printerProfileId: string,
  compression: PclCompression,
  filename: string,
): Promise<void> {
  const query = new URLSearchParams({ printerProfileId, compression });
  downloadBlob(
    await postBlob(
      `/api/designs/variable-line-grating/export.pcl?${query.toString()}`,
      request,
      'application/vnd.hp-pcl',
    ),
    filename,
  );
}

export async function downloadPrinterCalibrationResult(
  result: PrinterCalibrationResult,
  filename: string,
): Promise<void> {
  downloadBlob(
    await postBlob(
      '/api/designs/variable-line-grating/calibration-results/export.json',
      result,
      'application/json',
    ),
    filename,
  );
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  return parseJson(response);
}

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) throw new Error(await responseError(response));
  return response.json() as Promise<T>;
}

async function postBlob(path: string, body: unknown, accept: string): Promise<Blob> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: accept },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(await responseError(response));
  return response.blob();
}

async function responseError(response: Response): Promise<string> {
  const text = await response.text();
  return text || `HTTP ${response.status}`;
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
