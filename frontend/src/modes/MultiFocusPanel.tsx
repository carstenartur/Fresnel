import { useEffect, useState } from 'react';
import {
  downloadMultiFocusPng, fetchMultiFocusPreviewPng, validatePlugin,
  type DesignValidationReport, type MultiFocusRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { fetchPluginSchema, type PluginSchemaDocument } from '../pluginSchemaApi';
import { PluginActionBar } from '../schema/PluginActionBar';
import { SchemaForm } from '../schema/SchemaForm';
import { FocusPointListWidget } from '../schema/widgets/FocusPointListWidget';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: MultiFocusRequest = {
  apertureDiameterMm: 10,
  focusPoints: [
    { xMm: -5, yMm: 0, zMm: 1000 },
    { xMm: 5, yMm: 0, zMm: 1000 },
  ],
  wavelengthNm: 550,
  dpi: 1200,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const CUSTOM_WIDGETS = {
  'focus-point-list': FocusPointListWidget,
} as const;

export function MultiFocusPanel({ initialJob }: JobPanelProps) {
  const [req, setReq] = useState<MultiFocusRequest>(() =>
    initialJobParameters(initialJob, 'multi-focus', DEFAULT));
  const [schema, setSchema] = useState<PluginSchemaDocument<MultiFocusRequest> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    fetchPluginSchema<MultiFocusRequest>('multi-focus')
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

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try {
      setPreview(await fetchMultiFocusPreviewPng(req));
      setValidationReport(await validatePlugin('multi-focus', req));
    }
    catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Multi-focus</h2>
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={req}
          onChange={setReq}
          disabled={busy}
          customWidgets={CUSTOM_WIDGETS}
        />
      ) : !schemaError ? (
        <p role="status" style={{ fontSize: 12, color: '#6b7280' }}>Loading plugin schema…</p>
      ) : null}
      {schemaError && <p className="error-message">Could not load editor schema: {schemaError}</p>}

      <PluginActionBar
        capabilities={schema?.capabilities ?? []}
        busy={busy}
        actions={{
          PREVIEW_PNG: {
            label: busy ? 'Rendering…' : 'Render preview',
            primary: true,
            run: renderPreview,
          },
          EXPORT_PNG: {
            label: 'PNG',
            run: () => downloadMultiFocusPng(req, 'fresnel-multifocus.png'),
          },
        }}
      />
      <SaveJobControl pluginId="multi-focus" parameters={req} disabled={busy || !schema} />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Multi-focus preview" />
      <ValidationReportView report={validationReport} />
    </>
  );
}
