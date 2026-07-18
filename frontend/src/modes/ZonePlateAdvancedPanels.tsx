import { useEffect, useRef, useState } from 'react';
import {
  fetchPropagatePng,
  type DesignMetrics,
  type DesignValidationReport,
  type ExperimentalComparison,
  type ExperimentSetup,
  type MeasuredFocus,
  type OpticalQualityReport,
  type PropagationMode,
  type SingleZonePlateRequest,
  type Warning,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

export function ZonePlateWarnings({ warnings, valid }: { warnings: Warning[]; valid: boolean }) {
  if (warnings.length === 0) {
    return (
      <div className="warning info" style={{ marginTop: 16 }}>
        Design is {valid ? 'valid' : 'invalid'} — no warnings.
      </div>
    );
  }
  return (
    <div style={{ marginTop: 16 }}>
      {warnings.map((warning) => (
        <div
          key={warning.code}
          className={`warning ${warning.severity === 'ERROR' ? 'error' : warning.severity === 'INFO' ? 'info' : ''}`}
        >
          <strong>{warning.code}:</strong> {warning.message}
        </div>
      ))}
    </div>
  );
}

export function ZonePlateMetrics({ metrics }: { metrics: DesignMetrics }) {
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Design metrics</h3>
      <dl>
        <dt>Outer zone width</dt><dd>{metrics.outerZoneWidthMicrons.toFixed(2)} µm</dd>
        <dt>Printer pixel</dt><dd>{metrics.printerPixelMicrons.toFixed(2)} µm</dd>
        <dt>Pixels per outer zone</dt><dd>{metrics.pixelsPerOuterZone.toFixed(2)}</dd>
        <dt>Number of zones</dt><dd>{metrics.numberOfZones}</dd>
        <dt>Avg. transmission</dt><dd>{(metrics.estimatedTransmission * 100).toFixed(0)} %</dd>
        <dt>1st-order efficiency</dt><dd>{(metrics.estimatedFirstOrderEfficiency * 100).toFixed(2)} %</dd>
      </dl>

      {metrics.chromaticShifts && metrics.chromaticShifts.length > 0 && (
        <>
          <h3 style={{ marginTop: 12 }}>Chromatic focal shift</h3>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead><tr>
              <th style={{ textAlign: 'left' }}>λ (nm)</th>
              <th style={{ textAlign: 'right' }}>f (mm)</th>
            </tr></thead>
            <tbody>
              {metrics.chromaticShifts.map((shift) => (
                <tr key={shift.wavelengthNm}>
                  <td>{shift.wavelengthNm.toFixed(0)}</td>
                  <td style={{ textAlign: 'right' }}>{shift.focalLengthMm.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {metrics.defocusBlurs && metrics.defocusBlurs.length > 0 && (
        <>
          <h3 style={{ marginTop: 12 }}>Defocus blur (circle of confusion)</h3>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead><tr>
              <th style={{ textAlign: 'left' }}>Wall distance (mm)</th>
              <th style={{ textAlign: 'right' }}>Blur Ø (mm)</th>
            </tr></thead>
            <tbody>
              {metrics.defocusBlurs.map((blur) => (
                <tr key={blur.wallDistanceMm}>
                  <td>{blur.wallDistanceMm.toFixed(0)}</td>
                  <td style={{ textAlign: 'right' }}>{blur.blurDiameterMm.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

export function ZonePlateQualityReport({ report }: { report: OpticalQualityReport }) {
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Optical quality report</h3>
      <dl>
        <dt>Design wavelength</dt><dd>{report.wavelengthNm.toFixed(1)} nm</dd>
        <dt>Focal length</dt><dd>{report.focalLengthMm.toFixed(2)} mm</dd>
        <dt>Aperture diameter</dt><dd>{report.apertureDiameterMm.toFixed(2)} mm</dd>
        <dt>Numerical aperture (NA)</dt><dd>{report.numericalAperture.toFixed(4)}</dd>
        <dt>f-number (F#)</dt><dd>{report.fNumber.toFixed(1)}</dd>
        <dt>Airy disk diameter</dt><dd>{report.airyDiskDiameterMicrons.toFixed(2)} µm</dd>
        <dt>Rayleigh angular resolution</dt>
        <dd>{(report.rayleighAngularResolutionRad * 1e6).toFixed(3)} µrad</dd>
        <dt>Depth of focus (DoF)</dt><dd>{report.depthOfFocusMicrons.toFixed(1)} µm</dd>
        <dt>Outermost zone width</dt><dd>{report.outermostZoneWidthMicrons.toFixed(2)} µm</dd>
        <dt>
          Chromatic focal shift
          <span style={{ fontWeight: 'normal', fontSize: 11, color: '#6b7280' }}>
            {' '}({report.chromaticRangeMinNm.toFixed(0)}–{report.chromaticRangeMaxNm.toFixed(0)} nm)
          </span>
        </dt>
        <dd>{report.chromaticFocalShiftMm.toFixed(2)} mm</dd>
      </dl>
    </div>
  );
}

export interface ExperimentValidationPanelProps {
  req: SingleZonePlateRequest;
  validationReport: DesignValidationReport | null;
  designId: string;
  setDesignId: (value: string) => void;
  setup: ExperimentSetup;
  updateSetup: (patch: Partial<ExperimentSetup>) => void;
  measuredFocus: MeasuredFocus;
  updateMeasuredFocus: (patch: Partial<MeasuredFocus>) => void;
  photoReferenceText: string;
  setPhotoReferenceText: (value: string) => void;
  comparison: ExperimentalComparison | null;
  error: string | null;
  loading: boolean;
  onCompare: () => Promise<void>;
  onExportJson: () => Promise<void>;
  onExportMarkdown: () => Promise<void>;
}

export function ExperimentValidationPanel({
  req,
  validationReport,
  designId,
  setDesignId,
  setup,
  updateSetup,
  measuredFocus,
  updateMeasuredFocus,
  photoReferenceText,
  setPhotoReferenceText,
  comparison,
  error,
  loading,
  onCompare,
  onExportJson,
  onExportMarkdown,
}: ExperimentValidationPanelProps) {
  return (
    <div className="metrics" style={{ marginTop: 24 }} data-editor-extension="experiment">
      <h3>Experimental validation</h3>
      <p style={{ margin: '0 0 12px', fontSize: 12, color: '#6b7280' }}>
        Uses the currently selected single-zone-plate design as the experiment source.
        {validationReport && (
          <> Parameter hash: <code>{validationReport.parameterHash.slice(0, 12)}</code></>
        )}
      </p>

      <div className="field">
        <label htmlFor="experiment-design-id">Design id (optional)</label>
        <input
          id="experiment-design-id"
          value={designId}
          onChange={(event) => setDesignId(event.target.value)}
          placeholder="saved design UUID or lab notebook id"
        />
      </div>

      <h4 style={{ margin: '8px 0', fontSize: 13 }}>Print and illumination setup</h4>
      <div className="field">
        <label htmlFor="experiment-printer-model">Printer model</label>
        <input
          id="experiment-printer-model"
          value={setup.printerModel ?? ''}
          onChange={(event) => updateSetup({ printerModel: event.target.value })}
        />
      </div>
      <NumberField
        label="Nominal DPI"
        value={setup.nominalDpi ?? req.dpi}
        min={1}
        step={1}
        onChange={(value) => updateSetup({ nominalDpi: value })}
      />
      <NumberField
        label="Effective DPI"
        value={setup.effectiveDpi ?? req.dpi}
        min={1}
        step={1}
        onChange={(value) => updateSetup({ effectiveDpi: value })}
      />
      <div className="field">
        <label htmlFor="experiment-material">Material / foil type</label>
        <input
          id="experiment-material"
          value={setup.materialType ?? ''}
          onChange={(event) => updateSetup({ materialType: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-exposure">Exposure / print settings</label>
        <input
          id="experiment-exposure"
          value={setup.exposureSettings ?? ''}
          onChange={(event) => updateSetup({ exposureSettings: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-light-source">Light source type</label>
        <input
          id="experiment-light-source"
          value={setup.lightSourceType ?? ''}
          onChange={(event) => updateSetup({ lightSourceType: event.target.value })}
        />
      </div>
      <NumberField
        label="Measured wavelength estimate (nm)"
        value={setup.wavelengthNm ?? req.wavelengthNm}
        min={1}
        step={1}
        onChange={(value) => updateSetup({ wavelengthNm: value })}
      />
      <div className="field">
        <label htmlFor="experiment-spectrum">Spectrum estimate</label>
        <input
          id="experiment-spectrum"
          value={setup.spectrumEstimate ?? ''}
          onChange={(event) => updateSetup({ spectrumEstimate: event.target.value })}
        />
      </div>

      <h4 style={{ margin: '16px 0 8px', fontSize: 13 }}>Measured result</h4>
      <p style={{ margin: '0 0 8px', fontSize: 12, color: '#6b7280' }}>
        Target focal length from the selected design: {req.focalLengthMm.toFixed(2)} mm
      </p>
      <NumberField
        label="Measured focal length (mm)"
        value={measuredFocus.measuredFocalLengthMm ?? req.focalLengthMm}
        min={0.001}
        step={0.1}
        onChange={(value) => updateMeasuredFocus({ measuredFocalLengthMm: value })}
      />
      <div className="field">
        <label htmlFor="experiment-spot-size">Measured spot size (µm)</label>
        <input
          id="experiment-spot-size"
          type="number"
          value={measuredFocus.measuredSpotSizeMicrons ?? ''}
          min={0.001}
          step={1}
          onChange={(event) => {
            const parsed = Number.parseFloat(event.target.value);
            updateMeasuredFocus({
              measuredSpotSizeMicrons: event.target.value === '' || Number.isNaN(parsed)
                ? undefined
                : parsed,
            });
          }}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-focus-rating">Qualitative focus rating</label>
        <input
          id="experiment-focus-rating"
          value={measuredFocus.focusRating ?? ''}
          onChange={(event) => updateMeasuredFocus({ focusRating: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-environment">Environmental notes</label>
        <textarea
          id="experiment-environment"
          rows={3}
          value={setup.environmentalNotes ?? ''}
          onChange={(event) => updateSetup({ environmentalNotes: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-photo-refs">Photo references (one per line)</label>
        <textarea
          id="experiment-photo-refs"
          rows={3}
          value={photoReferenceText}
          onChange={(event) => setPhotoReferenceText(event.target.value)}
          placeholder={'focus-setup.jpg\nspot-closeup.jpg'}
        />
      </div>
      <div className="field">
        <label htmlFor="experiment-notes">Measurement notes</label>
        <textarea
          id="experiment-notes"
          rows={3}
          value={measuredFocus.notes ?? ''}
          onChange={(event) => updateMeasuredFocus({ notes: event.target.value })}
        />
      </div>

      <div className="actions">
        <button
          className="secondary"
          onClick={() => void onCompare()}
          disabled={!validationReport || loading}
        >
          {loading ? 'Comparing…' : 'Compare with theory'}
        </button>
        <button
          className="secondary"
          onClick={() => void onExportJson()}
          disabled={!validationReport || loading}
        >
          Export JSON
        </button>
        <button
          className="secondary"
          onClick={() => void onExportMarkdown()}
          disabled={!validationReport || loading}
        >
          Export Markdown
        </button>
      </div>

      {error && <p className="error-message" style={{ marginTop: 8 }}>{error}</p>}
      {comparison && (
        <div className="warning info" style={{ marginTop: 12, marginBottom: 0 }}>
          <strong>Comparison:</strong> {comparison.summary}
        </div>
      )}
    </div>
  );
}

export function PropagationPanel({ req }: { req: SingleZonePlateRequest }) {
  const [zMm, setZMm] = useState(req.focalLengthMm);
  const zMmEditedRef = useRef(false);
  const [mode, setMode] = useState<PropagationMode>('FRESNEL_TF');
  const [propagationUrl, setPropagationUrl] = useBlobUrl();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!zMmEditedRef.current) setZMm(req.focalLengthMm);
  }, [req.focalLengthMm]);

  const renderPropagation = async () => {
    setLoading(true);
    setError(null);
    try {
      setPropagationUrl(await fetchPropagatePng({ base: req, zMm, mode }));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ marginTop: 24 }} data-editor-extension="propagation">
      <h2>Optical propagation preview</h2>
      <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 8 }}>
        Simulates scalar diffraction intensity at distance <em>z</em> from the mask.
        FRESNEL_TF is the angular-spectrum method; FRAUNHOFER is the far-field |FFT|²
        approximation. Results are qualitative.
      </p>
      <NumberField
        label="Propagation distance z (mm)"
        value={zMm}
        min={0.001}
        step={1}
        onChange={(value) => {
          zMmEditedRef.current = true;
          setZMm(value);
        }}
      />
      <div className="field">
        <label htmlFor="prop-mode">Propagation mode</label>
        <select
          id="prop-mode"
          value={mode}
          disabled={loading}
          onChange={(event) => setMode(event.target.value as PropagationMode)}
        >
          <option value="FRESNEL_TF">Fresnel TF (angular spectrum)</option>
          <option value="FRAUNHOFER">Fraunhofer (far-field |FFT|²)</option>
        </select>
      </div>
      <div className="actions">
        <button onClick={renderPropagation} disabled={loading}>
          {loading ? 'Propagating…' : 'Compute propagation'}
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}
      <PreviewPane url={propagationUrl} alt="Optical propagation intensity preview" />
    </div>
  );
}
