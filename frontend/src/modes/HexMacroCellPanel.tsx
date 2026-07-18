import { useState } from 'react';
import {
  downloadHexPdf,
  downloadHexPng,
  fetchHexPreviewPng,
  hexInfo,
  validatePlugin,
  type DesignValidationReport,
  type HexInfo,
  type HexMacroCellRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
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
  const [request, setRequest] = useState<HexMacroCellRequest>(() =>
    initialJobParameters(initialJob, 'hex-macro-cell', DEFAULT));
  const [info, setInfo] = useState<HexInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const renderPreview = async (parameters: HexMacroCellRequest) => {
    setBusy(true);
    setError(null);
    try {
      setPreview(await fetchHexPreviewPng(parameters));
      setInfo(await hexInfo(parameters));
      setValidationReport(await validatePlugin('hex-macro-cell', parameters));
    } catch (renderError) {
      setValidationReport(null);
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Hex macro cell</h2>
      <PluginEditorShell
        pluginId="hex-macro-cell"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          return (
            <>
              {info && (
                <p style={{ fontSize: 12, color: '#6b7280' }}>
                  {info.subElements.toLocaleString()} sub-elements ·{' '}
                  {info.imageSidePx.toLocaleString()} px per side
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
                    disabled: !structurallyValid,
                    run: () => normalized
                      && downloadHexPng(normalized, 'fresnel-hex-macro.png'),
                  },
                  EXPORT_PDF: {
                    label: 'PDF',
                    disabled: !structurallyValid,
                    run: () => normalized
                      && downloadHexPdf(normalized, 'FIT', 'fresnel-hex-macro.pdf'),
                  },
                }}
              />
              <SaveJobControl
                pluginId="hex-macro-cell"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />
              {error && <p className="error-message">{error}</p>}

              <PreviewPane url={previewUrl} alt="Hex macro cell preview" />
              <ValidationReportView report={validationReport} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
