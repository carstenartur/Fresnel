import { useEffect, useState } from 'react';
import {
  downloadPrinterCalibrationResult,
  downloadVariableLineGratingPcl,
  downloadVariableLineGratingPdf,
  downloadVariableLineGratingPng,
  downloadVariableLineGratingSvg,
  fetchPrinterRasterProfiles,
  fetchVariableLineGratingPreviewPng,
  variableLineGratingInfo,
  type PclCompression,
  type PrinterCalibrationResult,
  type PrinterRasterProfile,
  type VariableLineGratingInfo,
  type VariableLineGratingRequest,
} from '../gratingApi';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: VariableLineGratingRequest = {
  widthMm: 190,
  heightMm: 277,
  lineOrientation: 'VERTICAL',
  startPitchUm: 500,
  endPitchUm: 40,
  progression: 'LINEAR_SPATIAL_FREQUENCY',
  progressionDirection: 'NORMAL',
  dutyCycle: 0.5,
  phaseOffsetCycles: 0,
  polarity: 'POSITIVE',
  marginMm: 5,
  annotationSizeMm: 14,
  showAxis: true,
  axisQuantity: 'PITCH_UM',
  tickCount: 9,
  showReferenceBands: true,
  referenceBandSizeMm: 5,
  dpi: 300,
};

interface CalibrationFormState {
  printerModel: string;
  mediumDescription: string;
  qualityMode: string;
  observedDegradationPositionMm: string;
  firstResolvedPitchUm: string;
  minimumUsefulFeatureWidthUm: string;
  observationNotes: string;
  measurementAttachmentReference: string;
}

const EMPTY_CALIBRATION: CalibrationFormState = {
  printerModel: '',
  mediumDescription: '',
  qualityMode: '',
  observedDegradationPositionMm: '',
  firstResolvedPitchUm: '',
  minimumUsefulFeatureWidthUm: '',
  observationNotes: '',
  measurementAttachmentReference: '',
};

