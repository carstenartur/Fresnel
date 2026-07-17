import { useEffect, useMemo, useState } from 'react';
import {
  downloadHologramPng, downloadHologramStl, reconstructHologramPng, synthesizeHologramPng,
  validatePlugin,
  type DesignValidationReport, type HologramRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { FRESNEL_JOB_MAX_BYTES } from '../jobApi';
import { fetchPluginSchema, type PluginSchemaDocument } from '../pluginSchemaApi';
import { PluginActionBar } from '../schema/PluginActionBar';
import { SchemaForm } from '../schema/SchemaForm';
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
  const [schema, setSchema] = useState<PluginSchemaDocument<HologramRequest> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [maskUrl, setMaskUrl] = useBlobUrl();
  const [reconUrl, setReconUrl] = useBlobUrl();

  useEffect(() => {
    let active = true;
    fetchPluginSchema<HologramRequest>('hologram')
      .then((loaded) => {
        if (!active) return;
        setSchema(loaded);
        setSchemaError(null);
      })
      .catch((loadError: unknown) => {
        if (!active) return;
        setSchema(null);
        setSchemaError(loadError instanceof Error ? loadError.message : String(loadError));
      });
    return () => { active = false; };
  }, []);

  const estimatedJobBytes = useMemo(
    () => new TextEncoder().encode(JSON.stringify(request)).byteLength,
    [request],
  );
  const hasTarget = request.targetImageBase64.length > 0;
  const jobFitsFileLimit = estimatedJobBytes <= FRESNEL_JOB_MAX_BYTES;

  const build = (): HologramRequest | null => {
    if (!hasTarget) {
      setError('Please choose a target image.');
      return null;
    }
    return request;
  };

  const synthesise = async () => {
    const req = build(); if (!req) return;
    setBusy(true); setError(null);
    try {
      setMaskUrl(await synthesizeHologramPng(req));
      setValidationReport(await validatePlugin('hologram', req));
    }
    catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    }
    finally { setBusy(false); }
  };

  const reconstruct = async () => {
    const req = build(); if (!req) return;
    setBusy(true); setError(null);
    try { setReconUrl(await reconstructHologramPng(req, true)); }
    catch (reconstructionError) {
      setError(reconstructionError instanceof Error
        ? reconstructionError.message
        : String(reconstructionError));
    }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Hologram</h2>
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={request}
          onChange={setRequest}
          disabled={busy}
          customWidgets={CUSTOM_WIDGETS}
        />
      ) : !schemaError ? (
        <p role="status" style={{ fontSize: 12, color: '#6b7280' }}>Loading plugin schema…</p>
      ) : null}
      {schemaError && <p className="error-message">Could not load editor schema: {schemaError}</p>}

      {hasTarget && !jobFitsFileLimit && (
        <div className="warning" role="alert">
          This embedded target is too large for the current 1 MiB `.fresnel` job envelope.
          Synthesis remains available, but saving the design job is disabled. Use a smaller
          source image until a bounded asset container is introduced.
        </div>
      )}

      <PluginActionBar
        capabilities={schema?.capabilities ?? []}
        busy={busy}
        actions={{
          PREVIEW_PNG: {
            label: busy ? 'Synthesising…' : 'Synthesise mask',
            primary: true,
            disabled: !hasTarget,
            run: synthesise,
          },
          EXPORT_PNG: {
            label: 'PNG',
            disabled: !hasTarget,
            run: async () => {
              const req = build(); if (!req) return;
              try { await downloadHologramPng(req, 'fresnel-hologram.png'); }
              catch (exportError) {
                setError(exportError instanceof Error ? exportError.message : String(exportError));
              }
            },
          },
          EXPORT_STL: {
            label: 'STL',
            disabled: !hasTarget,
            run: async () => {
              const req = build(); if (!req) return;
              try { await downloadHologramStl(req, 'fresnel-hologram-relief.stl'); }
              catch (exportError) {
                setError(exportError instanceof Error ? exportError.message : String(exportError));
              }
            },
          },
        }}
      />

      <div className="actions" data-editor-extension="reconstruction-preview">
        <button className="secondary" onClick={reconstruct} disabled={busy || !hasTarget}>
          Simulate reconstruction
        </button>
      </div>

      <SaveJobControl
        pluginId="hologram"
        parameters={hasTarget && jobFitsFileLimit ? request : null}
        disabled={busy}
      />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={maskUrl} alt="Hologram phase mask">
        <span style={{ color: '#9ca3af' }}>Choose a target image and synthesise.</span>
      </PreviewPane>
      {reconUrl && (
        <>
          <h2 style={{ marginTop: 16 }}>Simulated reconstruction</h2>
          <PreviewPane url={reconUrl} alt="Reconstruction preview" />
        </>
      )}
      <ValidationReportView report={validationReport} />
    </>
  );
}
