import { useEffect, useState } from 'react';
import type { QualityOutput, QualityRequest } from './bosQuality.worker';
import { readRegion, type Region } from './bosReferenceQuality';
import type { TargetGeometry } from './bosMarkers';

interface State {
  reference?: File;
  disturbed?: File;
  target: TargetGeometry | null;
  manual: Region | null;
  data?: QualityOutput;
  error?: string;
}
export function useReferenceInspection(
  reference: File | undefined,
  disturbed: File | undefined,
  target: TargetGeometry | null,
  manual: Region | null,
) {
  const [state, setState] = useState<State>();
  useEffect(() => {
    if (!reference) return;
    const inputs = { reference, disturbed, target, manual };
    let worker: Worker;
    let active = true;
    try {
      worker = new Worker(new URL('./bosQuality.worker.ts', import.meta.url), { type: 'module' });
    } catch {
      setState({ ...inputs, error: 'This browser could not start the reference check.' });
      return;
    }
    worker.onmessage = ({ data }: MessageEvent<QualityOutput | { error: string }>) => {
      if (!active) return;
      setState('error' in data ? { ...inputs, error: data.error } : { ...inputs, data });
      worker.terminate();
    };
    worker.onerror = () => {
      if (!active) return;
      setState({ ...inputs, error: 'The reference image could not be assessed.' });
      worker.terminate();
    };
    worker.postMessage(inputs satisfies QualityRequest);
    return () => {
      active = false;
      worker.terminate();
    };
  }, [reference, disturbed, target, manual]);
  return state?.reference === reference &&
    state?.disturbed === disturbed &&
    state?.target === target &&
    state?.manual === manual
    ? state
    : undefined;
}

const percent = (value: number) => (value * 100).toFixed(1) + '%';
const px = (value: number | null) => (value === null ? 'Insufficient edges' : value.toFixed(1) + ' px');
const keys = ['x', 'y', 'width', 'height'] as const;
const names = ['Left', 'Top', 'Width', 'Height'];
function ManualRegion({ region, apply }: { region: Region; apply: (r: Region | null) => void }) {
  const [values, setValues] = useState(() => keys.map((k) => (region[k] * 100).toFixed(2)));
  const [error, setError] = useState('');
  return (
    <details>
      <summary>Adjust analysis region</summary>
      <p>
        Enter percentages of the orientation-corrected image. Keep the quiet border and background outside the
        rectangle.
      </p>
      {keys.map((k, i) => (
        <label key={k}>
          {names[i]} (%){' '}
          <input
            type="number"
            step="0.1"
            min="0"
            max="100"
            value={values[i]}
            onChange={(e) => setValues(values.map((v, j) => (j === i ? e.target.value : v)))}
          />
        </label>
      ))}
      <button
        type="button"
        onClick={() => {
          const numbers = values.map((v) => (v.trim() === '' ? NaN : Number(v) / 100));
          const validated = readRegion({
            x: numbers[0],
            y: numbers[1],
            width: numbers[2],
            height: numbers[3],
          });
          if (!validated) {
            setError('Choose a positive rectangle fully inside the image.');
            return;
          }
          setError('');
          apply(validated);
        }}
      >
        Apply region
      </button>
      <button type="button" onClick={() => apply(null)}>
        Use automatic region
      </button>
      {error && <p>{error}</p>}
    </details>
  );
}
export function BosReferenceInspector({
  state,
  onArea,
}: {
  state: State | undefined;
  onArea: (r: Region | null) => void;
}) {
  const data = state?.data;
  const [url, setUrl] = useState('');
  useEffect(() => {
    if (!data) {
      setUrl('');
      return;
    }
    const u = URL.createObjectURL(data.preview);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [data]);
  if (!data) return <p>{state?.error ?? 'Checking reference image…'}</p>;
  const q = data.result,
    r = q.region,
    focus = q.disturbedEdgeWidthPx;
  return (
    <section aria-label="Reference image quality">
      <h4>Reference image quality</h4>
      <p>
        {q.markers
          ? `Four orientation markers matched (${percent(q.markers.meanAgreement)} mean template agreement).`
          : 'Four-marker geometry could not be verified.'}{' '}
        {q.selection === 'manual'
          ? 'Using the manual region.'
          : q.selection === 'four-fiducial'
            ? 'Using the inner rectangle of the detected dot field.'
            : 'Using the full image.'}
      </p>
      <div style={{ position: 'relative', maxWidth: 550, lineHeight: 0 }}>
        <img
          src={url || undefined}
          alt="Reference image with selected analysis area"
          style={{ width: '100%' }}
        />
        <svg
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
          aria-hidden="true"
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}
        >
          {q.markers && (
            <polygon
              points={q.markers.polygon.map((p) => p.x + ',' + p.y).join(' ')}
              fill="none"
              stroke="#ffbf47"
              strokeWidth="2"
              vectorEffect="non-scaling-stroke"
            />
          )}
          <rect
            x={r.x}
            y={r.y}
            width={r.width}
            height={r.height}
            fill="none"
            stroke="#00adff"
            strokeWidth="2"
            vectorEffect="non-scaling-stroke"
          />
        </svg>
      </div>
      <dl>
        <dt>Selected frame area</dt>
        <dd>{percent(r.width * r.height)}</dd>
        <dt>Luminance contrast (1st–99th percentile)</dt>
        <dd>{q.contrast} / 255</dd>
        <dt>Dark / bright clipped samples</dt>
        <dd>
          {percent(q.darkClippedFraction)} / {percent(q.brightClippedFraction)}
        </dd>
        <dt>Median isolated feature diameter</dt>
        <dd>
          {q.dotDiameterPx === null ? 'No resolved dots' : px(q.dotDiameterPx)} at {q.frameWidth} ×{' '}
          {q.frameHeight}
        </dd>
        <dt>Estimated edge width</dt>
        <dd>
          {px(q.sharpnessPx)}
          {focus !== null && ` (disturbed image ${px(focus)})`}
        </dd>
        <dt>15 × 15 analysis windows with at least two features</dt>
        <dd>
          {percent(q.occupiedFraction)} of {q.windowCount} windows
        </dd>
      </dl>
      {focus !== null && q.sharpnessPx !== null && focus > q.sharpnessPx * 1.5 && (
        <p>The disturbed image has broader edges. Check camera movement and lock focus between exposures.</p>
      )}
      {q.guidance.length > 0 && (
        <ul>
          {q.guidance.map((g) => (
            <li key={g}>{g}</li>
          ))}
        </ul>
      )}
      <ManualRegion key={JSON.stringify(r)} region={r} apply={onArea} />
    </section>
  );
}