export function VariableLineGratingPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<VariableLineGratingRequest>(() =>
    initialJobParameters(initialJob, 'variable-line-grating', DEFAULT));
  const [profiles, setProfiles] = useState<PrinterRasterProfile[]>([]);
  const [profileId, setProfileId] = useState('pcl5e-a4-600-portrait-v1');
  const [compression, setCompression] = useState<PclCompression>('TIFF');
  const [info, setInfo] = useState<VariableLineGratingInfo | null>(null);
  const [calibration, setCalibration] = useState<CalibrationFormState>(EMPTY_CALIBRATION);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    void fetchPrinterRasterProfiles()
      .then((loaded) => {
        if (!active) return;
        setProfiles(loaded);
        if (loaded.length > 0) {
          setProfileId((current) => loaded.some((profile) => profile.id === current)
            ? current
            : loaded[0].id);
        }
      })
      .catch((loadError) => {
        if (!active) return;
        setError(loadError instanceof Error ? loadError.message : String(loadError));
      });
    return () => { active = false; };
  }, []);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await operation();
    } catch (operationError) {
      setError(operationError instanceof Error ? operationError.message : String(operationError));
    } finally {
      setBusy(false);
    }
  };

  const renderPreview = async (parameters: VariableLineGratingRequest) => {
    await run(async () => {
      const [preview, analysis] = await Promise.all([
        fetchVariableLineGratingPreviewPng(parameters),
        variableLineGratingInfo(parameters, profileId),
      ]);
      setPreview(preview);
      setInfo(analysis);
    });
  };

  const selectedProfile = profiles.find((profile) => profile.id === profileId) ?? null;
  const updateCalibration = (field: keyof CalibrationFormState, value: string) => {
    setCalibration((current) => ({ ...current, [field]: value }));
  };

  return (
    <>
      <h2>Variable-line grating</h2>
      <p className="warning info" style={{ marginTop: 0 }}>
        Generate exactly one line family per output. Vertical lines vary across page X;
        horizontal lines vary across page Y. Print PDF, SVG and PCL at 100% with all
        fit-to-page and driver resampling options disabled.
      </p>
      <PluginEditorShell
        pluginId="variable-line-grating"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation, domainValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          const productionReady = structurallyValid && domainValidation?.valid === true;
          const orientationName = normalized?.lineOrientation === 'HORIZONTAL'
            ? 'horizontal'
            : 'vertical';
          const calibrationReady = Boolean(
            selectedProfile
              && normalized
              && calibration.printerModel.trim()
              && calibration.mediumDescription.trim()
              && calibration.qualityMode.trim(),
          );
          return (
            <>
              <fieldset disabled={busy || profiles.length === 0} style={{ marginBottom: 12 }}>
                <legend>Native printer output</legend>
                <label>
                  Trusted printer profile
                  <select value={profileId} onChange={(event) => setProfileId(event.target.value)}>
                    {profiles.map((profile) => (
                      <option key={profile.id} value={profile.id}>
                        {profile.id} · {profile.mediaSize} · {profile.dpiX}×{profile.dpiY} dpi
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  PCL row compression
                  <select
                    value={compression}
                    onChange={(event) => setCompression(event.target.value as PclCompression)}
                  >
                    <option value="TIFF">TIFF / PackBits</option>
                    <option value="NONE">None</option>
                  </select>
                </label>
                {selectedProfile && (
                  <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 0 }}>
                    Page X → device {selectedProfile.pageXAxisMapsTo}; page Y → device{' '}
                    {selectedProfile.pageYAxisMapsTo}. Printable raster{' '}
                    {selectedProfile.printableWidthDots.toLocaleString()} ×{' '}
                    {selectedProfile.printableHeightDots.toLocaleString()} dots.
                  </p>
                )}
              </fieldset>

              {info && (
                <div className="warning info" style={{ fontSize: 12 }}>
                  <strong>{info.lineOrientation === 'VERTICAL' ? 'Vertical' : 'Horizontal'} lines</strong>
                  {' · '}tested device axis {info.testedDeviceAxis} at{' '}
                  {info.selectedAxisDpi.toLocaleString()} dpi
                  {' · '}pitch {format(info.minPitchUm)}–{format(info.maxPitchUm)} µm
                  {' · '}minimum {format(info.minDotsPerPeriod)} dots/period
                  {' · '}{format(info.nominalCycleCount)} integrated cycles
                  <div style={{ marginTop: 4 }}>
                    Thresholds:{' '}
                    {info.thresholdCrossings.map((crossing) => (
                      <span key={crossing.dotsPerPeriod} style={{ marginRight: 8 }}>
                        {crossing.dotsPerPeriod} dots:{' '}
                        {crossing.crossed && crossing.positionMm !== undefined
                          ? `${format(crossing.positionMm)} mm`
                          : 'not crossed'}
                      </span>
                    ))}
                  </div>
                </div>
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
                    run: () => normalized && run(() =>
                      downloadVariableLineGratingPng(
                        normalized,
                        `fresnel-grating-${orientationName}.png`,
                      )),
                  },
                  EXPORT_SVG: {
                    label: 'SVG',
                    disabled: !productionReady,
                    run: () => normalized && run(() =>
                      downloadVariableLineGratingSvg(
                        normalized,
                        `fresnel-grating-${orientationName}.svg`,
                      )),
                  },
                  EXPORT_PDF: {
                    label: 'PDF A4',
                    disabled: !productionReady,
                    run: () => normalized && run(() =>
                      downloadVariableLineGratingPdf(
                        normalized,
                        `fresnel-grating-${orientationName}.pdf`,
                      )),
                  },
                  EXPORT_PCL: {
                    label: 'PCL 1-bit',
                    disabled: !productionReady || !selectedProfile,
                    title: 'Native device-dot raster; print without a graphics-driver conversion step.',
                    run: () => {
                      if (!normalized || !selectedProfile) return;
                      return run(() => downloadVariableLineGratingPcl(
                        normalized,
                        selectedProfile.id,
                        compression,
                        `fresnel-grating-${orientationName}.pcl`,
                      ));
                    },
                  },
                }}
              />
              <SaveJobControl
                pluginId="variable-line-grating"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />

              <details style={{ marginTop: 14, marginBottom: 14 }}>
                <summary><strong>Record physical calibration result</strong></summary>
                <fieldset disabled={busy || !selectedProfile || !normalized} style={{ marginTop: 10 }}>
                  <legend>Orientation-specific observation</legend>
                  <label>
                    Printer model
                    <input
                      value={calibration.printerModel}
                      onChange={(event) => updateCalibration('printerModel', event.target.value)}
                    />
                  </label>
                  <label>
                    Medium / transparency
                    <input
                      value={calibration.mediumDescription}
                      onChange={(event) => updateCalibration('mediumDescription', event.target.value)}
                    />
                  </label>
                  <label>
                    Driver quality mode
                    <input
                      value={calibration.qualityMode}
                      onChange={(event) => updateCalibration('qualityMode', event.target.value)}
                    />
                  </label>
                  <label>
                    Observed degradation position (mm)
                    <input
                      type="number"
                      min="0"
                      step="0.1"
                      value={calibration.observedDegradationPositionMm}
                      onChange={(event) => updateCalibration(
                        'observedDegradationPositionMm', event.target.value)}
                    />
                  </label>
                  <label>
                    First repeatably resolved pitch (µm)
                    <input
                      type="number"
                      min="0"
                      step="0.1"
                      value={calibration.firstResolvedPitchUm}
                      onChange={(event) => updateCalibration('firstResolvedPitchUm', event.target.value)}
                    />
                  </label>
                  <label>
                    Minimum useful feature width (µm)
                    <input
                      type="number"
                      min="0"
                      step="0.1"
                      value={calibration.minimumUsefulFeatureWidthUm}
                      onChange={(event) => updateCalibration(
                        'minimumUsefulFeatureWidthUm', event.target.value)}
                    />
                  </label>
                  <label>
                    Observation notes
                    <textarea
                      value={calibration.observationNotes}
                      onChange={(event) => updateCalibration('observationNotes', event.target.value)}
                    />
                  </label>
                  <label>
                    Photo / measurement attachment reference
                    <input
                      value={calibration.measurementAttachmentReference}
                      onChange={(event) => updateCalibration(
                        'measurementAttachmentReference', event.target.value)}
                    />
                  </label>
                  <button
                    className="secondary"
                    disabled={!calibrationReady}
                    onClick={() => {
                      if (!normalized || !selectedProfile) return;
                      const result = calibrationResult(
                        calibration, normalized, selectedProfile);
                      return run(() => downloadPrinterCalibrationResult(
                        result,
                        `fresnel-printer-calibration-${orientationName}.json`,
                      ));
                    }}
                  >
                    Export calibration result JSON
                  </button>
                  <p style={{ fontSize: 12, color: '#6b7280' }}>
                    Export one record for vertical lines and one for horizontal lines;
                    their tested device axes are intentionally kept separate.
                  </p>
                </fieldset>
              </details>

              {error && <p className="error-message" role="alert">{error}</p>}
              <PreviewPane url={previewUrl} alt="Variable-line grating preview" />
              <ValidationReportView report={domainValidation} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}

function calibrationResult(
  form: CalibrationFormState,
  parameters: VariableLineGratingRequest,
  profile: PrinterRasterProfile,
): PrinterCalibrationResult {
  const firstResolvedPitchUm = optionalNumber(form.firstResolvedPitchUm);
  const minimumUsefulFeatureWidthUm = optionalNumber(form.minimumUsefulFeatureWidthUm);
  return {
    printerModel: form.printerModel.trim(),
    printerProfileId: profile.id,
    printerProfileVersion: profile.version,
    mediumDescription: form.mediumDescription.trim(),
    qualityMode: form.qualityMode.trim(),
    nominalDpiX: profile.dpiX,
    nominalDpiY: profile.dpiY,
    pageOrientation: profile.pageOrientation,
    pageXAxisMapsTo: profile.pageXAxisMapsTo,
    pageYAxisMapsTo: profile.pageYAxisMapsTo,
    lineOrientation: parameters.lineOrientation,
    testedDeviceAxis: parameters.lineOrientation === 'VERTICAL'
      ? profile.pageXAxisMapsTo
      : profile.pageYAxisMapsTo,
    observedDegradationPositionMm: optionalNumber(form.observedDegradationPositionMm),
    firstResolvedPitchUm,
    minimumUsefulFeatureWidthUm,
    firstResolvedLinesPerMm: firstResolvedPitchUm
      ? 1000 / firstResolvedPitchUm
      : undefined,
    effectiveDpi: minimumUsefulFeatureWidthUm
      ? 25_400 / minimumUsefulFeatureWidthUm
      : undefined,
    observationNotes: form.observationNotes.trim(),
    measurementAttachmentReference: form.measurementAttachmentReference.trim(),
    measuredAt: new Date().toISOString(),
  };
}

function optionalNumber(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function format(value: number): string {
  if (Math.abs(value) >= 100 || Math.abs(value - Math.round(value)) < 0.05) {
    return value.toFixed(0);
  }
  return value.toFixed(1);
}
