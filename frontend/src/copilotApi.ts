import type { DesignValidationReport, SingleZonePlateRequest } from './api';
import type { FresnelJobDocument } from './jobApi';

const BASE = '';

export type ProposalValueSource =
  | 'USER_SUPPLIED'
  | 'COPILOT_INFERRED'
  | 'FRESNEL_DEFAULT';

export interface ExperimentCopilotProviderStatus {
  id: string;
  displayName: string;
  modelId: string;
  available: boolean;
}

export interface GroundedParameter {
  path: keyof SingleZonePlateRequest;
  value: unknown;
  defaultValue: unknown;
  source: ProposalValueSource;
  rationale: string;
}

export interface ExperimentAlternative {
  label: string;
  description: string;
  parameterOverrides?: Partial<SingleZonePlateRequest>;
}

export interface ExperimentCopilotRequest {
  request: string;
  provider?: string;
  currentParameters?: Partial<SingleZonePlateRequest>;
}

export interface ExperimentCopilotResponse {
  providerId: string;
  modelId: string;
  selectedPluginId: 'zone-plate';
  parameterSchemaVersion: number;
  summary: string;
  ready: boolean;
  parameters: GroundedParameter[];
  unresolvedQuestions: string[];
  alternatives: ExperimentAlternative[];
  normalizedParameters?: SingleZonePlateRequest;
  validation?: DesignValidationReport;
  job?: FresnelJobDocument<SingleZonePlateRequest>;
}

export async function fetchCopilotProviders(): Promise<ExperimentCopilotProviderStatus[]> {
  const response = await fetch(`${BASE}/api/assistant/providers`);
  if (!response.ok) throw new Error(await responseError(response));
  return response.json();
}

export async function proposeExperiment(
  request: ExperimentCopilotRequest,
): Promise<ExperimentCopilotResponse> {
  const response = await fetch(`${BASE}/api/assistant/propose`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw new Error(await responseError(response));
  return response.json();
}

async function responseError(response: Response): Promise<string> {
  const text = await response.text();
  if (!text) return `HTTP ${response.status}`;
  try {
    const value = JSON.parse(text) as { message?: unknown };
    return typeof value.message === 'string' ? value.message : text;
  } catch {
    return text;
  }
}
