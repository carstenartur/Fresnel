import { useEffect, useState } from 'react';
import {
  downloadVariableLineGratingPcl,
  downloadVariableLineGratingPdf,
  downloadVariableLineGratingPng,
  downloadVariableLineGratingSvg,
  fetchPrinterRasterProfiles,
  fetchVariableLineGratingPreviewPng,
  variableLineGratingInfo,
  type PclCompression,
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

export function VariableLineGratingPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<VariableLineGratingRequest>(() =>
    initialJobParameters(initialJob, 'variable-line-grating', DEFAULT));
  const [profiles, setProfiles] = useState<PrinterRasterProfile[]>([]);
  const [profileId, setProfileId] = useState('pcl5e-a4-600-portrait-v1');
  const [compression, setCompression] = useState<PclCompression>('TIFF');
  const [info, setInfo] = useState<VariableLineGratingInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  useEffect(() => {
    let active = true;
    void fetchPrinterRasterProfiles()
      .then((loaded) => {
        if (!active) return;
        setProfiles(loaded);
        if (loaded.length > 0 && !loaded.some((profile) => profile.id === profileId)) {
          setProfileId(loaded[0].id);
        }
      })
      .catch((loadError) => {
        if (!active) return;
        setError(loadError instanceof Error ? loadError.message : String(loadError));
      });
    return () => { active = false; };
  }, [profileId]);

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
  const testedPageAxis = request.lineOrientation === 'VERTICAL' ? 'page X' : 'page Y';

  return (
    <>
      <h2>Variable-line grating</h2>
      <p className="warning info" style={{ marginTop: 0 }}>
        Generate exactly one line family per output. Vertical lines vary across {testedPageAxis};
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
                    run: () => normalized && selectedProfile && run(() =>
                      downloadVariableLineGratingPcl(
                        normalized,
                        selectedProfile.id,
                        compression,
                        `fresnel-grating-${orientationName}.pcl`,
                      )),
                  },
                }}
              />
              <SaveJobControl
                pluginId="variable-line-grating"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />
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

function format(value: number): string {
  if (Math.abs(value) >= 100 || Math.abs(value - Math.round(value)) < 0.05) {
    return value.toFixed(0);
  }
  return value.toFixed(1);
}
