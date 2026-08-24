import { useRef, useState } from 'react';
import {
  downloadBosTargetManifest,
  downloadBosTargetPng,
  fetchBosTargetManifest,
  fetchBosTargetPreviewPng,
  type BosTargetManifest,
  type BosTargetRequest,
} from '../bosTargetApi';
import {
  initialJobParameters,
  SaveJobControl,
  type JobPanelProps,
} from '../jobs/JobFileControls';
import { PluginActionBar } from '../schema/PluginActionBar';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { PreviewPane, useBlobUrl } from './shared';
import './BosTargetPanel.css';

const DEFAULT: BosTargetRequest = {
  widthPx: 1600,
  heightPx: 1000,
  intendedDpi: 150,
  patternSeed: 20260823,
  dotDiameterPx: 7,
  targetFillRatio: 0.12,
  minimumDotSpacingPx: 3,
  borderPx: 64,
  fiducialsEnabled: true,
  invertPattern: false,
};

interface GeneratedTarget {
  fingerprint: string;
  manifest: BosTargetManifest;
}

export function BosTargetPanel({ initialJob }: JobPanelProps) {
  const [request, setRequest] = useState<BosTargetRequest>(() =>
    initialJobParameters(initialJob, 'background-oriented-schlieren', DEFAULT));
  const [generated, setGenerated] = useState<GeneratedTarget | null>(null);
  const [previewFingerprint, setPreviewFingerprint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreview] = useBlobUrl();
  const targetStageRef = useRef<HTMLDivElement>(null);
  const requestFingerprint = JSON.stringify(request);
  const currentManifest = generated?.fingerprint === requestFingerprint
    ? generated.manifest
    : null;
  const currentPreviewUrl = previewFingerprint === requestFingerprint ? previewUrl : null;

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await operation();
    } catch (operationError) {
      setError(operationError instanceof Error ? operationError.message : String(operationError));
    } finally {
      setBusy(false);
    }
  };

  const generatePreview = async (parameters: BosTargetRequest) => {
    const fingerprint = JSON.stringify(parameters);
    await run(async () => {
      const [preview, manifest] = await Promise.all([
        fetchBosTargetPreviewPng(parameters),
        fetchBosTargetManifest(parameters),
      ]);
      const expectedActiveRegion = [
        manifest.activeRegion.x,
        manifest.activeRegion.y,
        manifest.activeRegion.width,
        manifest.activeRegion.height,
      ].join(',');
      if (preview.targetId !== manifest.targetId
          || preview.semanticSha256 !== manifest.semanticSha256
          || preview.activeRegionHeader !== expectedActiveRegion) {
        throw new Error('Target manifest and PNG identity do not match.');
      }
      setPreview(preview.blob);
      setPreviewFingerprint(fingerprint);
      setGenerated({ fingerprint, manifest });
    });
  };

  const showFullScreen = () => {
    const stage = targetStageRef.current;
    if (!stage || !currentPreviewUrl) return;
    setError(null);
    void stage.requestFullscreen().catch((fullScreenError: unknown) => {
      setError(fullScreenError instanceof Error
        ? fullScreenError.message
        : String(fullScreenError));
    });
  };

  return (
    <>
      <h2>Background-Oriented Schlieren target</h2>
      <p className="warning info" style={{ marginTop: 0 }}>
        Generate a deterministic random-dot target for qualitative visualization of
        apparent background displacement caused by refractive-index gradients. This is
        not a thermal camera and does not measure temperature, density, velocity or
        airflow direction without a separately validated physical calibration.
      </p>
      <p style={{ fontSize: 13 }}>
        For a screen target, display the downloaded PNG without interpolation whenever
        possible. For a printed target, print at 100% scale and disable fit-to-page,
        resampling and driver enhancement options.
      </p>

      <PluginEditorShell
        pluginId="background-oriented-schlieren"
        value={request}
        onChange={setRequest}
        disabled={busy}
        applyDefaultsOnLoad={!initialJob}
        domainValidationEnabled={false}
      >
        {(schema, structuralValidation) => {
          const normalized = structuralValidation?.valid
            ? structuralValidation.normalizedParameters
            : undefined;
          const structurallyValid = Boolean(normalized);
          return (
            <>
              <PluginActionBar
                capabilities={schema.capabilities}
                busy={busy}
                actions={{
                  PREVIEW_PNG: {
                    label: busy ? 'Generating…' : 'Generate target preview',
                    primary: true,
                    disabled: !structurallyValid,
                    run: () => normalized && generatePreview(normalized),
                  },
                  EXPORT_PNG: {
                    label: 'Download target PNG',
                    disabled: !structurallyValid,
                    title: 'Lossless source pixels with the intended physical DPI embedded.',
                    run: () => normalized && run(() => downloadBosTargetPng(
                      normalized,
                      `${currentManifest?.targetId ?? 'fresnel-bos-target'}.png`,
                    )),
                  },
                }}
              />

              {currentManifest && (
                <div className="actions bos-target-secondary-actions">
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={showFullScreen}
                  >
                    Show target full screen
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => downloadBosTargetManifest(
                      currentManifest,
                      `${currentManifest.targetId}-manifest.json`,
                    )}
                  >
                    Download target manifest
                  </button>
                </div>
              )}

              <SaveJobControl
                pluginId="background-oriented-schlieren"
                parameters={normalized ?? null}
                disabled={busy || !structurallyValid}
                filename="background-oriented-schlieren.fresnel"
              />

              {currentManifest && <TargetManifestView manifest={currentManifest} />}
              {error && <p className="error-message" role="alert">{error}</p>}
              <div
                ref={targetStageRef}
                className="bos-target-stage"
                data-target-id={currentManifest?.targetId ?? ''}
                data-target-sha256={currentManifest?.semanticSha256 ?? ''}
              >
                <PreviewPane
                  url={currentPreviewUrl}
                  alt="Background-Oriented Schlieren dot target"
                >
                  <span style={{ color: '#9ca3af' }}>
                    Generate a preview to inspect the exact deterministic target.
                  </span>
                </PreviewPane>
              </div>
            </>
          );
        }}
      </PluginEditorShell>
    </>
  );
}

