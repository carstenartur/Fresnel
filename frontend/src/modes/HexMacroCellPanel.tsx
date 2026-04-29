import { useState } from 'react';
import {
  downloadHexPdf,
  downloadHexPng,
  fetchHexPreviewPng,
  hexInfo,
  type HexInfo,
  type HexMacroCellRequest,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const DEFAULT: HexMacroCellRequest = {
  macroRadiusMm: 30,
  subDiameterMm: 10,
  subPitchMm: 11,
  focalLengthMm: 1000,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
  wavelengthNm: 550,
  dpi: 600,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

export function HexMacroCellPanel() {
  const [req, setReq] = useState<HexMacroCellRequest>(DEFAULT);
  const [info, setInfo] = useState<HexInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const update = (p: Partial<HexMacroCellRequest>) => setReq((r) => ({ ...r, ...p }));

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try {
      setPreview(await fetchHexPreviewPng(req));
      setInfo(await hexInfo(req));
    }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Hex macro cell</h2>
      <NumberField label="Macro radius (mm)" value={req.macroRadiusMm} min={1} step={1}
        onChange={(v) => update({ macroRadiusMm: v })} />
      <NumberField label="Sub-element diameter (mm)" value={req.subDiameterMm} min={0.1} step={0.1}
        onChange={(v) => update({ subDiameterMm: v })} />
      <NumberField label="Sub-element pitch (mm)" value={req.subPitchMm} min={0.1} step={0.1}
        onChange={(v) => update({ subPitchMm: v })} />
      <NumberField label="Focal length (mm)" value={req.focalLengthMm} min={1} step={1}
        onChange={(v) => update({ focalLengthMm: v })} />

      <h2>Target offset</h2>
      <NumberField label="Target X (mm)" value={req.targetOffsetXmm ?? 0} step={1}
        onChange={(v) => update({ targetOffsetXmm: v })} />
      <NumberField label="Target Y (mm)" value={req.targetOffsetYmm ?? 0} step={1}
        onChange={(v) => update({ targetOffsetYmm: v })} />

      <h2>Print</h2>
      <NumberField label="Wavelength (nm)" value={req.wavelengthNm} min={100} max={2000} step={1}
        onChange={(v) => update({ wavelengthNm: v })} />
      <NumberField label="DPI" value={req.dpi} min={50} step={50}
        onChange={(v) => update({ dpi: v })} />

      {info && (
        <p style={{ fontSize: 12, color: '#6b7280' }}>
          {info.subElements.toLocaleString()} sub-elements · {info.imageSidePx.toLocaleString()} px per side
        </p>
      )}

      <div className="actions">
        <button onClick={renderPreview} disabled={busy}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadHexPng(req, 'fresnel-hex-macro.png')}>
          PNG
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadHexPdf(req, 'FIT', 'fresnel-hex-macro.pdf')}>
          PDF
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="Hex macro cell preview" />
    </>
  );
}
