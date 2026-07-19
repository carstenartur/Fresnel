import { useEffect, useMemo, useState } from 'react';
import {
  fetchPreviewPng,
  validatePlugin,
  type DesignValidationReport,
  type MaskType,
  type Polarity,
  type SingleZonePlateRequest,
} from '../api';
import {
  fetchCopilotProviders,
  proposeExperiment,
  type ExperimentAlternative,
  type ExperimentCopilotProviderStatus,
  type ExperimentCopilotResponse,
  type GroundedParameter,
  type ProposalValueSource,
} from '../copilotApi';
import {
  saveFresnelJob,
  type FresnelJobDocument,
} from '../jobApi';

const DEFAULT_REQUEST =
  'Create a printable 532 nm zone plate with a 1 m focal distance at 1200 DPI. ' +
  'Prefer a robust design that is easy to fabricate.';

type NumericPath =
  | 'apertureDiameterMm'
  | 'focalLengthMm'
  | 'wavelengthNm'
  | 'dpi'
  | 'targetOffsetXmm'
  | 'targetOffsetYmm';

type ParameterPath = NumericPath | 'maskType' | 'polarity';

const NUMERIC_FIELDS: ReadonlyArray<{
  path: NumericPath;
  label: string;
  unit: string;
  step: number;
}> = [
  { path: 'apertureDiameterMm', label: 'Aperture diameter', unit: 'mm', step: 0.1 },
  { path: 'focalLengthMm', label: 'Focal length', unit: 'mm', step: 1 },
  { path: 'wavelengthNm', label: 'Wavelength', unit: 'nm', step: 1 },
  { path: 'dpi', label: 'Printer resolution', unit: 'DPI', step: 50 },
  { path: 'targetOffsetXmm', label: 'Target offset X', unit: 'mm', step: 0.1 },
  { path: 'targetOffsetYmm', label: 'Target offset Y', unit: 'mm', step: 0.1 },
];

export interface AssistantPanelProps {
  onOpenJob?: (job: FresnelJobDocument<unknown>) => void;
}

