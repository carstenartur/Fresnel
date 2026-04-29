import { useState } from 'react';
import {
  downloadFoilPdf, fetchFoilPreviewPng, foilInfo,
  type FoilInfo, type WindowFoilRequest,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const DEFAULT: WindowFoilRequest = {
  sheetWidthMm: 200,
  sheetHeightMm: 100,
  macroRadiusMm: 25,
  subDiameterMm: 8,
  subPitchMm: 9,
  wavelengthNm: 550,
  dpi: 150,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
  drawCropMarks: true,
};

const SHEETS = ['FIT', 'A4', 'A3', 'A2', 'A1', 'A0'];

export function WindowFoilPanel() {
  const [req, setReq] = useState<WindowFoilRequest>(DEFAULT);
  const [info, setInfo] = useState<FoilInfo | null>(null);
  const [sheet, setSheet] = useState('A4');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const update = (p: Partial<WindowFoilRequest>) => setReq((r) => ({ ...r, ...p }));

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try {
      setPreview(await fetchFoilPreviewPng(req));
      setInfo(await foilInfo(req));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Sheet</h2>
      <NumberField label="Sheet width (mm)" value={req.sheetWidthMm} min={10} step={10}
        onChange={(v) => update({ sheetWidthMm: v })} />
      <NumberField label="Sheet height (mm)" value={req.sheetHeightMm} min={10} step={10}
        onChange={(v) => update({ sheetHeightMm: v })} />

      <h2>Macro cells</h2>
      <NumberField label="Macro radius (mm)" value={req.macroRadiusMm} min={1} step={1}
        onChange={(v) => update({ macroRadiusMm: v })} />
      <NumberField label="Sub-diameter (mm)" value={req.subDiameterMm} min={0.1} step={0.1}
        onChange={(v) => update({ subDiameterMm: v })} />
      <NumberField label="Sub-pitch (mm)" value={req.subPitchMm} min={0.1} step={0.1}
        onChange={(v) => update({ subPitchMm: v })} />

      <h2>Print</h2>
      <NumberField label="Wavelength (nm)" value={req.wavelengthNm} min={100} max={2000} step={1}
        onChange={(v) => update({ wavelengthNm: v })} />
      <NumberField label="DPI" value={req.dpi} min={50} step={50}
        onChange={(v) => update({ dpi: v })} />
      <div className="field">
        <label><input type="checkbox" checked={!!req.drawCropMarks}
                      onChange={(e) => update({ drawCropMarks: e.target.checked })} /> Draw crop marks</label>
      </div>
      <div className="field">
        <label htmlFor="sheet">PDF sheet size</label>
        <select id="sheet" value={sheet} onChange={(e) => setSheet(e.target.value)}>
          {SHEETS.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      {info && (
        <p style={{ fontSize: 12, color: '#6b7280' }}>
          {info.cells} cells · image {info.imageWidthPx.toLocaleString()} × {info.imageHeightPx.toLocaleString()} px
        </p>
      )}

      <div className="actions">
        <button onClick={renderPreview} disabled={busy}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadFoilPdf(req, sheet, 'fresnel-window-foil.pdf')}>
          PDF ({sheet})
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Window foil preview" />
    </>
  );
}
