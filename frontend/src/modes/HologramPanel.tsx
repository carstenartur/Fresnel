import { useState, type ChangeEvent } from 'react';
import {
  downloadHologramPng, fileToBase64, reconstructHologramPng, synthesizeHologramPng,
  type HologramRequest,
} from '../api';
import { NumberField, PreviewPane, useBlobUrl } from './shared';

const SIDES = [64, 128, 256, 512, 1024];

export function HologramPanel() {
  const [b64, setB64] = useState<string | null>(null);
  const [sidePx, setSide] = useState(128);
  const [iterations, setIters] = useState(40);
  const [outputType, setOut] = useState<'BINARY_PHASE' | 'GREYSCALE_PHASE'>('GREYSCALE_PHASE');
  const [dpi, setDpi] = useState(600);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [maskUrl, setMaskUrl] = useBlobUrl();
  const [reconUrl, setReconUrl] = useBlobUrl();

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try { setB64(await fileToBase64(file)); setError(null); }
    catch (err) { setError(err instanceof Error ? err.message : String(err)); }
  };

  const build = (): HologramRequest | null => {
    if (!b64) { setError('please choose a target image'); return null; }
    return { targetImageBase64: b64, sidePx, iterations, outputType, dpi };
  };

  const synthesise = async () => {
    const req = build(); if (!req) return;
    setBusy(true); setError(null);
    try { setMaskUrl(await synthesizeHologramPng(req)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const reconstruct = async () => {
    const req = build(); if (!req) return;
    setBusy(true); setError(null);
    try { setReconUrl(await reconstructHologramPng(req, true)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Target</h2>
      <div className="field">
        <label>Target image (PNG / JPEG)</label>
        <input type="file" accept="image/*" onChange={onFile} />
      </div>
      <div className="field">
        <label htmlFor="side">Side (px)</label>
        <select id="side" value={sidePx} onChange={(e) => setSide(Number(e.target.value))}>
          {SIDES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <h2>Algorithm</h2>
      <NumberField label="GS iterations" value={iterations} min={1} max={500} step={5}
                   onChange={setIters} />
      <div className="field">
        <label htmlFor="out">Output type</label>
        <select id="out" value={outputType}
                onChange={(e) => setOut(e.target.value as 'BINARY_PHASE' | 'GREYSCALE_PHASE')}>
          <option value="GREYSCALE_PHASE">Greyscale phase</option>
          <option value="BINARY_PHASE">Binary phase</option>
        </select>
      </div>
      <NumberField label="DPI" value={dpi} min={50} step={50} onChange={setDpi} />

      <div className="actions">
        <button onClick={synthesise} disabled={busy || !b64}>
          {busy ? 'Synthesising…' : 'Synthesise mask'}
        </button>
        <button className="secondary" onClick={reconstruct} disabled={busy || !b64}>
          Simulate reconstruction
        </button>
        <button className="secondary" onClick={async () => {
            const req = build(); if (!req) return;
            try { await downloadHologramPng(req, 'fresnel-hologram.png'); }
            catch (e) { setError(e instanceof Error ? e.message : String(e)); }
          }} disabled={busy || !b64}>
          PNG
        </button>
      </div>
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={maskUrl} alt="Hologram phase mask">
        <span style={{ color: '#9ca3af' }}>Choose a target image and synthesise.</span>
      </PreviewPane>
      {reconUrl && (
        <>
          <h2 style={{ marginTop: 16 }}>Simulated reconstruction</h2>
          <PreviewPane url={reconUrl} alt="Reconstruction preview" />
        </>
      )}
    </>
  );
}