export function AssistantPanel({ onOpenJob }: AssistantPanelProps) {
  const [request, setRequest] = useState(DEFAULT_REQUEST);
  const [providers, setProviders] = useState<ExperimentCopilotProviderStatus[]>([]);
  const [providerId, setProviderId] = useState('mock');
  const [proposal, setProposal] = useState<ExperimentCopilotResponse | null>(null);
  const [draft, setDraft] = useState<SingleZonePlateRequest | null>(null);
  const [sources, setSources] = useState<Record<string, ProposalValueSource>>({});
  const [rationales, setRationales] = useState<Record<string, string>>({});
  const [validation, setValidation] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [validating, setValidating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchCopilotProviders()
      .then((items) => {
        if (!active) return;
        setProviders(items);
        if (!items.some((item) => item.id === providerId && item.available)) {
          const firstAvailable = items.find((item) => item.available);
          if (firstAvailable) setProviderId(firstAvailable.id);
        }
      })
      .catch((value) => {
        if (active) setError(value instanceof Error ? value.message : String(value));
      });
    return () => { active = false; };
  }, []); // provider selection is intentionally initialized once

  useEffect(() => () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const parameterByPath = useMemo(() => {
    const entries = proposal?.parameters.map((parameter) => [parameter.path, parameter] as const) ?? [];
    return new Map<ParameterPath, GroundedParameter>(entries as Array<[ParameterPath, GroundedParameter]>);
  }, [proposal]);

  const runProposal = async () => {
    setLoading(true);
    setError(null);
    setNotice(null);
    clearPreview();
    try {
      const result = await proposeExperiment({ request, provider: providerId });
      setProposal(result);
      setValidation(result.validation ?? null);
      setDraft(result.normalizedParameters ?? null);
      setSources(Object.fromEntries(result.parameters.map((item) => [item.path, item.source])));
      setRationales(Object.fromEntries(result.parameters.map((item) => [item.path, item.rationale])));
    } catch (value) {
      setProposal(null);
      setDraft(null);
      setValidation(null);
      setError(value instanceof Error ? value.message : String(value));
    } finally {
      setLoading(false);
    }
  };

  const updateParameter = (path: ParameterPath, value: number | MaskType | Polarity) => {
    setDraft((current) => current ? ({ ...current, [path]: value }) : current);
    setSources((current) => ({ ...current, [path]: 'USER_SUPPLIED' }));
    setRationales((current) => ({
      ...current,
      [path]: 'Edited and accepted by the user after the copilot proposal.',
    }));
    setValidation(null);
    setNotice(null);
    clearPreview();
  };

  const useDefault = (path: ParameterPath) => {
    const parameter = parameterByPath.get(path);
    if (parameter?.defaultValue === undefined || parameter.defaultValue === null) return;
    updateParameter(path, parameter.defaultValue as number | MaskType | Polarity);
    setSources((current) => ({ ...current, [path]: 'FRESNEL_DEFAULT' }));
    setRationales((current) => ({
      ...current,
      [path]: 'Reset by the user to the current Fresnel parameter-schema default.',
    }));
  };

  const applyAlternative = (alternative: ExperimentAlternative) => {
    if (!draft || !alternative.parameterOverrides) return;
    setDraft({ ...draft, ...alternative.parameterOverrides });
    const changed = Object.keys(alternative.parameterOverrides) as ParameterPath[];
    setSources((current) => ({
      ...current,
      ...Object.fromEntries(changed.map((path) => [path, 'COPILOT_INFERRED'])),
    }));
    setRationales((current) => ({
      ...current,
      ...Object.fromEntries(changed.map((path) => [path, `Applied alternative: ${alternative.label}`])),
    }));
    setValidation(null);
    clearPreview();
    setNotice(`Applied alternative “${alternative.label}”. Review and validate it before saving.`);
  };

  const validateAndPreview = async () => {
    if (!draft) return;
    setValidating(true);
    setError(null);
    setNotice(null);
    clearPreview();
    try {
      const report = await validatePlugin('zone-plate', draft);
      setValidation(report);
      if (report.valid) {
        const blob = await fetchPreviewPng(draft);
        setPreviewUrl(URL.createObjectURL(blob));
        setNotice('Deterministic Fresnel validation and preview completed.');
      } else {
        setNotice('Fresnel rejected the draft for fabrication. Review the deterministic findings.');
      }
    } catch (value) {
      setError(value instanceof Error ? value.message : String(value));
    } finally {
      setValidating(false);
    }
  };

  const saveJob = async () => {
    if (!draft || !proposal?.job) return;
    setError(null);
    try {
      await saveFresnelJob(
        'zone-plate',
        draft,
        proposal.parameterSchemaVersion,
        'fresnel-copilot-zone-plate.fresnel',
        withDraft(proposal.job, draft),
      );
      setNotice('Saved a canonical .fresnel job. It can be reproduced without the copilot.');
    } catch (value) {
      setError(value instanceof Error ? value.message : String(value));
    }
  };

  const openInEditor = () => {
    if (!draft || !proposal?.job || !onOpenJob) return;
    onOpenJob(withDraft(proposal.job, draft));
  };

  return (
    <div data-testid="experiment-copilot">
      <h2>Experiment Copilot</h2>
      <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 12 }}>
        Describe an optical goal. The provider may propose values, but only Fresnel’s
        schema normalization and deterministic validation can create a production job.
      </p>
      <div className="warning info" style={{ fontSize: 12, marginBottom: 12 }}>
        <strong>Grounded trust boundary:</strong> no generated text can suppress a validation
        error or manufacturing warning. The final <code>.fresnel</code> file works offline.
      </div>

      <div className="field">
        <label htmlFor="copilot-provider">Proposal provider</label>
        <select
          id="copilot-provider"
          value={providerId}
          onChange={(event) => setProviderId(event.target.value)}
        >
          {providers.map((provider) => (
            <option key={provider.id} value={provider.id} disabled={!provider.available}>
              {provider.displayName} — {provider.modelId}{provider.available ? '' : ' (not configured)'}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="experiment-request">Optical goal</label>
        <textarea
          id="experiment-request"
          rows={6}
          value={request}
          onChange={(event) => setRequest(event.target.value)}
          style={{ width: '100%', resize: 'vertical' }}
        />
      </div>

      <div className="actions">
        <button onClick={runProposal} disabled={loading || request.trim().length === 0}>
          {loading ? 'Grounding proposal…' : 'Create grounded proposal'}
        </button>
      </div>

      {error && <p className="error-message" role="alert">{error}</p>}
      {notice && <div className="warning info" role="status">{notice}</div>}

      {proposal && (
        <section style={{ marginTop: 20 }}>
          <h3>Proposal review</h3>
          <p style={{ fontSize: 12 }}>
            <strong>{proposal.providerId}</strong> · {proposal.modelId}<br />
            {proposal.summary}
          </p>

          {proposal.unresolvedQuestions.length > 0 && (
            <div className="warning" data-testid="copilot-questions">
              <strong>Clarification required</strong>
              <ul>
                {proposal.unresolvedQuestions.map((question) => <li key={question}>{question}</li>)}
              </ul>
            </div>
          )}

          {draft && (
            <>
              <div data-testid="copilot-parameter-review">
                {NUMERIC_FIELDS.map((field) => (
                  <ParameterRow
                    key={field.path}
                    label={`${field.label} (${field.unit})`}
                    path={field.path}
                    source={sources[field.path] ?? 'FRESNEL_DEFAULT'}
                    rationale={rationales[field.path] ?? ''}
                    onDefault={() => useDefault(field.path)}
                  >
                    <input
                      aria-label={`${field.label} (${field.unit})`}
                      type="number"
                      step={field.step}
                      value={numberValue(draft[field.path])}
                      onChange={(event) => {
                        const value = Number(event.target.value);
                        if (Number.isFinite(value)) updateParameter(field.path, value);
                      }}
                    />
                  </ParameterRow>
                ))}

                <ParameterRow
                  label="Mask type"
                  path="maskType"
                  source={sources.maskType ?? 'FRESNEL_DEFAULT'}
                  rationale={rationales.maskType ?? ''}
                  onDefault={() => useDefault('maskType')}
                >
                  <select
                    aria-label="Mask type"
                    value={draft.maskType ?? 'BINARY_AMPLITUDE'}
                    onChange={(event) => updateParameter('maskType', event.target.value as MaskType)}
                  >
                    <option value="BINARY_AMPLITUDE">Binary amplitude</option>
                    <option value="GREYSCALE_PHASE">Greyscale phase</option>
                  </select>
                </ParameterRow>

                <ParameterRow
                  label="Polarity"
                  path="polarity"
                  source={sources.polarity ?? 'FRESNEL_DEFAULT'}
                  rationale={rationales.polarity ?? ''}
                  onDefault={() => useDefault('polarity')}
                >
                  <select
                    aria-label="Polarity"
                    value={draft.polarity ?? 'POSITIVE'}
                    onChange={(event) => updateParameter('polarity', event.target.value as Polarity)}
                  >
                    <option value="POSITIVE">Positive</option>
                    <option value="NEGATIVE">Negative</option>
                  </select>
                </ParameterRow>
              </div>

              {proposal.alternatives.length > 0 && (
                <details style={{ marginTop: 12 }}>
                  <summary>Review alternatives</summary>
                  {proposal.alternatives.map((alternative) => (
                    <div key={alternative.label} style={{ marginTop: 8, fontSize: 12 }}>
                      <strong>{alternative.label}</strong>
                      <p style={{ margin: '2px 0 6px' }}>{alternative.description}</p>
                      <button type="button" onClick={() => applyAlternative(alternative)}>
                        Apply alternative
                      </button>
                    </div>
                  ))}
                </details>
              )}

              <div className="actions" style={{ marginTop: 14 }}>
                <button onClick={validateAndPreview} disabled={validating}>
                  {validating ? 'Validating…' : 'Validate & preview'}
                </button>
                <button onClick={saveJob}>Save job (.fresnel)</button>
                {onOpenJob && <button onClick={openInEditor}>Open in Zone Plate editor</button>}
              </div>

              {validation && <ValidationSummary validation={validation} />}
              {previewUrl && (
                <img
                  src={previewUrl}
                  alt="Deterministic preview of the proposed Zone Plate"
                  data-testid="copilot-preview"
                  style={{ width: '100%', marginTop: 12, border: '1px solid #d1d5db' }}
                />
              )}
            </>
          )}
        </section>
      )}
    </div>
  );

  function clearPreview() {
    setPreviewUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return null;
    });
  }
}