function TargetManifestView({ manifest }: { manifest: BosTargetManifest }) {
  const active = manifest.activeRegion;
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Reproducible target manifest</h3>
      <dl>
        <dt>Target ID</dt>
        <dd><code>{manifest.targetId}</code></dd>
        <dt>Algorithm</dt>
        <dd><code>{manifest.algorithmVersion}</code></dd>
        <dt>Semantic SHA-256</dt>
        <dd style={{ overflowWrap: 'anywhere' }}><code>{manifest.semanticSha256}</code></dd>
        <dt>Source raster</dt>
        <dd>{manifest.widthPx.toLocaleString()} × {manifest.heightPx.toLocaleString()} px</dd>
        <dt>Intended print size</dt>
        <dd>{format(manifest.intendedWidthMm)} × {format(manifest.intendedHeightMm)} mm at{' '}
          {format(manifest.intendedDpi)} dpi</dd>
        <dt>Active analysis region</dt>
        <dd>
          x={active.x}, y={active.y}, {active.width.toLocaleString()} ×{' '}
          {active.height.toLocaleString()} px
        </dd>
        <dt>Dot field</dt>
        <dd>
          {manifest.dotCount.toLocaleString()} dots · {manifest.dotDiameterPx} px diameter ·{' '}
          {manifest.cellPitchPx} px cell pitch
        </dd>
        <dt>Actual foreground fill</dt>
        <dd>{formatPercent(manifest.actualFillRatio)}</dd>
        <dt>Orientation fiducials</dt>
        <dd>{manifest.fiducialsEnabled ? `${manifest.fiducials.length} outside the active region` : 'disabled'}</dd>
      </dl>
    </div>
  );
}

function format(value: number): string {
  if (Math.abs(value) >= 100 || Math.abs(value - Math.round(value)) < 0.005) {
    return value.toFixed(0);
  }
  return value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}%`;
}
