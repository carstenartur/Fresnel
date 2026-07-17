import { useEffect, useState } from 'react';
import {
  downloadFoilPdf, fetchFoilPreviewPng, foilInfo,
  validatePlugin,
  type DesignValidationReport, type FoilInfo, type WindowFoilRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { fetchPluginSchema, type PluginSchemaDocument } from '../pluginSchemaApi';
import { PluginActionBar } from '../schema/PluginActionBar';
import { SchemaForm } from '../schema/SchemaForm';
import { WindowCellLayoutWidget } from '../schema/widgets/WindowCellLayoutWidget';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: WindowFoilRequest = {
  sheetWidthMm: 200,
  sheetHeightMm: 100,
  macroRadiusMm: 25,
  subDiameterMm: 8,
  subPitchMm: 9,
  wavelengthNm: 550,
  dpi: 150,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
  cellSpecs: [],
  drawCropMarks: true,
};

const SHEETS = ['FIT', 'A4', 'A3', 'A2', 'A1', 'A0'] as const;
const CUSTOM_WIDGETS = {
  'window-cell-layout': WindowCellLayoutWidget,
} as const;

export function WindowFoilPanel({ initialJob }: JobPanelProps) {
  const [req, setReq] = useState<WindowFoilRequest>(() =>
    initialJobParameters(initialJob, 'window-foil', DEFAULT));
  const [schema, setSchema] = useState<PluginSchemaDocument<WindowFoilRequest> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [info, setInfo] = useState<FoilInfo | null>(null);
  const [sheet, setSheet] = useState<(typeof SHEETS)[number]>('A4');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    fetchPluginSchema<WindowFoilRequest>('window-foil')
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
      setPreview(await fetchFoilPreviewPng(req));
      setInfo(await foilInfo(req));
      setValidationReport(await validatePlugin('window-foil', req));
    } catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Window foil</h2>
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

      <div className="field">
        <label htmlFor="window-foil-pdf-sheet">PDF sheet size</label>
        <select
          id="window-foil-pdf-sheet"
          value={sheet}
          disabled={busy}
          onChange={(event) => setSheet(event.target.value as (typeof SHEETS)[number])}
        >
          {SHEETS.map((value) => <option key={value} value={value}>{value}</option>)}
        </select>
      </div>

      {info && (
        <p style={{ fontSize: 12, color: '#6b7280' }}>
          {info.cells} cells · image {info.imageWidthPx.toLocaleString()} × {info.imageHeightPx.toLocaleString()} px
        </p>
      )}

      <PluginActionBar
        capabilities={schema?.capabilities ?? []}
        busy={busy}
        actions={{
          PREVIEW_PNG: {
            label: busy ? 'Rendering…' : 'Render preview',
            primary: true,
            run: renderPreview,
          },
          EXPORT_PDF: {
            label: `PDF (${sheet})`,
            run: () => downloadFoilPdf(req, sheet, 'fresnel-window-foil.pdf'),
          },
        }}
      />
      <SaveJobControl pluginId="window-foil" parameters={req} disabled={busy || !schema} />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Window foil preview" />
      <ValidationReportView report={validationReport} />
    </>
  );
}
