import { useState, type ChangeEvent } from 'react';
import {
  downloadHologramPng, downloadHologramStl, fileToBase64, reconstructHologramPng, synthesizeHologramPng,
  validatePlugin,
  type DesignValidationReport, type HologramRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { NumberField, PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const SIDES = [64, 128, 256, 512, 1024];

const DEFAULT: HologramRequest = {
  targetImageBase64: '',
  sidePx: 128,
  iterations: 40,
  outputType: 'GREYSCALE_PHASE',
  dpi: 600,
};

export function HologramPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<HologramRequest>(() =>
    initialJobParameters(initialJob, 'hologram', DEFAULT));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [maskUrl, setMaskUrl] = useBlobUrl();
  const [reconUrl, setReconUrl] = useBlobUrl();

  const update = (patch: Partial<HologramRequest>) =>
    setRequest((current) => ({ ...current, ...patch }));

  const onFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      update({ targetImageBase64: await fileToBase64(file) });
      setError(null);
    } catch (fileError) {
      setError(fileError instanceof Error ? fileError.message : String(fileError));
    }
  };

  const build = (): HologramRequest | null => {
    if (!request.targetImageBase64) {
      setError('please choose a target image');
      return null;
    }
    return request;
  };

  const synthesise = async () => {
    const req = build(); if (!req) return;
    setBusy(true); setError(null);
    try {
      setMaskUrl(await synthesizeHologramPng(req));
      setValidationReport(await validatePlugin('hologram', req));
    }
    catch (e) { setValidationReport(null); setError(e instanceof Error ? e.message : String(e)); }
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
        <select id="side" value={request.sidePx}
                onChange={(e) => update({ sidePx: Number(e.target.value) })}>
          {SIDES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <h2>Algorithm</h2>
      <NumberField label="GS iterations" value={request.iterations} min={1} max={500} step={5}
                   onChange={(iterations) => update({ iterations })} />
      <div className="field">
        <label htmlFor="out">Output type</label>
        <select id="out" value={request.outputType}
                onChange={(e) => update({
                  outputType: e.target.value as 'BINARY_PHASE' | 'GREYSCALE_PHASE',
                })}>
          <option value="GREYSCALE_PHASE">Greyscale phase</option>
          <option value="BINARY_PHASE">Binary phase</option>
        </select>
      </div>
      <NumberField label="DPI" value={request.dpi} min={50} step={50}
                   onChange={(dpi) => update({ dpi })} />

      <div className="actions">
        <button onClick={synthesise} disabled={busy || !request.targetImageBase64}>
          {busy ? 'Synthesising…' : 'Synthesise mask'}
        </button>
        <button className="secondary" onClick={reconstruct}
                disabled={busy || !request.targetImageBase64}>
          Simulate reconstruction
        </button>
        <button className="secondary" onClick={async () => {
            const req = build(); if (!req) return;
            try { await downloadHologramPng(req, 'fresnel-hologram.png'); }
            catch (e) { setError(e instanceof Error ? e.message : String(e)); }
          }} disabled={busy || !request.targetImageBase64}>
          PNG
        </button>
        <button className="secondary" onClick={async () => {
            const req = build(); if (!req) return;
            try { await downloadHologramStl(req, 'fresnel-hologram-relief.stl'); }
            catch (e) { setError(e instanceof Error ? e.message : String(e)); }
          }} disabled={busy || !request.targetImageBase64}>
          STL
        </button>
      </div>
      <SaveJobControl
        pluginId="hologram"
        parameters={request.targetImageBase64 ? request : null}
        disabled={busy}
      />
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
      <ValidationReportView report={validationReport} />
    </>
  );
}
