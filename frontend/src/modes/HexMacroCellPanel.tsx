import { useEffect, useState } from 'react';
import {
  downloadHexPdf,
  downloadHexPng,
  fetchHexPreviewPng,
  hexInfo,
  validatePlugin,
  type DesignValidationReport, type HexInfo,
  type HexMacroCellRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { fetchPluginSchema, type PluginSchemaDocument } from '../pluginSchemaApi';
import { SchemaForm } from '../schema/SchemaForm';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: HexMacroCellRequest = {
  macroRadiusMm: 30,
  subDiameterMm: 10,
  subPitchMm: 11,
  focalLengthMm: 1000,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
  wavelengthNm: 550,
  dpi: 600,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

export function HexMacroCellPanel({ initialJob }: JobPanelProps) {
  const [req, setReq] = useState<HexMacroCellRequest>(() =>
    initialJobParameters(initialJob, 'hex-macro-cell', DEFAULT));
  const [schema, setSchema] = useState<PluginSchemaDocument<HexMacroCellRequest> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [info, setInfo] = useState<HexInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    fetchPluginSchema<HexMacroCellRequest>('hex-macro-cell')
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
      setPreview(await fetchHexPreviewPng(req));
      setInfo(await hexInfo(req));
      setValidationReport(await validatePlugin('hex-macro-cell', req));
    }
    catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Hex macro cell</h2>
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={req}
          onChange={setReq}
          disabled={busy}
        />
      ) : !schemaError ? (
        <p role="status" style={{ fontSize: 12, color: '#6b7280' }}>Loading plugin schema…</p>
      ) : null}
      {schemaError && <p className="error-message">Could not load editor schema: {schemaError}</p>}

      {info && (
        <p style={{ fontSize: 12, color: '#6b7280' }}>
          {info.subElements.toLocaleString()} sub-elements · {info.imageSidePx.toLocaleString()} px per side
        </p>
      )}

      <div className="actions">
        <button onClick={renderPreview} disabled={busy || !schema}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy || !schema}
                onClick={() => downloadHexPng(req, 'fresnel-hex-macro.png')}>
          PNG
        </button>
        <button className="secondary" disabled={busy || !schema}
                onClick={() => downloadHexPdf(req, 'FIT', 'fresnel-hex-macro.pdf')}>
          PDF
        </button>
      </div>
      <SaveJobControl pluginId="hex-macro-cell" parameters={req} disabled={busy || !schema} />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Hex macro cell preview" />
      <ValidationReportView report={validationReport} />
    </>
  );
}
