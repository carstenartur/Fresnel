import { ManualRegion } from './BosReferenceInspector';
import { fullFrame, readRegion, type Region } from './bosReferenceQuality';
import { BosLocalSearchControl, savedLocalSearch } from './BosLocalSearchControl';
import type { LocalSearch } from './bosDisplacement';
import type { CameraModel } from './bosCameraModel';
import { CameraModelControl, restoreCameraModel } from './CameraModelControl';
import { useMeasurementTrack } from './MeasurementWorkflow';
import {
  exposureHash,
  readBosSeriesOrigin,
  type PairedBosSeries,
  type BosSeriesOrigin,
} from './bosPairedCapture';
import { useEffect, useMemo, useRef, useState } from 'react';
import { restoreTarget, type TargetEvidence } from './bosTargetEvidence';
import { MeasurementArchive } from './MeasurementArchive';
import { paintMap, type MapMode } from './bosMap';
import {
  MAX_FRAMES,
  runSequence,
  stepName,
  type Policy,
  type FrameResult,
  type SequenceResult,
} from './bosSequence';

function draw(canvas: HTMLCanvasElement, seq: SequenceResult, index: number, mode: MapMode) {
  const frame = seq.frames[index];
  if (frame.result) paintMap(canvas, { ...frame.result, autoLimitPx: seq.colorLimitPx }, mode, 1);
  else {
    const exemplar = seq.frames.find((f) => f.result)?.result;
    canvas.width = exemplar?.width ?? 240;
    canvas.height = exemplar?.height ?? 140;
    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.fillStyle = '#25282d';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.fillStyle = 'white';
      ctx.fillText('Rejected frame ' + (index + 1), 12, 24);
    }
  }
}

async function reviewVideo(result: SequenceResult, mode: MapMode, signal: AbortSignal) {
  const canvas = document.createElement('canvas');
  draw(canvas, result, 0, mode);
  if (typeof MediaRecorder === 'undefined' || !canvas.captureStream)
    throw new Error('WebM review export is unavailable in this browser.');
  const type = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'].find((t) =>
    MediaRecorder.isTypeSupported(t),
  );
  if (!type) throw new Error('This browser has no WebM encoder.');
  const stream = canvas.captureStream(10);
  try {
    const recorder = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 1_000_000 });
    const chunks: Blob[] = [];
    const completed = new Promise<Blob>((resolve, reject) => {
      recorder.ondataavailable = (e) => {
        if (e.data.size) chunks.push(e.data);
      };
      recorder.onerror = () => reject(new Error('Could not encode review video.'));
      recorder.onstop = () => resolve(new Blob(chunks, { type: 'video/webm' }));
    });
    recorder.start();
    try {
      for (let i = 0; i < result.frames.length; i++) {
        signal.throwIfAborted();
        draw(canvas, result, i, mode);
        await new Promise((r) => setTimeout(r, 200));
      }
    } finally {
      if (recorder.state !== 'inactive') recorder.stop();
    }
    const blob = await completed;
    if (!blob.size) throw new Error('The review encoder returned an empty WebM.');
    return blob;
  } finally {
    stream.getTracks().forEach((track) => track.stop());
  }
}

