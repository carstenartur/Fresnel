import { useState } from 'react';
import {
  downloadBackgroundOrientedSchlierenPng,
  fetchBackgroundOrientedSchlierenPreviewPng,
  type BackgroundOrientedSchlierenRequest,
} from '../bosApi';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { PreviewPane, useBlobUrl } from './shared';

const DEFAULT: BackgroundOrientedSchlierenRequest = {
  widthPx: 1920,
  heightPx: 1080,
  intendedDpi: 300,
  patternSeed: 20260824,
  dotDiameterPx: 7,
  targetFillRatio: 0.12,
  minimumDotSpacingPx: 3,
  borderPx: 64,
  fiducialsEnabled: true,
  invertPattern: false,
};

/** Deterministic target-generation slice for background-oriented schlieren. */
export function BackgroundOrientedSchlierenPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<BackgroundOrientedSchlierenRequest>(() =>
    initialJobParameters(
      initialJob,
      'background-oriented-schlieren',
      DEFAULT,
    ));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await operation();
    } catch (operationError) {
      setError(operationError instanceof Error
        ? operationError.message
        : String(operationError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>Background-oriented schlieren target</h2>
      <p className="warning info" style={{ marginTop: 0 }}>
        Generate a seeded, reproducible random-dot background for BOS capture.
        Print or display the exported PNG without resampling. The same target
        parameters and seed always produce the same immutable image.
      </p>
      <PluginEditorShell
        pluginId="background-oriented-schlieren"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
      >
        {(schema, structuralValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const ready = Boolean(normalized);
          return (
            <>
              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Rendering…' : 'Render target preview',
                    primary: true,
                    disabled: !ready,
                    run: () => {
                      if (!normalized) return;
                      return run(async () => {
                        const blob = await fetchBackgroundOrientedSchlierenPreviewPng(
                          normalized as BackgroundOrientedSchlierenRequest,
                        );
                        setPreview(blob);
                      });
                    },
                  },
                  EXPORT_PNG: {
                    label: 'Export target PNG',
                    disabled: !ready,
                    title: 'Seeded full-resolution target; use without scaling or resampling.',
                    run: () => {
                      if (!normalized) return;
                      return run(() =>
                        downloadBackgroundOrientedSchlierenPng(
                          normalized as BackgroundOrientedSchlierenRequest,
                        ));
                    },
                  },
                }}
              />
              <SaveJobControl
                pluginId="background-oriented-schlieren"
                parameters={normalized ?? null}
                disabled={busy || !ready}
                filename="fresnel-background-oriented-schlieren.fresnel"
              />
              {error && <p className="error-message" role="alert">{error}</p>}
              <PreviewPane
                url={previewUrl}
                alt="Seeded background-oriented schlieren random-dot target"
              >
                <span style={{ color: '#9ca3af' }}>
                  Render a preview to inspect dot density, border and fiducials.
                </span>
              </PreviewPane>
              <div className="warning info" style={{ marginTop: 12, fontSize: 12 }}>
                This slice generates the capture target only. Reference/disturbed
                capture import, Photographer-controlled acquisition and BOS
                displacement analysis will use the common durable measurement
                session introduced in the next slice.
              </div>
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}
