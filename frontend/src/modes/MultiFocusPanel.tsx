import { useState } from 'react';
import {
  downloadMultiFocusPng, fetchMultiFocusPreviewPng,
  type FocusPointDto, type MultiFocusRequest,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const DEFAULT: MultiFocusRequest = {
  apertureDiameterMm: 10,
  focusPoints: [
    { xMm: -5, yMm: 0, zMm: 1000 },
    { xMm:  5, yMm: 0, zMm: 1000 },
  ],
  wavelengthNm: 550,
  dpi: 1200,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

export function MultiFocusPanel() {
  const [req, setReq] = useState<MultiFocusRequest>(DEFAULT);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const update = (p: Partial<MultiFocusRequest>) => setReq((r) => ({ ...r, ...p }));

  const setPoint = (idx: number, patch: Partial<FocusPointDto>) => {
    setReq((r) => {
      const fp = r.focusPoints.map((p, i) => i === idx ? { ...p, ...patch } : p);
      return { ...r, focusPoints: fp };
    });
  };
  const addPoint = () =>
    setReq((r) => ({ ...r, focusPoints: [...r.focusPoints, { xMm: 0, yMm: 0, zMm: 1000 }] }));
  const removePoint = (idx: number) =>
    setReq((r) => ({ ...r, focusPoints: r.focusPoints.filter((_, i) => i !== idx) }));

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try { setPreview(await fetchMultiFocusPreviewPng(req)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Aperture</h2>
      <NumberField label="Aperture diameter (mm)" value={req.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => update({ apertureDiameterMm: v })} />

      <h2>Focus points</h2>
      {req.focusPoints.map((p, i) => (
        <div key={i} style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr 1fr auto', gap: 4,
          alignItems: 'end', marginBottom: 8,
        }}>
          <NumberField label={`x${i + 1} (mm)`} value={p.xMm} step={0.5}
                       onChange={(v) => setPoint(i, { xMm: v })} />
          <NumberField label={`y${i + 1} (mm)`} value={p.yMm} step={0.5}
                       onChange={(v) => setPoint(i, { yMm: v })} />
          <NumberField label={`z${i + 1} (mm)`} value={p.zMm} min={1} step={10}
                       onChange={(v) => setPoint(i, { zMm: v })} />
          <button className="secondary" disabled={req.focusPoints.length <= 1}
                  onClick={() => removePoint(i)} title="Remove">×</button>
        </div>
      ))}
      <button className="secondary" onClick={addPoint}>+ Add focus point</button>

      <h2>Print</h2>
      <NumberField label="Wavelength (nm)" value={req.wavelengthNm} min={100} max={2000} step={1}
        onChange={(v) => update({ wavelengthNm: v })} />
      <NumberField label="DPI" value={req.dpi} min={50} step={50}
        onChange={(v) => update({ dpi: v })} />

      <div className="actions">
        <button onClick={renderPreview} disabled={busy}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadMultiFocusPng(req, 'fresnel-multifocus.png')}>
          PNG
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Multi-focus preview" />
    </>
  );
}
