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
import type { PluginParameterValidation } from '../pluginSchemaApi';
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
  const [structuralValidation, setStructuralValidation] =
    useState<PluginParameterValidation<SingleZonePlateRequest> | null>(null);
  const [metrics, setMetrics] = useState<DesignMetrics | null>(null);
  const [qualityReport, setQualityReport] = useState<OpticalQualityReport | null>(null);
  const [warnings, setWarnings] = useState<Warning[]>([]);
  const [valid, setValid] = useState(false);
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
  const initialPreviewRendered = useRef(false);

  // A changed public parameter object invalidates the previous normalized/domain
  // result immediately. The shell will publish a fresh canonical result after its
  // debounce; no domain endpoint receives an unvalidated intermediate value.
  useEffect(() => {
    lastValidationId.current += 1;
    setStructuralValidation(null);
    setMetrics(null);
    setQualityReport(null);
    setWarnings([]);
    setValid(false);
    setError(null);
  }, [request]);

  useEffect(() => {
    if (structuralValidation?.valid !== true
        || !structuralValidation.normalizedParameters) {
      return;
    }

    const normalized = structuralValidation.normalizedParameters;
    const validationId = ++lastValidationId.current;
    let active = true;

    const runOpticalValidation = async () => {
      try {
        const response = await validate(normalized);
        if (!active || validationId !== lastValidationId.current) return;

        setMetrics(response.metrics);
        setQualityReport(response.qualityReport ?? null);
        setWarnings(response.warnings);
        setValid(response.valid);
        setError(null);

        // Preview is a design aid, not a production release. It may be requested
        // for any structurally valid parameter object; automatic first rendering
        // remains limited to the ordinary valid default/happy path.
        if (!initialPreviewRendered.current && response.valid) {
          initialPreviewRendered.current = true;
          setPreviewUrl(await fetchPreviewPng(normalized));
        }
      } catch (validationError) {
        if (!active || validationId !== lastValidationId.current) return;
        setMetrics(null);
        setQualityReport(null);
        setWarnings([]);
        setValid(false);
        setError(validationError instanceof Error
          ? validationError.message
          : String(validationError));
      }
    };

    void runOpticalValidation();
    return () => { active = false; };
  }, [structuralValidation, setPreviewUrl]);

  const renderPreview = async () => {
    const normalized = structuralValidation?.valid
      ? structuralValidation.normalizedParameters
      : undefined;
    if (!normalized) return;

    setBusy(true);
    setError(null);
    try {
      setPreviewUrl(await fetchPreviewPng(normalized));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  const sizeEstimatePx = useMemo(() => {
    const pixelMm = 25.4 / request.dpi;
    return Math.round(request.apertureDiameterMm / pixelMm);
  }, [request.apertureDiameterMm, request.dpi]);

  const updateExperimentSetup = (patch: Partial<ExperimentSetup>) =>
    setExperimentSetup((current) => ({ ...current, ...patch }));
  const updateMeasuredFocus = (patch: Partial<MeasuredFocus>) =>
    setMeasuredFocus((current) => ({ ...current, ...patch }));

  const buildExperimentRecord = (
    validationReport: DesignValidationReport,
    normalized: SingleZonePlateRequest,
  ): ExperimentRecord => {
    const photoReferences = photoReferenceText
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
    const measurement: MeasurementResult = {
      targetFocalLengthMm: normalized.focalLengthMm,
      measuredFoci: [{
        ...measuredFocus,
        measuredFocalLengthMm: measuredFocus.measuredFocalLengthMm ?? normalized.focalLengthMm,
      }],
    };
    return {
      designId: experimentDesignId.trim() || undefined,
      pluginId: validationReport.pluginId,
      parameterHash: validationReport.parameterHash,
      designDocument: { kind: 'single', version: 1, payload: normalized },
      validationReport,
      setup: {
        ...experimentSetup,
        nominalDpi: experimentSetup.nominalDpi ?? normalized.dpi,
        photoReferences,
      },
      measurement,
    };
  };

  const runExperimentComparison = async (
    validationReport: DesignValidationReport,
    normalized: SingleZonePlateRequest,
  ) => {
    setComparingExperiment(true);
    setExperimentError(null);
    try {
      const record = await compareExperiment(buildExperimentRecord(validationReport, normalized));
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
        onStructuralValidation={setStructuralValidation}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, shellValidation, domainValidation) => {
          const extensions = new Set(schema.uiSchema.extensions ?? []);
          const normalized = shellValidation?.valid
            ? shellValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          const productionReady = structurallyValid
            && valid
            && domainValidation?.valid === true;
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
                    disabled: !structurallyValid,
                    run: renderPreview,
                  },
                  EXPORT_PNG: {
                    label: 'PNG',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadExportPng(normalized, 'fresnel-zone-plate.png'),
                  },
                  EXPORT_SVG: {
                    label: 'SVG',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadExportSvg(normalized, 'fresnel-zone-plate.svg'),
                  },
                  EXPORT_PDF: {
                    label: 'PDF',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadExportPdf(normalized, sheet, 'fresnel-zone-plate.pdf'),
                  },
                  EXPORT_DXF: {
                    label: 'DXF',
                    title: 'DXF outlines for laser cutters / pen plotters',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadExportDxf(normalized, 'fresnel-zone-plate.dxf'),
                  },
                  EXPORT_GERBER: {
                    label: 'Gerber',
                    title: 'Gerber RS-274X for PCB-style fabrication',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadExportGerber(normalized, 'fresnel-zone-plate.gbr'),
                  },
                  PRINTABILITY_ANALYSIS: {
                    label: 'Calibration PDF',
                    disabled: !productionReady,
                    run: () => normalized && downloadCalibrationPdf({
                      dpi: normalized.dpi,
                      printScale: 1,
                      wavelengthNm: normalized.wavelengthNm,
                      focalLengthMm: normalized.focalLengthMm,
                    }, sheet, 'fresnel-calibration-sheet.pdf'),
                  },
                }}
              />

              <SaveJobControl
                pluginId="zone-plate"
                parameters={normalized ?? null}
                disabled={!structurallyValid || busy}
              />
              {error && <p className="error-message">{error}</p>}

              {extensions.has('validation') && (
                <div data-editor-extension="validation">
                  {(metrics || domainValidation) && (
                    <ZonePlateWarnings
                      warnings={warnings}
                      valid={valid && domainValidation?.valid === true}
                    />
                  )}
                  <PreviewPane url={previewUrl} alt="Fresnel zone plate preview" />
                  {metrics && <ZonePlateMetrics metrics={metrics} />}
                  {qualityReport && <ZonePlateQualityReport report={qualityReport} />}
                  <ValidationReportView report={domainValidation} />
                </div>
              )}

              {extensions.has('experiment') && (
                <ExperimentValidationPanel
                  req={normalized ?? request}
                  validationReport={productionReady ? domainValidation : null}
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
                  onCompare={() => domainValidation && normalized
                    ? runExperimentComparison(domainValidation, normalized)
                    : Promise.resolve()}
                  onExportJson={async () => {
                    try {
                      setExperimentError(null);
                      if (!domainValidation || !normalized) {
                        throw new Error('Validation report is not ready yet.');
                      }
                      await downloadExperimentJson(
                        buildExperimentRecord(domainValidation, normalized),
                      );
                    } catch (exportError) {
                      setExperimentError(exportError instanceof Error
                        ? exportError.message
                        : String(exportError));
                    }
                  }}
                  onExportMarkdown={async () => {
                    try {
                      setExperimentError(null);
                      if (!domainValidation || !normalized) {
                        throw new Error('Validation report is not ready yet.');
                      }
                      await downloadExperimentMarkdown(
                        buildExperimentRecord(domainValidation, normalized),
                      );
                    } catch (exportError) {
                      setExperimentError(exportError instanceof Error
                        ? exportError.message
                        : String(exportError));
                    }
                  }}
                />
              )}

              {extensions.has('propagation') && supportsPropagation && (
                <PropagationPanel
                  req={normalized ?? request}
                  disabled={!structurallyValid}
                />
              )}
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
