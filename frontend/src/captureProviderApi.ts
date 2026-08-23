const BASE = '';

export type CaptureProviderHealthState =
  | 'NOT_CONFIGURED'
  | 'PAIRING'
  | 'CONNECTED'
  | 'DEGRADED'
  | 'DISCONNECTED'
  | 'INCOMPATIBLE'
  | 'AUTHENTICATION_FAILED'
  | 'REVOKED';

export type CaptureDeviceState =
  | 'CONNECTED'
  | 'BUSY'
  | 'DISCONNECTED'
  | 'UNSUPPORTED';

export interface CaptureProviderLimits {
  maximumSteps: number;
  maximumAssetBytes: number;
  maximumSessionDurationSeconds: number;
}

export interface CaptureDeviceStatus {
  id: string;
  displayName: string;
  state: CaptureDeviceState;
  capabilities: string[];
  supportedFormats: string[];
}

export interface CaptureProviderStatus {
  id: string;
  displayName: string;
  state: CaptureProviderHealthState;
  checkedAt: string;
  messageCode: string;
  protocolVersion: number;
  minimumSupportedProtocolVersion: number;
  capabilities: string[];
  supportedFormats: string[];
  limits: CaptureProviderLimits;
  devices: CaptureDeviceStatus[];
}

export interface CaptureProviderOverview {
  state: CaptureProviderHealthState;
  checkedAt: string;
  messageCode: string;
  providers: CaptureProviderStatus[];
}

export async function fetchCaptureProviderOverview(
  signal?: AbortSignal,
): Promise<CaptureProviderOverview> {
  const response = await fetch(`${BASE}/api/capture-providers`, {
    headers: { Accept: 'application/json' },
    signal,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Could not read camera-service status (HTTP ${response.status})`);
  }
  return response.json() as Promise<CaptureProviderOverview>;
}
