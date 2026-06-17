import { useEffect, useMemo, useRef, useState } from 'react';
import {
  downloadExportDxf,
  downloadExportGerber,
  downloadExportPdf,
  downloadExportPng,
  downloadExportSvg,
  fetchPreviewPng,
  fetchPropagatePng,
  loadDesignFromFile,
  saveDesign,
  validate,
  type DesignMetrics,
  type OpticalQualityReport,
  type PropagationMode,
  type SingleZonePlateRequest,
  type Warning,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const DPI_PRESETS = [600, 1200, 2400, 4800];
const SHEETS = ['FIT', 'A4', 'A3', 'A2', 'A1', 'A0'];

const DEFAULT_REQ: SingleZonePlateRequest = {
  apertureDiameterMm: 10,
  focalLengthMm: 1000,
  wavelengthNm: 550,
  dpi: 1200,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

export function SingleZonePlatePanel() {
  const [req, setReq] = useState<SingleZonePlateRequest>(DEFAULT_REQ);
  const [metrics, setMetrics] = useState<DesignMetrics | null>(null);
  const [qualityReport, setQualityReport] = useState<OpticalQualityReport | null>(null);
  const [warnings, setWarnings] = useState<Warning[]>([]);
  const [valid, setValid] = useState(true);
  const [previewUrl, setPreview] = useBlobUrl();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sheet, setSheet] = useState('FIT');
  const lastReqId = useRef(0);

  useEffect(() => {
    const id = ++lastReqId.current;
    const t = setTimeout(async () => {
      try {
        const v = await validate(req);
        if (id !== lastReqId.current) return;
        setMetrics(v.metrics);
        setQualityReport(v.qualityReport ?? null);
        setWarnings(v.warnings);
        setValid(v.valid);
        setError(null);
      } catch (e) {
        if (id !== lastReqId.current) return;
        setError(e instanceof Error ? e.message : String(e));
      }
    }, 200);
    return () => clearTimeout(t);
  }, [req]);

  const renderPreview = async () => {
    setLoading(true); setError(null);
    try { setPreview(await fetchPreviewPng(req)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  };

  // Render an initial preview once on mount.
  useEffect(() => { void renderPreview(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  const update = (patch: Partial<SingleZonePlateRequest>) => setReq((r) => ({ ...r, ...patch }));

  const sizeEstimatePx = useMemo(() => {
    const pixelMm = 25.4 / req.dpi;
    return Math.round(req.apertureDiameterMm / pixelMm);
  }, [req.apertureDiameterMm, req.dpi]);

  return (
    <>
      <h2>Geometry</h2>
      <NumberField label="Aperture diameter (mm)"
        value={req.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => update({ apertureDiameterMm: v })} />
      <NumberField label="Focal length (mm)"
        value={req.focalLengthMm} min={1} step={1}
        onChange={(v) => update({ focalLengthMm: v })} />
      <NumberField label="Wavelength (nm)"
        value={req.wavelengthNm} min={100} max={2000} step={1}
        onChange={(v) => update({ wavelengthNm: v })} />

      <h2>Off-axis target</h2>
      <NumberField label="Target offset X (mm)"
        value={req.targetOffsetXmm ?? 0} step={1}
        onChange={(v) => update({ targetOffsetXmm: v })} />
      <NumberField label="Target offset Y (mm)"
        value={req.targetOffsetYmm ?? 0} step={1}
        onChange={(v) => update({ targetOffsetYmm: v })} />

      <h2>Print</h2>
      <div className="field">
        <label htmlFor="dpi">Printer DPI</label>
        <select id="dpi" value={req.dpi}
                onChange={(e) => update({ dpi: Number(e.target.value) })}>
          {DPI_PRESETS.map((d) => <option key={d} value={d}>{d} dpi</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="mask">Mask type</label>
        <select id="mask" value={req.maskType}
                onChange={(e) => update({ maskType: e.target.value as 'BINARY_AMPLITUDE' | 'GREYSCALE_PHASE' })}>
          <option value="BINARY_AMPLITUDE">Binary amplitude</option>
          <option value="GREYSCALE_PHASE">Greyscale phase</option>
        </select>
      </div>
      <div className="field">
        <label htmlFor="pol">Polarity</label>
        <select id="pol" value={req.polarity}
                onChange={(e) => update({ polarity: e.target.value as 'POSITIVE' | 'NEGATIVE' })}>
          <option value="POSITIVE">Positive</option>
          <option value="NEGATIVE">Negative (inverted)</option>
        </select>
      </div>
      <div className="field">
        <label htmlFor="sheet">PDF sheet size</label>
        <select id="sheet" value={sheet} onChange={(e) => setSheet(e.target.value)}>
          {SHEETS.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <p style={{ fontSize: 12, color: '#6b7280' }}>
        Estimated image size: {sizeEstimatePx.toLocaleString()} × {sizeEstimatePx.toLocaleString()} px
      </p>

      <div className="actions">
        <button onClick={renderPreview} disabled={loading}>
          {loading ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={!valid || loading}
                onClick={() => downloadExportPng(req, 'fresnel-zone-plate.png')}>
          PNG
        </button>
        <button className="secondary" disabled={!valid || loading}
                onClick={() => downloadExportSvg(req, 'fresnel-zone-plate.svg')}>
          SVG
        </button>
        <button className="secondary" disabled={!valid || loading}
                onClick={() => downloadExportPdf(req, sheet, 'fresnel-zone-plate.pdf')}>
          PDF
        </button>
        <button className="secondary" disabled={!valid || loading}
                onClick={() => downloadExportDxf(req, 'fresnel-zone-plate.dxf')}
                title="DXF outlines for laser cutters / pen plotters">
          DXF
        </button>
        <button className="secondary" disabled={!valid || loading}
                onClick={() => downloadExportGerber(req, 'fresnel-zone-plate.gbr')}
                title="Gerber RS-274X for PCB-style fabrication">
          Gerber
        </button>
      </div>

      <h2 style={{ marginTop: 16 }}>Save / load design</h2>
      <div className="actions">
        <button className="secondary"
                onClick={() => saveDesign({ kind: 'single', version: 1, payload: req })}>
          Save (.json)
        </button>
        <label className="secondary" style={{ cursor: 'pointer' }}>
          Load…
          <input type="file" accept="application/json,.json" style={{ display: 'none' }}
                 onChange={async (e) => {
                   const f = e.target.files?.[0];
                   if (!f) return;
                   try {
                     const doc = await loadDesignFromFile<SingleZonePlateRequest>(f);
                     if (doc.kind !== 'single') {
                       setError(`Loaded file is for "${doc.kind}" mode, not single.`);
                       return;
                     }
                     setReq({ ...DEFAULT_REQ, ...doc.payload });
                     setError(null);
                   } catch (err) {
                     setError(err instanceof Error ? err.message : String(err));
                   } finally {
                     e.target.value = '';
                   }
                 }} />
        </label>
      </div>
      {error && <p className="error-message">{error}</p>}

      <Warnings warnings={warnings} valid={valid} />
      <PreviewPane url={previewUrl} alt="Fresnel zone plate preview" />
      {metrics && <Metrics m={metrics} />}
      {qualityReport && <QualityReport r={qualityReport} />}

      <PropagationPanel req={req} />
    </>
  );
}

function Warnings({ warnings, valid }: { warnings: Warning[]; valid: boolean }) {
  if (warnings.length === 0) {
    return (
      <div className="warning info" style={{ marginTop: 16 }}>
        Design is {valid ? 'valid' : 'invalid'} — no warnings.
      </div>
    );
  }
  return (
    <div style={{ marginTop: 16 }}>
      {warnings.map((w) => (
        <div key={w.code}
             className={`warning ${w.severity === 'ERROR' ? 'error' : w.severity === 'INFO' ? 'info' : ''}`}>
          <strong>{w.code}:</strong> {w.message}
        </div>
      ))}
    </div>
  );
}

function Metrics({ m }: { m: DesignMetrics }) {
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Design metrics</h3>
      <dl>
        <dt>Outer zone width</dt><dd>{m.outerZoneWidthMicrons.toFixed(2)} µm</dd>
        <dt>Printer pixel</dt><dd>{m.printerPixelMicrons.toFixed(2)} µm</dd>
        <dt>Pixels per outer zone</dt><dd>{m.pixelsPerOuterZone.toFixed(2)}</dd>
        <dt>Number of zones</dt><dd>{m.numberOfZones}</dd>
        <dt>Avg. transmission</dt><dd>{(m.estimatedTransmission * 100).toFixed(0)} %</dd>
        <dt>1st-order efficiency</dt><dd>{(m.estimatedFirstOrderEfficiency * 100).toFixed(2)} %</dd>
      </dl>

      {m.chromaticShifts && m.chromaticShifts.length > 0 && (
        <>
          <h3 style={{ marginTop: 12 }}>Chromatic focal shift</h3>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead><tr>
              <th style={{ textAlign: 'left' }}>λ (nm)</th>
              <th style={{ textAlign: 'right' }}>f (mm)</th>
            </tr></thead>
            <tbody>
              {m.chromaticShifts.map((c) => (
                <tr key={c.wavelengthNm}>
                  <td>{c.wavelengthNm.toFixed(0)}</td>
                  <td style={{ textAlign: 'right' }}>{c.focalLengthMm.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {m.defocusBlurs && m.defocusBlurs.length > 0 && (
        <>
          <h3 style={{ marginTop: 12 }}>Defocus blur (circle of confusion)</h3>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead><tr>
              <th style={{ textAlign: 'left' }}>Wall distance (mm)</th>
              <th style={{ textAlign: 'right' }}>Blur Ø (mm)</th>
            </tr></thead>
            <tbody>
              {m.defocusBlurs.map((d) => (
                <tr key={d.wallDistanceMm}>
                  <td>{d.wallDistanceMm.toFixed(0)}</td>
                  <td style={{ textAlign: 'right' }}>{d.blurDiameterMm.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

/**
 * Optical quality report panel.
 *
 * Displays the independently computed optical quality metrics for the current
 * design: NA, f-number, Airy disk diameter, Rayleigh angular resolution,
 * depth of focus, outermost zone width and chromatic focal shift.
 */
function QualityReport({ r }: { r: OpticalQualityReport }) {
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Optical quality report</h3>
      <dl>
        <dt>Design wavelength</dt><dd>{r.wavelengthNm.toFixed(1)} nm</dd>
        <dt>Focal length</dt><dd>{r.focalLengthMm.toFixed(2)} mm</dd>
        <dt>Aperture diameter</dt><dd>{r.apertureDiameterMm.toFixed(2)} mm</dd>
        <dt>Numerical aperture (NA)</dt><dd>{r.numericalAperture.toFixed(4)}</dd>
        <dt>f-number (F#)</dt><dd>{r.fNumber.toFixed(1)}</dd>
        <dt>Airy disk diameter</dt><dd>{r.airyDiskDiameterMicrons.toFixed(2)} µm</dd>
        <dt>Rayleigh angular resolution</dt>
        <dd>{(r.rayleighAngularResolutionRad * 1e6).toFixed(3)} µrad</dd>
        <dt>Depth of focus (DoF)</dt><dd>{r.depthOfFocusMicrons.toFixed(1)} µm</dd>
        <dt>Outermost zone width</dt><dd>{r.outermostZoneWidthMicrons.toFixed(2)} µm</dd>
        <dt>Chromatic focal shift
          <span style={{ fontWeight: 'normal', fontSize: 11, color: '#6b7280' }}>
            {' '}({r.chromaticRangeMinNm.toFixed(0)}–{r.chromaticRangeMaxNm.toFixed(0)} nm)
          </span>
        </dt>
        <dd>{r.chromaticFocalShiftMm.toFixed(2)} mm</dd>
      </dl>
    </div>
  );
}

/**
 * Optical propagation preview panel.
 *
 * Renders the zone plate at the current design parameters, propagates the
 * resulting field by the user-specified distance, and shows the intensity
 * image.
 *
 * Approximation used: scalar (paraxial) diffraction.
 * - FRESNEL_TF: angular-spectrum transfer-function propagation, valid for any
 *   finite distance; the output represents the physical intensity at z = zMm.
 * - FRAUNHOFER: far-field |FFT|² (no specific distance, focal-plane inspection).
 *
 * Note: results are qualitative — the pixel grid of a printed zone plate is
 * typically much coarser than the optical wavelength, so only low-frequency
 * diffraction features are captured.
 */
function PropagationPanel({ req }: { req: SingleZonePlateRequest }) {
  const [zMm, setZMm] = useState(req.focalLengthMm);
  const zMmEditedRef = useRef(false);
  const [mode, setMode] = useState<PropagationMode>('FRESNEL_TF');
  const [propUrl, setPropUrl] = useBlobUrl();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keep zMm in sync with the design focal length unless the user has
  // explicitly overridden it via the distance field.
  useEffect(() => {
    if (!zMmEditedRef.current) {
      setZMm(req.focalLengthMm);
    }
  }, [req.focalLengthMm]);

  const renderPropagation = async () => {
    setLoading(true); setError(null);
    try {
      const blob = await fetchPropagatePng({ base: req, zMm, mode });
      setPropUrl(blob);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ marginTop: 24 }}>
      <h2>Optical propagation preview</h2>
      <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 8 }}>
        Simulates the scalar diffraction intensity at distance <em>z</em> from the mask.
        FRESNEL_TF is the angular-spectrum method (any distance);
        FRAUNHOFER is the far-field |FFT|² (focal-plane approximation).
        Results are qualitative — see docs for limits.
      </p>

      <NumberField label="Propagation distance z (mm)"
        value={zMm} min={0.001} step={1}
        onChange={(v) => { zMmEditedRef.current = true; setZMm(v); }} />

      <div className="field">
        <label htmlFor="prop-mode">Propagation mode</label>
        <select id="prop-mode" value={mode}
                onChange={(e) => setMode(e.target.value as PropagationMode)}>
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
      <PreviewPane url={propUrl} alt="Optical propagation intensity preview" />
    </div>
  );
}
