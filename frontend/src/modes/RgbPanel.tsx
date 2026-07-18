import { useState } from 'react';
import {
  downloadRgbPng, fetchRgbPreviewPng, validatePlugin,
  type DesignValidationReport, type RgbZonePlateRequest, type SingleZonePlateRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { NumberField, PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const BASE_DEFAULT: SingleZonePlateRequest = {
  apertureDiameterMm: 5,
  focalLengthMm: 100,
  wavelengthNm: 550,    // ignored by RGB renderer
  dpi: 600,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const DEFAULT: RgbZonePlateRequest = {
  base: BASE_DEFAULT,
  redNm: 630,
  greenNm: 532,
  blueNm: 450,
};

export function RgbPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<RgbZonePlateRequest>(() => {
    const loaded = initialJobParameters(initialJob, 'rgb-zone-plate', DEFAULT);
    return { ...loaded, base: { ...BASE_DEFAULT, ...loaded.base } };
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validationReport, setValidationReport] = useState<DesignValidationReport | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const updateBase = (patch: Partial<SingleZonePlateRequest>) =>
    setRequest((current) => ({ ...current, base: { ...current.base, ...patch } }));
  const update = (patch: Partial<RgbZonePlateRequest>) =>
    setRequest((current) => ({ ...current, ...patch }));

  const renderPreview = async () => {
    setBusy(true); setError(null);
    try {
      setPreview(await fetchRgbPreviewPng(request));
      setValidationReport(await validatePlugin('rgb-zone-plate', request));
    }
    catch (e) { setValidationReport(null); setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h2>Geometry</h2>
      <NumberField label="Aperture (mm)" value={request.base.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => updateBase({ apertureDiameterMm: v })} />
      <NumberField label="Focal length (mm)" value={request.base.focalLengthMm} min={1} step={1}
        onChange={(v) => updateBase({ focalLengthMm: v })} />
      <NumberField label="DPI" value={request.base.dpi} min={50} step={50}
        onChange={(v) => updateBase({ dpi: v })} />

      <h2>Channel wavelengths (nm)</h2>
      <NumberField label="Red" value={request.redNm} min={100} max={2000} step={1}
        onChange={(redNm) => update({ redNm })} />
      <NumberField label="Green" value={request.greenNm} min={100} max={2000} step={1}
        onChange={(greenNm) => update({ greenNm })} />
      <NumberField label="Blue" value={request.blueNm} min={100} max={2000} step={1}
        onChange={(blueNm) => update({ blueNm })} />

      <div className="actions">
        <button onClick={renderPreview} disabled={busy}>
          {busy ? 'Rendering…' : 'Render preview'}
        </button>
        <button className="secondary" disabled={busy}
                onClick={() => downloadRgbPng(request, 'fresnel-rgb.png')}>
          PNG
        </button>
      </div>
      <SaveJobControl pluginId="rgb-zone-plate" parameters={request} disabled={busy} />
      {error && <p className="error-message">{error}</p>}

      <PreviewPane url={previewUrl} alt="RGB zone plate preview" />
      <ValidationReportView report={validationReport} />
    </>
  );
}