export function BosSequencePanel({
  target,
  stillRegion,
  pairedCapture,
}: {
  target: TargetEvidence;
  stillRegion: Region | null;
  pairedCapture?: PairedBosSeries;
}) {
  const [origin, setOrigin] = useState<BosSeriesOrigin>();
  const disclosure = useRef<HTMLDetailsElement>(null);
  const [region, setRegion] = useState<Region | null>(null);
  const [liveFrame, setLiveFrame] = useState<FrameResult>();
  const liveCanvas = useRef<HTMLCanvasElement>(null);
  const [localSearch, setLocalSearch] = useState<LocalSearch>('pyramid');
  const [cameraModel, setCameraModel] = useState<CameraModel>('translation');
  const [restoredTarget, setRestoredTarget] = useState<TargetEvidence>();
  const [analyzedTarget, setAnalyzedTarget] = useState<TargetEvidence>({ configuredTarget: null });
  const [kind, setKind] = useState<'stills' | 'video'>('stills');
  const [files, setFiles] = useState<File[]>([]),
    [clip, setClip] = useState<File>();
  const [external, setExternal] = useState<File>(),
    [policy, setPolicy] = useState<Policy>('fixed'),
    [rate, setRate] = useState(2);
  const [result, setResult] = useState<SequenceResult>(),
    [index, setIndex] = useState(0),
    [mode, setMode] = useState<MapMode>('magnitude');
  const [status, setStatus] = useState(''),
    [error, setError] = useState(''),
    [key, setKey] = useState(0);
  const job = useRef<AbortController | null>(null),
    map = useRef<HTMLCanvasElement>(null);
  useEffect(() => () => job.current?.abort(), []);
  useEffect(() => {
    if (map.current && result) draw(map.current, result, index, mode);
  }, [result, index, mode]);
  useEffect(() => {
    if (liveFrame?.result && liveCanvas.current)
      paintMap(liveCanvas.current, liveFrame.result, 'magnitude', 1);
  }, [liveFrame]);
  useEffect(() => {
    if (!pairedCapture) return;
    reset();
    setOrigin(pairedCapture.origin);
    setFiles(pairedCapture.frames);
    setExternal(pairedCapture.reference);
    setClip(undefined);
    setRestoredTarget(undefined);
    setPolicy('fixed');
    setKind('stills');
    setKey((k) => k + 1);
    if (disclosure.current) {
      disclosure.current.open = true;
      disclosure.current.scrollIntoView({ block: 'start' });
    }
  }, [pairedCapture]);
  const report = useMemo(
    () =>
      result &&
      new Blob(
        [
          JSON.stringify({
            ...result,
            ...analyzedTarget,
            captureProvider: origin?.provider ?? 'manual',
            captureOrigin: origin ?? null,
          }),
        ],
        { type: 'application/json' },
      ),
    [result, analyzedTarget, origin],
  );
  const sources = useMemo(() => {
    const items: Record<string, File> = {};
    if (external && (policy === 'fixed' || origin)) items.reference = external;
    if (kind === 'video') {
      if (clip) items.video = clip;
    } else {
      files.forEach((file, i) => { items[stepName(i)] = file; });
    }
    return items;
  }, [external, policy, origin, kind, clip, files]);
  const steps = kind === 'video' && !clip ? [...Object.keys(sources), 'video'] : Object.keys(sources);
  const [cancelled, setCancelled] = useState(false);
  const activateSeries = useMeasurementTrack('bos/series', useMemo(() => ({
    artifacts: Object.entries(sources).map(([role,file])=>({
      role, file, sha256:role==='reference' ? result?.reference?.sha256 : role==='video' ?
        result?.sourceVideo?.sha256 : result?.frames[Number(role.slice(6))-1]?.sha256,
    })),
    planned: (kind === 'video' ? 1 : Math.max(2, files.length)) + Number(!!sources.reference),
    ready: kind === 'video' ? !!clip : files.length >= 2,
    analyzing: !!status && !result, completed: !!result, error, cancelled,
    target: result ? analyzedTarget.configuredTarget?.targetId ?? null : restoredTarget && (restoredTarget.configuredTarget?.targetId ?? null),
  }), [sources, kind, files.length, clip, result, status, error, cancelled, restoredTarget, analyzedTarget]));
  function reset() {
    setCancelled(false);
    job.current?.abort();
    setLiveFrame(undefined);
    job.current = null;
    setStatus('');
    setError('');
    setResult(undefined);
    setIndex(0);
  }
  async function restore(params: Record<string, unknown>, sources: Record<string, File>) {
    const metadata = readBosSeriesOrigin(params.captureOrigin);
    if (params.captureOrigin) {
      const hashes = metadata && [metadata.reference, ...metadata.frames];
      const roles = metadata && ['reference', ...metadata.frames.map((_, i) => stepName(i))];
      if (!metadata || !hashes || !roles || roles.length !== Object.keys(sources).length)
        throw new Error('Incomplete Photographer capture provenance.');
      for (let i = 0; i < roles.length; i++) {
        const file = sources[roles[i]],
          evidence = hashes[i];
        if (!file || file.size !== evidence.byteLength || (await exposureHash(file)) !== evidence.sha256)
          throw new Error('The saved Photographer sequence failed source verification.');
      }
    }
    reset();
    setOrigin(metadata ?? undefined);
    setRestoredTarget(restoreTarget(params));
    setRegion(readRegion(params.manualRegion));
    setKey((k) => k + 1);
    setKind(sources.video || params.sourceKind === 'video' ? 'video' : 'stills');
    setExternal(sources.reference);
    setClip(sources.video);
    setFiles(
      Object.keys(sources)
        .filter((k) => /^frame-\d\d$/.test(k))
        .sort()
        .map((k) => sources[k]),
    );
    setRate(typeof params.rate === 'number' && params.rate >= 0.25 && params.rate <= 12 ? params.rate : 2);
    setCameraModel(restoreCameraModel(params.cameraModel));
    setLocalSearch(savedLocalSearch(params.localSearch));
    setPolicy(params.policy === 'median' ? 'median' : 'fixed');
  }
  function download(blob: Blob, name: string) {
    const url = URL.createObjectURL(blob),
      a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function run(exporting: boolean) {
    setCancelled(false);
    job.current?.abort();
    const controller = new AbortController();
    job.current = controller;
    setStatus(exporting ? 'Encoding review…' : 'Reading sources…');
    setError('');
    try {
      if (exporting && result) {
        const blob = await reviewVideo(result, mode, controller.signal);
        controller.signal.throwIfAborted();
        download(blob, 'bos-review.webm');
      } else {
        setResult(undefined);
        setIndex(0);
        setAnalyzedTarget(restoredTarget ?? target);
        const next = await runSequence(
          files,
          kind === 'video' ? clip : undefined,
          policy === 'fixed' ? external : undefined,
          policy,
          rate,
          cameraModel,
          localSearch,
          region ?? fullFrame,
          controller.signal,
          (s) => {
            if (!controller.signal.aborted) setStatus(s);
          },
          (f) => {
            if (!controller.signal.aborted) setLiveFrame(f);
          },
        );
        if (!controller.signal.aborted) {
          if (origin) {
            const start = Date.parse(origin.frames[0].capturedAt);
            next.timeBasis = 'Elapsed seconds from first disturbed Photographer exposure (recorded UTC)';
            if (next.reference) next.reference.name = 'reference.png';
            next.frames.forEach((frame, i) => {
              frame.timeSeconds = (Date.parse(origin.frames[i].capturedAt) - start) / 1000;
              frame.name = stepName(i) + '.png';
            });
          }
          setResult(next);
        }
      }
    } catch (e) {
      if (!controller.signal.aborted)
        setError(e instanceof Error ? e.message : 'Sequence analysis failed.');
    } finally {
      if (job.current === controller) {
        setStatus('');
        setLiveFrame(undefined);
        job.current = null;
      }
    }
  }
  const frame = result?.frames[index];
  return (
    <details ref={disclosure} id="bos-series" tabIndex={-1} onFocusCapture={activateSeries} className="capture-provider-status-details">
      <summary>Analyze a sequence or video</summary>
      <section aria-label="BOS time series">
        <p>
          Use ordered dot-field exposures or an MP4 up to 30 seconds. The analysis samples at most{' '}
          {MAX_FRAMES} frames and registers each to the same reference.
        </p>
        {origin && (
          <p>
            Photographer capture {origin.captureId} · {origin.frames.length} verified disturbed exposures ·
            recorded UTC capture times.
          </p>
        )}
        <fieldset disabled={Boolean(status)}>
          <label>
            Source type{' '}
            <select
              aria-label="Source type"
              value={kind}
              onChange={(e) => {
                reset();
                setOrigin(undefined);
                setRegion(null);
                setKind(e.target.value as typeof kind);
              }}
            >
              <option value="stills">Ordered stills</option>
              <option value="video">MP4 video</option>
            </select>
          </label>
          {kind === 'stills' ? (
            <>
              <label>
                Ordered frames{' '}
                <input
                  key={key + 'stills'}
                  aria-label="Ordered frames"
                  type="file"
                  multiple
                  accept="image/png,image/jpeg"
                  onChange={(e) => {
                    reset();
                    setRegion(null);
                    setRestoredTarget(undefined);
                    setOrigin(undefined);
                    const chosen = Array.from(e.target.files ?? []);
                    setFiles([]);
                    if (chosen.length > MAX_FRAMES) {
                      setError('Choose at most 24 images.');
                      return;
                    }
                    setFiles(chosen);
                  }}
                />
              </label>
              {!!files.length && (
                <ol>
                  {files.map((f, i) => (
                    <li key={i}>
                      {f.name}
                      {i > 0 && (
                        <button
                          type="button"
                          onClick={() => {
                            reset();
                            setOrigin(undefined);
                            const ordered = files.slice();
                            [ordered[i - 1], ordered[i]] = [ordered[i], ordered[i - 1]];
                            setFiles(ordered);
                          }}
                        >
                          Move earlier
                        </button>
                      )}
                    </li>
                  ))}
                </ol>
              )}
            </>
          ) : (
            <label>
              MP4 clip{' '}
              <input
                key={key + 'movie'}
                aria-label="MP4 clip"
                type="file"
                accept="video/mp4,.mp4"
                onChange={(e) => {
                  reset();
                  setRestoredTarget(undefined);
                  setOrigin(undefined);
                  setRegion(null);
                  setClip(e.target.files?.[0]);
                }}
              />
              {clip?.name}
            </label>
          )}
          {(kind === 'video' ? clip : files.length > 0) && (
            <>
              {stillRegion && (
                <button type="button" onClick={() => {
                  reset();
                  setRegion(stillRegion);
                }}>
                  Use the manually selected still-pair region
                </button>
              )}
              <ManualRegion
                key={JSON.stringify(region)}
                region={region ?? fullFrame}
                resetLabel="Use full image"
                apply={next => {
                  reset();
                  setRegion(next);
                }}
              />
              {region && (
                <p>
                  Sequence region starts at {(100 * region.x).toFixed(1)}%, {(100 * region.y).toFixed(1)}%
                  and covers {(100 * region.width).toFixed(1)}% × {(100 * region.height).toFixed(1)}% of each source.
                </p>
              )}
            </>
          )}
          <BosLocalSearchControl
            value={localSearch}
            onChange={(next) => {
              reset();
              setLocalSearch(next);
            }}
          />
          <CameraModelControl
            value={cameraModel}
            onChange={(model) => {
              reset();
              setCameraModel(model);
            }}
          />
          <label>
            Reference policy{' '}
            <select
              aria-label="Reference policy"
              value={policy}
              onChange={(e) => {
                reset();
                setPolicy(e.target.value as Policy);
              }}
            >
              <option value="fixed">Fixed reference</option>
              <option value="median">Temporal median</option>
            </select>
          </label>
          {policy === 'fixed' ? (
            <label>
              Optional reference image{' '}
              <input
                key={key + 'reference'}
                type="file"
                accept="image/png,image/jpeg"
                onChange={(e) => {
                  reset();
                  setOrigin(undefined);
                  setRegion(null);
                  setExternal(e.target.files?.[0]);
                }}
              />
              {external?.name ?? ' First frame by default.'}
            </label>
          ) : (
            <p>Median of three or five samples spread across the whole sequence.</p>
          )}
          {!origin && (
            <label>
              {kind === 'video' ? 'Requested samples per second' : 'Declared still frames per second'}{' '}
              <input
                aria-label="Frames per second"
                type="number"
                min="0.25"
                max="12"
                step="0.25"
                value={Number.isNaN(rate) ? '' : rate}
                onChange={(e) => {
                  reset();
                  setRate(e.target.valueAsNumber);
                }}
              />
            </label>
          )}
          <button
            type="button"
            disabled={kind === 'video' ? !clip : files.length < 2}
            id="bos-analyze-series" onClick={() => void run(false)}
          >
            Analyze time series
          </button>
        </fieldset>
        {status && (
          <>
            <span role="status">{status}</span>{' '}
            <button
              type="button"
              onClick={() => {
                job.current?.abort();
                job.current = null;
                setLiveFrame(undefined);
                setStatus('');
                setCancelled(!result);
              }}
            >
              Cancel
            </button>
          </>
        )}
        {status && liveFrame && (
          <figure aria-label="Live displacement preview">
            <figcaption>
              Frame {liveFrame.index + 1} at {liveFrame.timeSeconds.toFixed(3)} s {' · '}
              {liveFrame.result
                ? 'Live magnitude, per-frame color limit ' + liveFrame.result.autoLimitPx.toFixed(2) + ' px'
                : liveFrame.rejection}
            </figcaption>
            {liveFrame.result && (
              <canvas
                ref={liveCanvas}
                aria-label="Live frame magnitude"
                style={{ width: 'min(100%, 512px)' }}
              />
            )}
          </figure>
        )}
        {error && <p role="alert">{error}</p>}
        {result && frame && (
          <>
            <p role="status">
              {result.frames.filter((f) => f.result).length} of {result.frames.length} frames registered.
            </p>
            <label>
              Timeline{' '}
              <input
                aria-label="Timeline"
                type="range"
                min="0"
                max={result.frames.length - 1}
                value={index}
                onChange={(e) => setIndex(Number(e.target.value))}
              />
            </label>
            <p>
              Frame {index + 1} · {frame.timeSeconds.toFixed(3)} s · {frame.name}
            </p>
            <label>
              Time-series map{' '}
              <select
                aria-label="Time-series map"
                value={mode}
                onChange={(e) => setMode(e.target.value as MapMode)}
              >
                <option value="magnitude">Magnitude</option>
                <option value="horizontal">Signed horizontal</option>
                <option value="vertical">Signed vertical</option>
                <option value="confidence">Correlation confidence</option>
              </select>
            </label>
            <canvas
              ref={map}
              aria-label="Selected frame map"
              style={{ width: 'min(100%, 512px)', display: 'block' }}
            />
            {frame.rejection && <p>{frame.rejection}</p>}
            <p>
              All frames share a color scale.{' '}
              {mode === 'confidence'
                ? 'Black to white spans correlations of 0 to 1.'
                : 'The display limit is ' + result.colorLimitPx.toFixed(2) + ' analysis pixels.'}{' '}
              Charcoal marks unmeasured regions.
            </p>
            <button type="button" onClick={() => report && download(report, 'bos-time-series.json')}>
              Download time-series grid
            </button>{' '}
            <button type="button" disabled={Boolean(status)} onClick={() => void run(true)}>
              Download review WebM (5 fps)
            </button>
          </>
        )}
        <MeasurementArchive anchorId="bos-series-archive"
          analysis={{ready:kind === 'video' ? !!clip : files.length >= 2,running:Boolean(status),error,
            start:() => void run(false),stop:reset}}
          pluginId="background-oriented-schlieren"
          steps={steps}
          files={sources}
          parameters={{
            sourceKind: kind,
            ...(origin ? { captureOrigin: origin } : {}),
            manualRegion: region,
            policy,
            rate,
            cameraModel,
            localSearch,
            ...(result ? analyzedTarget : (restoredTarget ?? target)),
          }}
          acceptsSteps={(s) => s.includes('video') || s.includes('frame-01')}
          result={report}
          onRestore={restore}
        />
      </section>
    </details>
  );
}
