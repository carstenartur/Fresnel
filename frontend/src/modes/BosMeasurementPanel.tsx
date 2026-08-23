import { useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_BOS_TARGET_PARAMETERS,
  downloadBosTarget,
  fetchBosTargetManifest,
  fetchBosTargetPreview,
  type BosTargetManifest,
  type BosTargetParameters,
  type BosTargetPreview,
} from '../bosApi';
import { SaveJobControl, type JobPanelProps } from '../jobs/JobFileControls';
import type { PluginParameterValidation } from '../pluginSchemaApi';
import { PluginEditorShell } from '../schema/PluginEditorShell';

const PLUGIN_ID = 'background-oriented-schlieren' as const;

/** First BOS vertical slice: deterministic target generation, display and download. */
export function BosMeasurementPanel({ initialJob }: JobPanelProps) {
  const [parameters, setParameters] = useState<BosTargetParameters>(() =>
    initialParameters(initialJob));
  const [validation, setValidation] =
    useState<PluginParameterValidation<BosTargetParameters> | null>(null);
  const [manifest, setManifest] = useState<BosTargetManifest | null>(null);
  const [preview, setPreview] = useState<BosTargetPreview | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [downloadBusy, setDownloadBusy] = useState(false);
  const targetRef = useRef<HTMLDivElement>(null);

  const normalized = validation?.valid ? validation.normalizedParameters ?? null : null;
  const fingerprint = useMemo(
    () => normalized ? JSON.stringify(normalized) : '',
    [normalized],
  );

  useEffect(() => {
    if (!normalized) {
      setManifest(null);
      setPreviewError(null);
      setPreviewBusy(false);
      setPreview((current) => {
        if (current) URL.revokeObjectURL(current.objectUrl);
        return null;
      });
      return;
    }

    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setPreviewBusy(true);
      setPreviewError(null);
      try {
        const [nextManifest, nextPreview] = await Promise.all([
          fetchBosTargetManifest(normalized, controller.signal),
          fetchBosTargetPreview(normalized, controller.signal),
        ]);
        if (controller.signal.aborted) {
          URL.revokeObjectURL(nextPreview.objectUrl);
          return;
        }
        if (nextManifest.semanticSha256 !== nextPreview.semanticSha256) {
          URL.revokeObjectURL(nextPreview.objectUrl);
          throw new Error('Target manifest and PNG identity do not match.');
        }
        setManifest(nextManifest);
        setPreview((current) => {
          if (current) URL.revokeObjectURL(current.objectUrl);
          return nextPreview;
        });
      } catch (requestError) {
        if (!controller.signal.aborted) {
          setPreviewError(requestError instanceof Error
            ? requestError.message
            : String(requestError));
          setManifest(null);
        }
      } finally {
        if (!controller.signal.aborted) setPreviewBusy(false);
      }
    }, 300);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [fingerprint]); // fingerprint denotes the validated canonical parameter object.

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.objectUrl);
  }, [preview]);

  const download = async () => {
    if (!normalized || downloadBusy) return;
    setDownloadBusy(true);
    setPreviewError(null);
    try {
      await downloadBosTarget(normalized, manifest?.targetId);
    } catch (requestError) {
      setPreviewError(requestError instanceof Error ? requestError.message : String(requestError));
    } finally {
      setDownloadBusy(false);
    }
  };

  const showFullScreen = async () => {
    if (!targetRef.current) return;
    try {
      await targetRef.current.requestFullscreen();
    } catch (requestError) {
      setPreviewError(requestError instanceof Error ? requestError.message : String(requestError));
    }
  };

  return (
    <PluginEditorShell<BosTargetParameters>
      pluginId={PLUGIN_ID}
      value={parameters}
      onChange={setParameters}
      applyDefaultsOnLoad={!initialJob}
      domainValidationEnabled={false}
      onStructuralValidation={setValidation}
    >
      {(schema, structuralValidation) => {
        const validParameters = structuralValidation?.valid
          ? structuralValidation.normalizedParameters
          : undefined;
        return (
          <>
            <div className="warning info bos-introduction">
              <strong>See otherwise invisible air motion.</strong>
              {' '}Show this dot target on a monitor or print it, then photograph it once
              undisturbed and once with warm or moving air in front. This first slice
              generates the reproducible target; capture and displacement analysis follow.
            </div>

            <div className="actions bos-actions">
              <button
                type="button"
                onClick={() => { void showFullScreen(); }}
                disabled={!preview || previewBusy}
              >
                Show full screen
              </button>
              <button
                type="button"
                className="secondary"
                onClick={() => { void download(); }}
                disabled={!validParameters || downloadBusy}
              >
                {downloadBusy ? 'Preparing PNG…' : 'Download PNG'}
              </button>
              <SaveJobControl
                pluginId={PLUGIN_ID}
                parameters={validParameters ?? parameters}
                parameterSchemaVersion={schema.parameterSchemaVersion}
                filename="background-oriented-schlieren.fresnel"
                disabled={!validParameters}
              />
            </div>

            <p className="bos-setup-note">
              For a screen target, use the display's native pixel dimensions, browser zoom
              100%, and full-screen mode. For print, use the embedded DPI and print at 100% scale.
            </p>

            {previewError && (
              <p className="error-message" role="alert">{previewError}</p>
            )}

            <div
              ref={targetRef}
              className={`bos-target-stage ${manifest?.invertPattern ? 'inverted' : ''}`}
              data-testid="bos-target-stage"
              data-target-id={manifest?.targetId ?? ''}
              data-target-sha256={manifest?.semanticSha256 ?? ''}
            >
              {preview ? (
                <img
                  src={preview.objectUrl}
                  alt={`Background-Oriented Schlieren dot target ${preview.targetId}`}
                  data-testid="bos-target-preview"
                  draggable={false}
                />
              ) : (
                <div className="bos-target-placeholder" role="status">
                  {previewBusy ? 'Generating deterministic dot target…' : 'Enter valid target parameters.'}
                </div>
              )}
            </div>

            {manifest && (
              <section className="metrics bos-target-metrics" aria-label="BOS target identity">
                <h3>Target identity</h3>
                <dl>
                  <dt>ID</dt><dd><code>{manifest.targetId}</code></dd>
                  <dt>Semantic SHA-256</dt><dd><code>{manifest.semanticSha256}</code></dd>
                  <dt>Source pixels</dt><dd>{manifest.widthPx} × {manifest.heightPx}</dd>
                  <dt>Intended print size</dt>
                  <dd>{manifest.intendedWidthMm.toFixed(1)} × {manifest.intendedHeightMm.toFixed(1)} mm at {manifest.intendedDpi.toFixed(1)} dpi</dd>
                  <dt>Active analysis region</dt>
                  <dd>{manifest.activeRegion.width} × {manifest.activeRegion.height} px, border {manifest.borderPx} px</dd>
                  <dt>Dots</dt>
                  <dd>{manifest.dotCount.toLocaleString()} · actual fill {(manifest.actualFillRatio * 100).toFixed(1)}%</dd>
                  <dt>Cell pitch</dt><dd>{manifest.cellPitchPx} px</dd>
                  <dt>Orientation markers</dt><dd>{manifest.fiducialsEnabled ? '4 asymmetric markers outside the active region' : 'Disabled'}</dd>
                </dl>
              </section>
            )}

            <div className="warning bos-claim-boundary">
              This target supports qualitative Background-Oriented Schlieren imaging. It is
              not a thermal camera and does not by itself measure temperature or air velocity.
            </div>
          </>
        );
      }}
    </PluginEditorShell>
  );
}

function initialParameters(job: JobPanelProps['initialJob']): BosTargetParameters {
  if (job?.plugin.id !== PLUGIN_ID || !isRecord(job.parameters)) {
    return { ...DEFAULT_BOS_TARGET_PARAMETERS };
  }
  return {
    ...DEFAULT_BOS_TARGET_PARAMETERS,
    ...job.parameters,
  } as BosTargetParameters;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
