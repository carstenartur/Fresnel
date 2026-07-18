import { useMemo, useState } from 'react';
import {
  downloadHologramPng,
  downloadHologramStl,
  reconstructHologramPng,
  synthesizeHologramPng,
  validatePlugin,
  type DesignValidationReport,
  type HologramRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { FRESNEL_JOB_MAX_BYTES } from '../jobApi';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { HologramTargetImageWidget } from '../schema/widgets/HologramTargetImageWidget';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: HologramRequest = {
  targetImageBase64: '',
  sidePx: 128,
  iterations: 40,
  outputType: 'GREYSCALE_PHASE',
  dpi: 600,
  wavelengthNm: 550,
  refractiveIndexDelta: 0.5,
  maxPhaseShiftRad: 2 * Math.PI,
};

const CUSTOM_WIDGETS = {
  'hologram-target-image': HologramTargetImageWidget,
} as const;

export function HologramPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<HologramRequest>(() =>
    initialJobParameters(initialJob, 'hologram', DEFAULT));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [maskUrl, setMaskUrl] = useBlobUrl();
  const [reconstructionUrl, setReconstructionUrl] = useBlobUrl();

  const estimatedJobBytes = useMemo(
    () => new TextEncoder().encode(JSON.stringify(request)).byteLength,
    [request],
  );
  const requestHasTarget = request.targetImageBase64.length > 0;
  const jobFitsFileLimit = estimatedJobBytes <= FRESNEL_JOB_MAX_BYTES;

  const requireTarget = (parameters: HologramRequest): HologramRequest | null => {
    if (!parameters.targetImageBase64) {
      setError('Please choose a target image.');
      return null;
    }
    return parameters;
  };

  const synthesise = async (parameters: HologramRequest) => {
    const built = requireTarget(parameters);
    if (!built) return;
    setBusy(true);
    setError(null);
    try {
      setMaskUrl(await synthesizeHologramPng(built));
      setValidationReport(await validatePlugin('hologram', built));
    } catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  const reconstruct = async (parameters: HologramRequest) => {
    const built = requireTarget(parameters);
    if (!built) return;
    setBusy(true);
    setError(null);
    try {
      setReconstructionUrl(await reconstructHologramPng(built, true));
    } catch (reconstructionError) {
      setError(reconstructionError instanceof Error
        ? reconstructionError.message
        : String(reconstructionError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Hologram</h2>
      <PluginEditorShell
        pluginId="hologram"
        value={request}
        onChange={setRequest}
        disabled={busy}
        customWidgets={CUSTOM_WIDGETS}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          const hasTarget = Boolean(normalized?.targetImageBase64);
          return (
            <>
              {requestHasTarget && !jobFitsFileLimit && (
                <div className="warning" role="alert">
                  This embedded target is too large for the current 1 MiB `.fresnel` job envelope.
                  Synthesis remains available, but saving the design job is disabled. Use a smaller
                  source image until a bounded asset container is introduced.
                </div>
              )}

              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Synthesising…' : 'Synthesise mask',
                    primary: true,
                    disabled: !hasTarget || !structurallyValid,
                    run: () => normalized && synthesise(normalized),
                  },
                  EXPORT_PNG: {
                    label: 'PNG',
                    disabled: !hasTarget || !structurallyValid,
                    run: async () => {
                      if (!normalized) return;
                      const built = requireTarget(normalized);
                      if (!built) return;
                      try {
                        await downloadHologramPng(built, 'fresnel-hologram.png');
                      } catch (exportError) {
                        setError(exportError instanceof Error
                          ? exportError.message
                          : String(exportError));
                      }
                    },
                  },
                  EXPORT_STL: {
                    label: 'STL',
                    disabled: !hasTarget || !structurallyValid,
                    run: async () => {
                      if (!normalized) return;
                      const built = requireTarget(normalized);
                      if (!built) return;
                      try {
                        await downloadHologramStl(built, 'fresnel-hologram-relief.stl');
                      } catch (exportError) {
                        setError(exportError instanceof Error
                          ? exportError.message
                          : String(exportError));
                      }
                    },
                  },
                }}
              />

              {schema.uiSchema.extensions?.includes('reconstruction-preview') && (
                <div className="actions" data-editor-extension="reconstruction-preview">
                  <button
                    className="secondary"
                    onClick={() => normalized && void reconstruct(normalized)}
                    disabled={busy || !hasTarget || !structurallyValid}
                  >
                    Simulate reconstruction
                  </button>
                </div>
              )}

              <SaveJobControl
                pluginId="hologram"
                parameters={hasTarget && jobFitsFileLimit ? normalized ?? null : null}
                disabled={busy || !structurallyValid}
              />
              {error && <p className="error-message">{error}</p>}

              <PreviewPane url={maskUrl} alt="Hologram phase mask">
                <span style={{ color: '#9ca3af' }}>Choose a target image and synthesise.</span>
              </PreviewPane>
              {reconstructionUrl && (
                <>
                  <h2 style={{ marginTop: 16 }}>Simulated reconstruction</h2>
                  <PreviewPane url={reconstructionUrl} alt="Reconstruction preview" />
                </>
              )}
              <ValidationReportView report={validationReport} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
