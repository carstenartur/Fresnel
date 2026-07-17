import { useEffect, useState } from 'react';
import {
  downloadRgbPng, fetchRgbPreviewPng, validatePlugin,
  type DesignValidationReport, type RgbZonePlateRequest, type SingleZonePlateRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { fetchPluginSchema, type PluginSchemaDocument } from '../pluginSchemaApi';
import { PluginActionBar } from '../schema/PluginActionBar';
import { SchemaForm } from '../schema/SchemaForm';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const BASE_DEFAULT: SingleZonePlateRequest = {
  apertureDiameterMm: 5,
  focalLengthMm: 100,
  wavelengthNm: 550,
  dpi: 600,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const DEFAULT: RgbZonePlateRequest = {
  base: BASE_DEFAULT,
  redNm: 630,
  greenNm: 532,
  blueNm: 450,
};

export function RgbPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<RgbZonePlateRequest>(() => {
    const loaded = initialJobParameters(initialJob, 'rgb-zone-plate', DEFAULT);
    return { ...loaded, base: { ...BASE_DEFAULT, ...loaded.base } };
  });
  const [schema, setSchema] = useState<PluginSchemaDocument<RgbZonePlateRequest> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    fetchPluginSchema<RgbZonePlateRequest>('rgb-zone-plate')
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
      setPreview(await fetchRgbPreviewPng(request));
      setValidationReport(await validatePlugin('rgb-zone-plate', request));
    }
    catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>RGB zone plate</h2>
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={request}
          onChange={setRequest}
          disabled={busy}
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
            run: () => downloadRgbPng(request, 'fresnel-rgb.png'),
          },
        }}
      />
      <SaveJobControl pluginId="rgb-zone-plate" parameters={request} disabled={busy || !schema} />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="RGB zone plate preview" />
      <ValidationReportView report={validationReport} />
    </>
  );
}
