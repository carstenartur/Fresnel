import { useState } from 'react';
import {
  downloadMultiFocusPng,
  fetchMultiFocusPreviewPng,
  type MultiFocusRequest,
} from '../api';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { FocusPointListWidget } from '../schema/widgets/FocusPointListWidget';
import { PreviewPane, useBlobUrl, ValidationReportView } from './shared';

const DEFAULT: MultiFocusRequest = {
  apertureDiameterMm: 10,
  focusPoints: [
    { xMm: -5, yMm: 0, zMm: 1000 },
    { xMm: 5, yMm: 0, zMm: 1000 },
  ],
  wavelengthNm: 550,
  dpi: 1200,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const CUSTOM_WIDGETS = {
  'focus-point-list': FocusPointListWidget,
} as const;

export function MultiFocusPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<MultiFocusRequest>(() =>
    initialJobParameters(initialJob, 'multi-focus', DEFAULT));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const renderPreview = async (parameters: MultiFocusRequest) => {
    setBusy(true);
    setError(null);
    try {
      setPreview(await fetchMultiFocusPreviewPng(parameters));
    } catch (renderError) {
      setError(renderError instanceof Error ? renderError.message : String(renderError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Multi-focus</h2>
      <PluginEditorShell
        pluginId="multi-focus"
        value={request}
        onChange={setRequest}
        disabled={busy}
        customWidgets={CUSTOM_WIDGETS}
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
                      && downloadMultiFocusPng(normalized, 'fresnel-multifocus.png'),
                  },
                }}
              />
              <SaveJobControl
                pluginId="multi-focus"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
              />
              {error && <p className="error-message">{error}</p>}

              <PreviewPane url={previewUrl} alt="Multi-focus preview" />
              <ValidationReportView report={domainValidation} />
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
