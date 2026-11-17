import { useEffect, useMemo, useRef, useState } from 'react';
import { MeasurementArchive } from './MeasurementArchive';
import { BosReferenceInspector, useReferenceInspection } from './BosReferenceInspector';
import { fullFrame, readRegion, type Region } from './bosReferenceQuality';
import type { QualityOutput } from './bosQuality.worker';
import type { BosTargetManifest } from '../bosTargetApi';
import type { WorkerOutput } from './bosStillPair.worker';
import { paintMap, type MapMode } from './bosMap';

import type { CameraModel } from './bosCameraModel';

const cameraModels: Record<CameraModel, string> = {
  translation: 'Translation',
  affine: 'Affine (rotation, scale and shear)',
  homography: 'Perspective (homography)',
};
const imageRoles = ['reference', 'disturbed'] as const;
type Role = (typeof imageRoles)[number];
type Images = Partial<Record<Role, File>>;
const modes: Record<MapMode, string> = {
  magnitude: 'Magnitude',
  horizontal: 'Signed horizontal displacement',
  vertical: 'Signed vertical displacement',
  confidence: 'Correlation confidence',
};
type TargetIdentity = { targetId: string; semanticSha256: string };
function readTarget(value: unknown): TargetIdentity | null {
  if (typeof value !== 'object' || value === null || !('targetId' in value) || !('semanticSha256' in value))
    return null;
  if (
    typeof value.targetId !== 'string' ||
    !/^bos-[0-9a-f]{12}$/.test(value.targetId) ||
    typeof value.semanticSha256 !== 'string' ||
    !/^[0-9a-f]{64}$/.test(value.semanticSha256)
  )
    return null;
  return { targetId: value.targetId, semanticSha256: value.semanticSha256 };
}

function useObjectUrl(blob?: Blob) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    if (!blob) {
      setUrl('');
      return;
    }
    const value = URL.createObjectURL(blob);
    setUrl(value);
    return () => URL.revokeObjectURL(value);
  }, [blob]);
  return url;
}

