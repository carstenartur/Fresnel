import { useState } from 'react';
import {
  downloadRgbPng,
  fetchRgbPreviewPng,
  type RgbZonePlateRequest,
  type SingleZonePlateRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const BASE_DEFAULT: SingleZonePlateRequest = {
  apertureDiameterMm: 5,
  focalLengthMm: 100,
  wavelengthNm: 550,
  dpi: 600,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
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
  const [previewUrl, setPreview] = useBlobUrl();

  const renderPreview = async (parameters: RgbZonePlateRequest) => {
    setBusy(true);
    setError(null);
    try {
      setPreview(await fetchRgbPreviewPng(parameters));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>RGB zone plate</h2>
      <PluginEditorShell
        pluginId="rgb-zone-plate"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation, domainValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          const productionReady = structurallyValid && domainValidation?.valid === true;
          return (
            <>
              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Rendering…' : 'Render preview',
                    primary: true,
                    disabled: !structurallyValid,
                    run: () => normalized && renderPreview(normalized),
                  },
                  EXPORT_PNG: {
                    label: 'PNG',
                    disabled: !productionReady,
                    run: () => normalized
                      && downloadRgbPng(normalized, 'fresnel-rgb.png'),
                  },
                }}
              />
              <SaveJobControl
                pluginId="rgb-zone-plate"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />
              {error && <p className="error-message">{error}</p>}

              <PreviewPane url={previewUrl} alt="RGB zone plate preview" />
              <ValidationReportView report={domainValidation} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
