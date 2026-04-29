import { useState } from 'react';
import {
  downloadRgbPng, fetchRgbPreviewPng,
  type RgbZonePlateRequest, type SingleZonePlateRequest,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const BASE_DEFAULT: SingleZonePlateRequest = {
  apertureDiameterMm: 5,
  focalLengthMm: 100,
  wavelengthNm: 550,    // ignored by RGB renderer
  dpi: 600,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

export function RgbPanel() {
  const [base, setBase] = useState<SingleZonePlateRequest>(BASE_DEFAULT);
  const [redNm, setRed] = useState(630);
  const [greenNm, setGreen] = useState(532);
  const [blueNm, setBlue] = useState(450);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const updateBase = (p: Partial<SingleZonePlateRequest>) => setBase((b) => ({ ...b, ...p }));

  const req = (): RgbZonePlateRequest => ({ base, redNm, greenNm, blueNm });

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try { setPreview(await fetchRgbPreviewPng(req())); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Geometry</h2>
      <NumberField label="Aperture (mm)" value={base.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => updateBase({ apertureDiameterMm: v })} />
      <NumberField label="Focal length (mm)" value={base.focalLengthMm} min={1} step={1}
        onChange={(v) => updateBase({ focalLengthMm: v })} />
      <NumberField label="DPI" value={base.dpi} min={50} step={50}
        onChange={(v) => updateBase({ dpi: v })} />

      <h2>Channel wavelengths (nm)</h2>
      <NumberField label="Red" value={redNm} min={100} max={2000} step={1} onChange={setRed} />
      <NumberField label="Green" value={greenNm} min={100} max={2000} step={1} onChange={setGreen} />
      <NumberField label="Blue" value={blueNm} min={100} max={2000} step={1} onChange={setBlue} />

      <div className="actions">
        <button onClick={renderPreview} disabled={busy}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadRgbPng(req(), 'fresnel-rgb.png')}>
          PNG
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="RGB zone plate preview" />
    </>
  );
}
