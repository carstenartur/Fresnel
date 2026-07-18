const BASE = '';

export const FRESNEL_JOB_MEDIA_TYPE = 'application/vnd.carstenartur.fresnel.job+json';
export const FRESNEL_JOB_EXTENSION = '.fresnel';
export const FRESNEL_JOB_FORMAT = 'io.github.carstenartur.fresnel.job';
export const FRESNEL_JOB_SCHEMA_URL =
  'https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json';
export const FRESNEL_JOB_FORMAT_VERSION = 1;
export const FRESNEL_JOB_MAX_BYTES = 1024 * 1024;

export type FresnelPluginId =
  | 'zone-plate'
  | 'hex-macro-cell'
  | 'window-foil'
  | 'multi-focus'
  | 'rgb-zone-plate'
  | 'hologram';

export interface FresnelJobPluginRef {
  id: FresnelPluginId;
  parameterSchemaVersion: number;
  algorithmVersion: string;
}

export interface FresnelProductionOutput {
  id?: string;
  format: 'png' | 'svg' | 'pdf' | 'dxf' | 'gerber' | 'gbr' | 'stl';
  filename?: string;
  sheet?: 'FIT' | 'A4' | 'A3' | 'A2' | 'A1' | 'A0';
  printScale?: number;
  options?: Record<string, unknown>;
}

export interface FresnelProductionPlan {
  outputs: FresnelProductionOutput[];
}

export interface FresnelJobProvenance {
  createdWith?: string;
  applicationVersion?: string;
  parameterSha256?: string;
}

export interface FresnelJobDocument<T = unknown> {
  $schema?: string;
  format: typeof FRESNEL_JOB_FORMAT;
  formatVersion: number;
  plugin: FresnelJobPluginRef;
  parameters: T;
  production?: FresnelProductionPlan;
  provenance?: FresnelJobProvenance;
}

export interface LoadedFresnelJob {
  job: FresnelJobDocument<unknown>;
  migratedFromLegacy: boolean;
  sourceName: string;
}

export function createFresnelJob<T>(
  pluginId: FresnelPluginId,
  parameters: T,
  parameterSchemaVersion: number,
  sourceJob?: FresnelJobDocument<unknown> | null,
): FresnelJobDocument<T> {
  if (!Number.isInteger(parameterSchemaVersion) || parameterSchemaVersion < 1) {
    throw new Error('Plugin parameter schema version must be a positive integer.');
  }

  const reusableSource = sourceJob?.plugin.id === pluginId ? sourceJob : null;
  const sourceProvenance = reusableSource?.provenance;

  return {
    $schema: FRESNEL_JOB_SCHEMA_URL,
    format: FRESNEL_JOB_FORMAT,
    formatVersion: FRESNEL_JOB_FORMAT_VERSION,
    plugin: {
      id: pluginId,
      parameterSchemaVersion:
        reusableSource?.plugin.parameterSchemaVersion ?? parameterSchemaVersion,
      algorithmVersion: reusableSource?.plugin.algorithmVersion ?? `${pluginId}/1`,
    },
    parameters,
    production: reusableSource?.production,
    provenance: {
      createdWith: sourceProvenance?.createdWith ?? 'Fresnel',
      applicationVersion: sourceProvenance?.applicationVersion,
      // parameterSha256 is intentionally omitted. The backend recomputes it from
      // the edited, normalized parameter object before returning the download.
    },
  };
}

export async function saveFresnelJob<T>(
  pluginId: FresnelPluginId,
  parameters: T,
  parameterSchemaVersion: number,
  filename = `fresnel-${pluginId}${FRESNEL_JOB_EXTENSION}`,
  sourceJob?: FresnelJobDocument<unknown> | null,
): Promise<void> {
  const response = await fetch(`${BASE}/api/designs/job/save`, {
    method: 'POST',
    headers: {
      'Content-Type': FRESNEL_JOB_MEDIA_TYPE,
      Accept: FRESNEL_JOB_MEDIA_TYPE,
    },
    body: JSON.stringify(createFresnelJob(
      pluginId,
      parameters,
      parameterSchemaVersion,
      sourceJob,
    )),
  });
  if (!response.ok) {
    throw new Error(await responseError(response));
  }
  downloadBlob(await response.blob(), filename);
}

export async function loadFresnelJobFromFile(file: File): Promise<LoadedFresnelJob> {
  if (file.size > FRESNEL_JOB_MAX_BYTES) {
    throw new Error(`Job file exceeds the ${FRESNEL_JOB_MAX_BYTES.toLocaleString()} byte limit.`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(await file.text());
  } catch (error) {
    throw new Error(`Invalid JSON: ${error instanceof Error ? error.message : String(error)}`);
  }

  const migratedFromLegacy = isLegacyDesignDocument(parsed);
  const response = await fetch(`${BASE}/api/designs/job/load`, {
    method: 'POST',
    headers: {
      'Content-Type': migratedFromLegacy ? 'application/json' : FRESNEL_JOB_MEDIA_TYPE,
      Accept: FRESNEL_JOB_MEDIA_TYPE,
    },
    body: JSON.stringify(parsed),
  });
  if (!response.ok) {
    throw new Error(await responseError(response));
  }

  return {
    job: await response.json() as FresnelJobDocument<unknown>,
    migratedFromLegacy,
    sourceName: file.name,
  };
}

function isLegacyDesignDocument(value: unknown): boolean {
  return isRecord(value)
    && typeof value.kind === 'string'
    && typeof value.version === 'number'
    && 'payload' in value
    && !('format' in value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
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
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
