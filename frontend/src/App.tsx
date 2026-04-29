import { useEffect, useMemo, useRef, useState } from 'react';
import {
  downloadExportPng,
  fetchPreviewPng,
  validate,
  type DesignMetrics,
  type SingleZonePlateRequest,
  type Warning,
} from './api';

const DPI_PRESETS = [600, 1200, 2400, 4800];

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

export function App() {
  const [req, setReq] = useState<SingleZonePlateRequest>(DEFAULT_REQ);
  const [metrics, setMetrics] = useState<DesignMetrics | null>(null);
  const [warnings, setWarnings] = useState<Warning[]>([]);
  const [valid, setValid] = useState(true);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // For aborting outdated requests when parameters change quickly.
  const lastReqId = useRef(0);

  // Validate on every change (debounced).
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

  // Render preview on demand to avoid spamming large renders.
  const renderPreview = async () => {
    setLoading(true);
    setError(null);
    try {
      const blob = await fetchPreviewPng(req);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      setPreviewUrl(URL.createObjectURL(blob));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Auto-load an initial preview once on mount.
  useEffect(() => {
    void renderPreview();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const update = (patch: Partial<SingleZonePlateRequest>) =>
    setReq((r) => ({ ...r, ...patch }));

  const sizeEstimatePx = useMemo(() => {
    const pixelMm = 25.4 / req.dpi;
    return Math.round(req.apertureDiameterMm / pixelMm);
  }, [req.apertureDiameterMm, req.dpi]);

  return (
    <div className="app">
      <aside className="panel">
        <h1>Fresnel Zone Plate Designer</h1>

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

        <p style={{ fontSize: 12, color: '#6b7280' }}>
          Estimated image size: {sizeEstimatePx.toLocaleString()} × {sizeEstimatePx.toLocaleString()} px
        </p>

        <div className="actions">
          <button onClick={renderPreview} disabled={loading}>
            {loading ? 'Rendering…' : 'Render preview'}
          </button>
          <button className="secondary" disabled={!valid || loading}
                  onClick={() => downloadExportPng(req, 'fresnel-zone-plate.png')}>
            Download PNG
          </button>
        </div>
        {error && <p className="error-message">{error}</p>}
      </aside>

      <main className="preview">
        <Warnings warnings={warnings} valid={valid} />
        <div className="preview-canvas">
          {previewUrl
            ? <img src={previewUrl} alt="Fresnel zone plate preview" />
            : <span style={{ color: '#9ca3af' }}>No preview yet</span>}
        </div>
        {metrics && <Metrics m={metrics} />}
      </main>
    </div>
  );
}

function NumberField({ label, value, min, max, step, onChange }: {
  label: string; value: number; min?: number; max?: number; step?: number;
  onChange: (v: number) => void;
}) {
  return (
    <div className="field">
      <label>{label}</label>
      <input type="number" value={value} min={min} max={max} step={step}
             onChange={(e) => onChange(Number(e.target.value))} />
    </div>
  );
}

function Warnings({ warnings, valid }: { warnings: Warning[]; valid: boolean }) {
  if (warnings.length === 0) {
    return (
      <div className="warning info">
        Design is {valid ? 'valid' : 'invalid'} — no warnings.
      </div>
    );
  }
  return (
    <div>
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
    <div className="metrics">
      <h3>Design metrics</h3>
      <dl>
        <dt>Outer zone width</dt><dd>{m.outerZoneWidthMicrons.toFixed(2)} µm</dd>
        <dt>Printer pixel</dt><dd>{m.printerPixelMicrons.toFixed(2)} µm</dd>
        <dt>Pixels per outer zone</dt><dd>{m.pixelsPerOuterZone.toFixed(2)}</dd>
        <dt>Number of zones</dt><dd>{m.numberOfZones}</dd>
        <dt>Avg. transmission</dt><dd>{(m.estimatedTransmission * 100).toFixed(0)} %</dd>
        <dt>1st-order efficiency</dt><dd>{(m.estimatedFirstOrderEfficiency * 100).toFixed(2)} %</dd>
      </dl>
    </div>
  );
}