function ParameterRow({
  label,
  path,
  source,
  rationale,
  onDefault,
  children,
}: {
  label: string;
  path: ParameterPath;
  source: ProposalValueSource;
  rationale: string;
  onDefault: () => void;
  children: JSX.Element;
}) {
  return (
    <div
      className="field"
      data-parameter-path={path}
      style={{ borderBottom: '1px solid #e5e7eb', paddingBottom: 8, marginBottom: 8 }}
    >
      <label>{label}</label>
      {children}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
        <SourceBadge source={source} />
        <button type="button" onClick={onDefault} style={{ fontSize: 11, padding: '2px 6px' }}>
          Use Fresnel default
        </button>
      </div>
      {rationale && <small style={{ color: '#6b7280' }}>{rationale}</small>}
    </div>
  );
}

function SourceBadge({ source }: { source: ProposalValueSource }) {
  const label = source === 'USER_SUPPLIED'
    ? 'User supplied'
    : source === 'COPILOT_INFERRED'
      ? 'Copilot inferred'
      : 'Fresnel default';
  const background = source === 'USER_SUPPLIED'
    ? '#dbeafe'
    : source === 'COPILOT_INFERRED'
      ? '#fef3c7'
      : '#e5e7eb';
  return (
    <span
      data-source={source}
      style={{ background, borderRadius: 4, padding: '2px 6px', fontSize: 11 }}
    >
      {label}
    </span>
  );
}

