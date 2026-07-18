import { useEffect, useMemo, useRef, useState } from 'react';
import {
  compareExperiment,
  downloadCalibrationPdf,
  downloadExperimentJson,
  downloadExperimentMarkdown,
  downloadExportDxf,
  downloadExportGerber,
  downloadExportPdf,
  downloadExportPng,
  downloadExportSvg,
  fetchPreviewPng,
  validate,
  validatePlugin,
  type DesignMetrics,
  type DesignValidationReport,
  type ExperimentalComparison,
  type ExperimentRecord,
  type ExperimentSetup,
  type MeasurementResult,
  type MeasuredFocus,
  type OpticalQualityReport,
  type SingleZonePlateRequest,
  type Warning,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import {
  ExperimentValidationPanel,
  PropagationPanel,
  ZonePlateMetrics,
  ZonePlateQualityReport,
  ZonePlateWarnings,
} from './ZonePlateAdvancedPanels';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const SHEETS = ['FIT', 'A4', 'A3', 'A2', 'A1', 'A0'] as const;

const FALLBACK_DEFAULTS: SingleZonePlateRequest = {
  apertureDiameterMm: 10,
  focalLengthMm: 1000,
  wavelengthNm: 550,
  dpi: 1200,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const DEFAULT_EXPERIMENT_SETUP: ExperimentSetup = {
  printerModel: '',
  materialType: '',
  exposureSettings: '',
  lightSourceType: '',
  spectrumEstimate: '',
  environmentalNotes: '',
  photoReferences: [],
};

const DEFAULT_MEASURED_FOCUS: MeasuredFocus = {
  label: 'Primary focus',
  focusRating: '',
  notes: '',
};

/**
 * Schema-driven Zone Plate editor.
 *
 * <p>Standard parameter controls, defaults and production actions come from the
 * versioned plugin contract. Optical reports, experimental validation and
 * propagation remain trusted editor extensions that operate on the exact same
 * serialisable parameter object.</p>
 */
export function ZonePlatePanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<SingleZonePlateRequest>(() =>
    initialJobParameters(initialJob, 'zone-plate', FALLBACK_DEFAULTS));
  const [metrics, setMetrics] = useState<DesignMetrics | null>(null);
  const [qualityReport, setQualityReport] = useState<OpticalQualityReport | null>(null);
  const [warnings, setWarnings] = useState<Warning[]>([]);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [valid, setValid] = useState(true);
  const [previewUrl, setPreviewUrl] = useBlobUrl();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sheet, setSheet] = useState<(typeof SHEETS)[number]>('FIT');
  const [experimentDesignId, setExperimentDesignId] = useState('');
  const [experimentSetup, setExperimentSetup] = useState<ExperimentSetup>(DEFAULT_EXPERIMENT_SETUP);
  const [measuredFocus, setMeasuredFocus] = useState<MeasuredFocus>(DEFAULT_MEASURED_FOCUS);
  const [photoReferenceText, setPhotoReferenceText] = useState('');
  const [comparison, setComparison] = useState<ExperimentalComparison | null>(null);
  const [experimentError, setExperimentError] = useState<string | null>(null);
  const [comparingExperiment, setComparingExperiment] = useState(false);
  const lastValidationId = useRef(0);

  useEffect(() => {
    const validationId = ++lastValidationId.current;
    const timer = window.setTimeout(async () => {
      try {
        const response = await validate(request);
        if (validationId !== lastValidationId.current) return;
        const report = await validatePlugin('zone-plate', request);
        if (validationId !== lastValidationId.current) return;
        setMetrics(response.metrics);
        setQualityReport(response.qualityReport ?? null);
        setWarnings(response.warnings);
        setValid(response.valid);
        setValidationReport(report);
        setError(null);
      } catch (validationError) {
        if (validationId !== lastValidationId.current) return;
        setMetrics(null);
        setQualityReport(null);
        setWarnings([]);
        setValidationReport(null);
        setValid(false);
        setError(validationError instanceof Error
          ? validationError.message
          : String(validationError));
      }
    }, 200);
    return () => window.clearTimeout(timer);
  }, [request]);

  const renderPreview = async () => {
    setBusy(true);
    setError(null);
    try {
      setPreviewUrl(await fetchPreviewPng(request));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    void renderPreview();
    // The editor is remounted whenever an imported job revision changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sizeEstimatePx = useMemo(() => {
    const pixelMm = 25.4 / request.dpi;
    return Math.round(request.apertureDiameterMm / pixelMm);
  }, [request.apertureDiameterMm, request.dpi]);

  const updateExperimentSetup = (patch: Partial<ExperimentSetup>) =>
    setExperimentSetup((current) => ({ ...current, ...patch }));
  const updateMeasuredFocus = (patch: Partial<MeasuredFocus>) =>
    setMeasuredFocus((current) => ({ ...current, ...patch }));

  const buildExperimentRecord = (): ExperimentRecord => {
    if (!validationReport) throw new Error('Validation report is not ready yet.');
    const photoReferences = photoReferenceText
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
    const measurement: MeasurementResult = {
      targetFocalLengthMm: request.focalLengthMm,
      measuredFoci: [{
        ...measuredFocus,
        measuredFocalLengthMm: measuredFocus.measuredFocalLengthMm ?? request.focalLengthMm,
      }],
    };
    return {
      designId: experimentDesignId.trim() || undefined,
      pluginId: validationReport.pluginId,
      parameterHash: validationReport.parameterHash,
      designDocument: { kind: 'single', version: 1, payload: request },
      validationReport,
      setup: {
        ...experimentSetup,
        nominalDpi: experimentSetup.nominalDpi ?? request.dpi,
        photoReferences,
      },
      measurement,
    };
  };

  const runExperimentComparison = async () => {
    setComparingExperiment(true);
    setExperimentError(null);
    try {
      const record = await compareExperiment(buildExperimentRecord());
      setComparison(record.comparison ?? null);
    } catch (comparisonError) {
      setExperimentError(comparisonError instanceof Error
        ? comparisonError.message
        : String(comparisonError));
      setComparison(null);
    } finally {
      setComparingExperiment(false);
    }
  };

  return (
    <>
      <h2>Zone plate</h2>
      <PluginEditorShell
        pluginId="zone-plate"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation) => {
          const extensions = new Set(schema.uiSchema.extensions ?? []);
          const structurallyValid = structuralValidation?.valid === true;
          const fullyValid = structurallyValid && valid;
          const supportsPropagation = schema.capabilities.includes('PROPAGATION_PREVIEW');

          return (
            <>
              {extensions.has('production-actions') && (
                <div className="field" data-editor-extension="production-actions">
                  <label htmlFor="zone-plate-pdf-sheet">PDF sheet size</label>
                  <select
                    id="zone-plate-pdf-sheet"
                    value={sheet}
                    disabled={busy}
                    onChange={(event) =>
                      setSheet(event.target.value as (typeof SHEETS)[number])}
                  >
                    {SHEETS.map((value) => (
                      <option key={value} value={value}>{value}</option>
                    ))}
                  </select>
                </div>
              )}

              <p style={{ fontSize: 12, color: '#6b7280' }}>
                Estimated image size: {sizeEstimatePx.toLocaleString()} ×{' '}
                {sizeEstimatePx.toLocaleString()} px
              </p>

              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Rendering…' : 'Render preview',
                    primary: true,
                    disabled: !fullyValid,
                    run: renderPreview,
                  },
                  EXPORT_PNG: {
                    label: 'PNG',
                    disabled: !fullyValid,
                    run: () => downloadExportPng(request, 'fresnel-zone-plate.png'),
                  },
                  EXPORT_SVG: {
                    label: 'SVG',
                    disabled: !fullyValid,
                    run: () => downloadExportSvg(request, 'fresnel-zone-plate.svg'),
                  },
                  EXPORT_PDF: {
                    label: 'PDF',
                    disabled: !fullyValid,
                    run: () => downloadExportPdf(request, sheet, 'fresnel-zone-plate.pdf'),
                  },
                  EXPORT_DXF: {
                    label: 'DXF',
                    title: 'DXF outlines for laser cutters / pen plotters',
                    disabled: !fullyValid,
                    run: () => downloadExportDxf(request, 'fresnel-zone-plate.dxf'),
                  },
                  EXPORT_GERBER: {
                    label: 'Gerber',
                    title: 'Gerber RS-274X for PCB-style fabrication',
                    disabled: !fullyValid,
                    run: () => downloadExportGerber(request, 'fresnel-zone-plate.gbr'),
                  },
                  PRINTABILITY_ANALYSIS: {
                    label: 'Calibration PDF',
                    disabled: !fullyValid,
                    run: () => downloadCalibrationPdf({
                      dpi: request.dpi,
                      printScale: 1,
                      wavelengthNm: request.wavelengthNm,
                      focalLengthMm: request.focalLengthMm,
                    }, sheet, 'fresnel-calibration-sheet.pdf'),
                  },
                }}
              />

              <SaveJobControl
                pluginId="zone-plate"
                parameters={request}
                disabled={!fullyValid || busy}
              />
              {error && <p className="error-message">{error}</p>}

              {extensions.has('validation') && (
                <div data-editor-extension="validation">
                  <ZonePlateWarnings warnings={warnings} valid={valid} />
                  <PreviewPane url={previewUrl} alt="Fresnel zone plate preview" />
                  {metrics && <ZonePlateMetrics metrics={metrics} />}
                  {qualityReport && <ZonePlateQualityReport report={qualityReport} />}
                  <ValidationReportView report={validationReport} />
                </div>
              )}

              {extensions.has('experiment') && (
                <ExperimentValidationPanel
                  req={request}
                  validationReport={fullyValid ? validationReport : null}
                  designId={experimentDesignId}
                  setDesignId={setExperimentDesignId}
                  setup={experimentSetup}
                  updateSetup={updateExperimentSetup}
                  measuredFocus={measuredFocus}
                  updateMeasuredFocus={updateMeasuredFocus}
                  photoReferenceText={photoReferenceText}
                  setPhotoReferenceText={setPhotoReferenceText}
                  comparison={comparison}
                  error={experimentError}
                  loading={comparingExperiment}
                  onCompare={runExperimentComparison}
                  onExportJson={async () => {
                    try {
                      setExperimentError(null);
                      await downloadExperimentJson(buildExperimentRecord());
                    } catch (exportError) {
                      setExperimentError(exportError instanceof Error
                        ? exportError.message
                        : String(exportError));
                    }
                  }}
                  onExportMarkdown={async () => {
                    try {
                      setExperimentError(null);
                      await downloadExperimentMarkdown(buildExperimentRecord());
                    } catch (exportError) {
                      setExperimentError(exportError instanceof Error
                        ? exportError.message
                        : String(exportError));
                    }
                  }}
                />
              )}

              {extensions.has('propagation') && supportsPropagation && (
                <PropagationPanel req={request} disabled={!fullyValid} />
              )}
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