export function BosStillPairPanel({ target }: { target: BosTargetManifest | null }) {
  const [restoredTarget, setRestoredTarget] = useState<TargetIdentity | null | undefined>(undefined);
  const [analyzedTarget, setAnalyzedTarget] = useState<TargetIdentity | null>(null);
  const [cameraModel, setCameraModel] = useState<CameraModel>('translation');
  const [files, setFiles] = useState<Images>({});
  const [manualArea, setManualArea] = useState<Region | null>(null);
  const [analyzedQuality, setAnalyzedQuality] = useState<QualityOutput['result'] | null>(null);
  const qualityTarget =
    restoredTarget === undefined || restoredTarget?.semanticSha256 === target?.semanticSha256 ? target : null;
  const inspection = useReferenceInspection(files.reference, files.disturbed, qualityTarget, manualArea);
  const [output, setOutput] = useState<WorkerOutput>();
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [mode, setMode] = useState<MapMode>('magnitude'),
    [gain, setGain] = useState(1);
  const [inputGeneration, setInputGeneration] = useState(0);
  const [preview, setPreview] = useState<'reference' | 'stabilized'>('reference');
  const worker = useRef<Worker | null>(null),
    map = useRef<HTMLCanvasElement>(null);
  useEffect(() => () => worker.current?.terminate(), []);
  useEffect(() => {
    if (map.current && output) paintMap(map.current, output.result, mode, gain);
  }, [output, mode, gain]);
  const report = useMemo(
    () =>
      output &&
      new Blob(
        [
          JSON.stringify({
            ...output.result,
            configuredTarget: analyzedTarget,
            referenceQuality: analyzedQuality,
            visualization: mode,
            amplification: gain,
            displayedLimitPx: mode === 'confidence' ? null : output.result.autoLimitPx / gain,
          }),
        ],
        { type: 'application/json' },
      ),
    [output, mode, gain, analyzedTarget, analyzedQuality],
  );
  const referenceUrl = useObjectUrl(output?.referencePreview),
    stabilizedUrl = useObjectUrl(output?.stabilizedPreview);
  const reportUrl = useObjectUrl(report);

  function stop() {
    worker.current?.terminate();
    worker.current = null;
    setStatus('');
  }
  function changeFiles(next: Images) {
    stop();
    if (next.reference !== files.reference) setManualArea(null);
    setFiles(next);
    setRestoredTarget(undefined);
    setOutput(undefined);
    setError('');
  }
  function restore(params: Record<string, unknown>, restored: Partial<Record<string, File>>) {
    changeFiles({ reference: restored.reference, disturbed: restored.disturbed });
    setRestoredTarget(readTarget(params.configuredTarget));
    setManualArea(readRegion(params.analysisRegion));
    setCameraModel(
      params.cameraModel === 'affine' || params.cameraModel === 'homography'
        ? params.cameraModel
        : 'translation',
    );
    setInputGeneration((n) => n + 1);
    setMode(
      typeof params.visualization === 'string' &&
        Object.prototype.hasOwnProperty.call(modes, params.visualization)
        ? (params.visualization as MapMode)
        : 'magnitude',
    );
    setGain(
      typeof params.amplification === 'number' && Number.isFinite(params.amplification)
        ? Math.max(0.25, Math.min(8, params.amplification))
        : 1,
    );
  }
  function analyze() {
    if (!files.reference || !files.disturbed) return;
    setAnalyzedQuality(inspection?.data?.result ?? null);
    setAnalyzedTarget(restoredTarget === undefined ? readTarget(target) : restoredTarget);
    stop();
    setOutput(undefined);
    setError('');
    try {
      const task = new Worker(new URL('./bosStillPair.worker.ts', import.meta.url), { type: 'module' });
      worker.current = task;
      setStatus('Reading source images…');
      task.onmessage = ({ data }: MessageEvent<WorkerOutput | { stage: string } | { error: string }>) => {
        if (worker.current !== task) return;
        if ('stage' in data) {
          setStatus(data.stage);
          return;
        }
        stop();
        if ('error' in data) setError(data.error);
        else setOutput(data);
      };
      task.onerror = () => {
        if (worker.current === task) {
          stop();
          setError('Could not process the image pair in this browser.');
        }
      };
      task.postMessage({
        ...files,
        model: cameraModel,
        region: manualArea ?? inspection?.data?.result.region ?? fullFrame,
      });
    } catch {
      stop();
      setError('This browser could not start image analysis.');
    }
  }

  return (
    <section aria-label="Still-pair displacement" className="card">
      <h3>Measure still-pair displacement</h3>
      <p>
        Photograph the same dot field before and during the disturbance. Keep its outer edges sharp and
        undisturbed so Fresnel can measure camera motion separately from the local displacement.
      </p>
      {imageRoles.map((role) => (
        <label key={role + inputGeneration} style={{ display: 'block', marginBlock: 8 }}>
          {role === 'reference' ? 'Reference image' : 'Disturbed image'}
          <input
            type="file"
            aria-label={role === 'reference' ? 'Reference image' : 'Disturbed image'}
            accept="image/png,image/jpeg"
            onChange={(e) => changeFiles({ ...files, [role]: e.currentTarget.files?.[0] })}
          />
          {files[role] && <small>{files[role]?.name}</small>}
        </label>
      ))}
      {files.reference && (
        <BosReferenceInspector
          state={inspection}
          onArea={(r) => {
            stop();
            setManualArea(r);
            setOutput(undefined);
            setError('');
          }}
        />
      )}
      <label style={{ display: 'block', marginBlock: 8 }}>
        Camera motion model{' '}
        <select
          value={cameraModel}
          onChange={(e) => {
            stop();
            setCameraModel(e.currentTarget.value as CameraModel);
            setOutput(undefined);
            setError('');
          }}
        >
          {Object.entries(cameraModels).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        disabled={!files.reference || !files.disturbed || Boolean(status) || !inspection}
        onClick={analyze}
      >
        Analyze still pair
      </button>
      {status && (
        <>
          <span role="status"> {status} </span>
          <button type="button" onClick={stop}>
            Cancel analysis
          </button>
        </>
      )}
      {error && <p role="alert">{error}</p>}
      {output && (
        <>
          <p role="status">
            Registered {output.result.registration.inliers} of 12 outer windows. Camera motion at the center (
            {output.result.registration.dx.toFixed(2)}, {output.result.registration.dy.toFixed(2)}) px,
            residual RMS {output.result.registration.residualRms.toFixed(2)} px. {output.result.accepted} of{' '}
            {output.result.vectors.length} displacement vectors accepted.
          </p>
          <label>
            Map{' '}
            <select aria-label="Map" value={mode} onChange={(e) => setMode(e.currentTarget.value as MapMode)}>
              {Object.entries(modes).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          {mode !== 'confidence' && (
            <label>
              {' '}
              Amplification{' '}
              <input
                type="range"
                min="0.25"
                max="8"
                step="0.25"
                value={gain}
                onChange={(e) => setGain(Number(e.currentTarget.value))}
              />{' '}
              {gain}×
            </label>
          )}
          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            <figure style={{ margin: 0, flex: '1 1 220px' }}>
              <figcaption>
                <label>
                  Source{' '}
                  <select
                    aria-label="Source view"
                    value={preview}
                    onChange={(e) => setPreview(e.currentTarget.value as typeof preview)}
                  >
                    <option value="reference">Reference</option>
                    <option value="stabilized">Stabilized disturbed image</option>
                  </select>
                </label>
              </figcaption>
              <img
                src={(preview === 'reference' ? referenceUrl : stabilizedUrl) || undefined}
                alt={preview === 'reference' ? 'Reference dot field' : 'Disturbed image after registration'}
                style={{ width: '100%' }}
              />
            </figure>
            <figure style={{ margin: 0, flex: '1 1 220px' }}>
              <figcaption>{modes[mode]}</figcaption>
              <canvas
                ref={map}
                aria-label="Displacement map"
                style={{ width: '100%', imageRendering: 'pixelated' }}
              />
            </figure>
          </div>
          <p>
            {mode === 'confidence'
              ? 'Correlation ranges from black (0) to white (1).'
              : mode === 'magnitude'
                ? 'Navy is zero and gold is ' +
                  (output.result.autoLimitPx / gain).toFixed(2) +
                  ' analysis px or greater.'
                : 'Blue is negative, white is zero and red is positive. The color limits are ±' +
                  (output.result.autoLimitPx / gain).toFixed(2) +
                  ' analysis px.'}{' '}
            Charcoal marks unmeasured or rejected windows.
          </p>
          <a href={reportUrl} download="bos-displacement.json">
            Download numeric grid
          </a>
          {' · '}
          <button
            type="button"
            onClick={() => {
              const canvas = map.current;
              if (canvas) {
                const link = document.createElement('a');
                link.href = canvas.toDataURL('image/png');
                link.download = 'bos-' + mode + '.png';
                link.click();
              }
            }}
          >
            Download map PNG
          </button>
        </>
      )}
      <MeasurementArchive
        pluginId="background-oriented-schlieren"
        steps={imageRoles}
        files={files}
        parameters={{
          cameraModel,
          visualization: mode,
          amplification: gain,
          analysisRegion: output
            ? (analyzedQuality?.region ?? manualArea ?? fullFrame)
            : (manualArea ?? inspection?.data?.result.region),
          configuredTarget: output
            ? analyzedTarget
            : restoredTarget === undefined
              ? readTarget(target)
              : restoredTarget,
        }}
        result={report}
        onRestore={restore}
      />
      <p>
        <small>
          Qualitative measurement of apparent background displacement from refractive-index gradients.
          Image-plane vectors do not measure airflow direction, velocity, density or temperature. Rolling
          shutter, compression and movement outside the fitted camera model can produce artifacts.
        </small>
      </p>
    </section>
  );
}
