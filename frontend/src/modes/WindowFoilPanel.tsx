import { useState } from 'react';
import {
  downloadFoilPdf,
  fetchFoilPreviewPng,
  foilInfo,
  type FoilInfo,
  type WindowFoilRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { WindowCellLayoutWidget } from '../schema/widgets/WindowCellLayoutWidget';
import { downloadWindowFoilPng } from '../windowFoilExportApi';
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
  const [request, setRequest] = useState<WindowFoilRequest>(() =>
    initialJobParameters(initialJob, 'window-foil', DEFAULT));
  const [info, setInfo] = useState<FoilInfo | null>(null);
  const [sheet, setSheet] = useState<(typeof SHEETS)[number]>('A4');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const renderPreview = async (parameters: WindowFoilRequest) => {
    setBusy(true);
    setError(null);
    try {
      setPreview(await fetchFoilPreviewPng(parameters));
      setInfo(await foilInfo(parameters));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Window foil</h2>
      <PluginEditorShell
        pluginId="window-foil"
        value={request}
        onChange={setRequest}
        disabled={busy}
        customWidgets={CUSTOM_WIDGETS}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation, domainValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          const productionReady = structurallyValid && domainValidation?.valid === true;
          return (
            <>
              <div className="field" data-editor-extension="production-actions">
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
                  {info.cells} cells · image {info.imageWidthPx.toLocaleString()} ×{' '}
                  {info.imageHeightPx.toLocaleString()} px
                </p>
              )}

              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Rendering…' : 'Render preview',
                    primary: true,
                    disabled: !structurallyValid,
                    run: () => normalized && renderPreview(normalized),
                  },
                  EXPORT_PNG: {
                    label: 'PNG',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadWindowFoilPng(normalized, 'fresnel-window-foil.png'),
                  },
                  EXPORT_PDF: {
                    label: `PDF (${sheet})`,
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadFoilPdf(normalized, sheet, 'fresnel-window-foil.pdf'),
                  },
                }}
              />
              <SaveJobControl
                pluginId="window-foil"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />
              {error && <p className="error-message">{error}</p>}

              <PreviewPane url={previewUrl} alt="Window foil preview" />
              <ValidationReportView report={domainValidation} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