function ValidationSummary({ validation }: { validation: DesignValidationReport }) {
  const errors = validation.findings.filter((finding) => finding.severity === 'ERROR');
  const warnings = validation.findings.filter((finding) => finding.severity === 'WARNING');
  return (
    <div
      className={`warning ${validation.valid ? 'info' : 'error'}`}
      data-testid="copilot-validation"
      style={{ marginTop: 12 }}
    >
      <strong>{validation.valid ? 'Deterministic validation passed' : 'Deterministic validation failed'}</strong>
      <div>{errors.length} errors · {warnings.length} warnings · {validation.metrics.length} metrics</div>
      {validation.findings.length > 0 && (
        <ul>
          {validation.findings.map((finding) => (
            <li key={`${finding.layer}:${finding.code}`}>{finding.code}: {finding.message}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function withDraft(
  source: FresnelJobDocument<SingleZonePlateRequest>,
  parameters: SingleZonePlateRequest,
): FresnelJobDocument<SingleZonePlateRequest> {
  return {
    ...source,
    parameters,
    provenance: {
      ...source.provenance,
      createdWith: source.provenance?.createdWith ?? 'Fresnel experiment copilot',
      // The backend recomputes this for the accepted/edited parameter object.
      parameterSha256: undefined,
    },
  };
}

function numberValue(value: unknown): number | string {
  return typeof value === 'number' && Number.isFinite(value) ? value : '';
}
