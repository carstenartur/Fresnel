import { useEffect, useMemo, useRef, useState } from 'react';
import {
  downloadExportPdf,
  downloadExportPng,
  downloadExportSvg,
  fetchPreviewPng,
  validate,
  type DesignMetrics,
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
      </div>
      {error && <p className="error-message">{error}</p>}

      <Warnings warnings={warnings} valid={valid} />
      <PreviewPane url={previewUrl} alt="Fresnel zone plate preview" />
      {metrics && <Metrics m={metrics} />}
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
