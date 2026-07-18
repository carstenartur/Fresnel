import type { FresnelPluginId } from './jobApi';

const BASE = '';

export type PluginEditorMode = 'SCHEMA' | 'SCHEMA_WITH_EXTENSIONS' | 'CUSTOM';
export type PluginCapability =
  | 'EXPORT_PNG'
  | 'EXPORT_SVG'
  | 'EXPORT_PDF'
  | 'EXPORT_DXF'
  | 'EXPORT_GERBER'
  | 'EXPORT_STL'
  | 'PREVIEW_PNG'
  | 'PROPAGATION_PREVIEW'
  | 'PRINTABILITY_ANALYSIS'
  | 'OPTICAL_QUALITY_REPORT'
  | 'EXPERIMENTAL_VALIDATION';

export interface PluginMetadata {
  id: FresnelPluginId;
  displayName: string;
  description: string;
  documentationUrl: string;
  capabilities: PluginCapability[];
  parameterSchemaVersion: number;
  editorMode: PluginEditorMode;
  schemaUrl: string;
}

export interface ParameterFieldSchema {
  type: 'number' | 'integer' | 'string' | 'boolean' | 'object' | 'array';
  title?: string;
  description?: string;
  default?: unknown;
  enum?: string[];
  minimum?: number;
  maximum?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  properties?: Record<string, ParameterFieldSchema>;
  required?: string[];
  items?: ParameterFieldSchema;
  contentEncoding?: string;
  'x-fresnel-unit'?: string;
  'x-fresnel-step'?: number;
  'x-fresnel-precision'?: number;
  'x-fresnel-expensive'?: boolean;
  'x-fresnel-widget'?: string;
  'x-fresnel-enum-labels'?: Record<string, string>;
  'x-fresnel-sensitive-size'?: boolean;
  'x-fresnel-power-of-two'?: boolean;
}

export interface PluginParameterSchema {
  $schema: string;
  $id: string;
  title: string;
  type: 'object';
  additionalProperties: false;
  required?: string[];
  default: Record<string, unknown>;
  properties: Record<string, ParameterFieldSchema>;
}

export interface PluginUiGroup {
  id: string;
  title: string;
  fields: string[];
  collapsible?: boolean;
  advanced?: boolean;
}

export interface PluginUiWidget {
  type: string;
  presets?: Array<number | string>;
}

export interface PluginUiSchema {
  formatVersion: number;
  pluginId: FresnelPluginId;
  parameterSchemaVersion: number;
  groups: PluginUiGroup[];
  widgets?: Record<string, PluginUiWidget>;
  extensions?: string[];
}

export interface PluginSchemaDocument<TDefaults extends object = Record<string, unknown>> {
  pluginId: FresnelPluginId;
  parameterSchemaVersion: number;
  editorMode: PluginEditorMode;
  parameterSchema: PluginParameterSchema;
  uiSchema: PluginUiSchema;
  defaults: TDefaults;
  capabilities: PluginCapability[];
}

export interface PluginParameterFieldError {
  path: string;
  code: string;
  message: string;
}

export interface PluginParameterValidation<TParameters extends object = Record<string, unknown>> {
  pluginId: FresnelPluginId;
  parameterSchemaVersion: number;
  valid: boolean;
  normalizedParameters?: TParameters;
  errors: PluginParameterFieldError[];
}

export async function fetchPluginMetadata(): Promise<PluginMetadata[]> {
  const response = await fetch(`${BASE}/api/plugins`, {
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Could not load plugin metadata (HTTP ${response.status})`);
  }
  return response.json() as Promise<PluginMetadata[]>;
}

export async function fetchPluginSchema<TDefaults extends object = Record<string, unknown>>(
  pluginId: FresnelPluginId,
): Promise<PluginSchemaDocument<TDefaults>> {
  const response = await fetch(`${BASE}/api/plugins/${encodeURIComponent(pluginId)}/schema`, {
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Could not load schema for ${pluginId} (HTTP ${response.status})`);
  }
  return response.json() as Promise<PluginSchemaDocument<TDefaults>>;
}

export async function validatePluginParameters<TParameters extends object>(
  pluginId: FresnelPluginId,
  parameters: TParameters,
): Promise<PluginParameterValidation<TParameters>> {
  const response = await fetch(
    `${BASE}/api/plugins/${encodeURIComponent(pluginId)}/parameters/validate`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(parameters),
    },
  );
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Could not validate parameters for ${pluginId} (HTTP ${response.status})`);
  }
  return response.json() as Promise<PluginParameterValidation<TParameters>>;
}
