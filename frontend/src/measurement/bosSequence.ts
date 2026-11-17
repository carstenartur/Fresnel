import { settings, type PairResult } from './bosDisplacement';
import type { FrameInput, Reply, Request } from './bosSequence.worker';
export type Policy = 'fixed' | 'median';
export const MAX_FRAMES = 24;
export const stepName = (i: number) => 'frame-' + String(i + 1).padStart(2, '0');

export interface FrameResult {
  index: number;
  timeSeconds: number;
  presentedTimeSeconds?: number;
  name: string;
  sha256: string;
  sourceWidth: number;
  sourceHeight: number;
  result: PairResult | null;
  rejection?: string;
}
function waitFor(video: HTMLVideoElement, event: string, signal: AbortSignal, action: () => void) {
  return new Promise<void>((resolve, reject) => {
    signal.throwIfAborted();
    const finish = (error?: Error) => {
      clearTimeout(timer);
      video.removeEventListener(event, ready);
      video.removeEventListener('error', failed);
      signal.removeEventListener('abort', aborted);
      if (error) reject(error);
      else resolve();
    };
    const ready = () => finish(),
      failed = () => finish(new Error('This browser could not decode or seek this MP4.'));
    const aborted = () => finish(new DOMException('Cancelled', 'AbortError'));
    const timer = setTimeout(failed, 12_000);
    video.addEventListener(event, ready, { once: true });
    video.addEventListener('error', failed, { once: true });
    signal.addEventListener('abort', aborted, { once: true });
    try {
      action();
    } catch {
      failed();
    }
  });
}
// Register before changing the media position. "seeked" alone can still expose the preceding pixels.
async function presented(
  video: HTMLVideoElement,
  signal: AbortSignal,
  move: () => Promise<void>,
): Promise<number> {
  signal.throwIfAborted();
  if (!video.requestVideoFrameCallback)
    throw new Error('MP4 sampling requires a browser with video frame callbacks.');
  let callback = 0,
    timer: ReturnType<typeof setTimeout> | undefined;
  let abort = () => {};
  const display = new Promise<number>((resolve, reject) => {
    abort = () => reject(new DOMException('Cancelled', 'AbortError'));
    signal.addEventListener('abort', abort, { once: true });
    timer = setTimeout(() => reject(new Error('The MP4 decoder did not present this frame.')), 12_000);
    callback = video.requestVideoFrameCallback((_, frame) => resolve(frame.mediaTime));
  });
  try {
    const [, time] = await Promise.all([move(), display]);
    return time;
  } finally {
    clearTimeout(timer);
    video.cancelVideoFrameCallback(callback);
    signal.removeEventListener('abort', abort);
  }
}

async function openMovie(file: File, signal: AbortSignal) {
  if (!file.size || file.size > 64 * 1024 * 1024) throw new Error('Each MP4 is limited to 64 MiB.');
  const prefix = new Uint8Array(await file.slice(0, 12).arrayBuffer());
  if (String.fromCharCode(...prefix.subarray(4, 8)) !== 'ftyp')
    throw new Error('Choose a finite MP4 clip.');
  const video = document.createElement('video'),
    url = URL.createObjectURL(file);
  video.preload = 'metadata';
  video.muted = true;
  video.playsInline = true;
  video.width = 2;
  video.height = 2;
  video.tabIndex = -1;
  video.setAttribute('aria-hidden', 'true');
  video.style.position = 'absolute';
  video.style.left = '-200px';
  document.body.append(video);
  const close = () => {
    video.removeAttribute('src');
    video.load();
    video.remove();
    URL.revokeObjectURL(url);
  };
  try {
    let actualTime = await presented(video, signal, async () => {
      await waitFor(video, 'loadedmetadata', signal, () => {
        video.src = url;
      });
      const { videoWidth: w, videoHeight: h, duration } = video;
      if (
        !Number.isFinite(duration) ||
        duration <= 0 ||
        duration > 30 ||
        !w ||
        !h ||
        w > 4096 ||
        h > 4096 ||
        w * h > 8_500_000
      )
        throw new Error('Use an MP4 up to 30 seconds, 4096 px per side and 8.5 megapixels.');
      video.preload = 'auto';
    });
    const { videoWidth: w, videoHeight: h, duration } = video;
    const canvas = document.createElement('canvas'),
      scale = Math.min(1, settings.maximumSide / Math.max(w, h));
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) throw new Error('Browser image decoder is unavailable.');
    ctx.imageSmoothingQuality = 'high';
    const sample = async (t: number, index: number): Promise<FrameInput> => {
      if (video.readyState < 2 || Math.abs(t - video.currentTime) > 1e-7) {
        actualTime = await presented(video, signal, () =>
          waitFor(video, 'seeked', signal, () => {
            video.currentTime = t;
          }),
        );
      }
      signal.throwIfAborted();
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (b) => (b ? resolve(b) : reject(new Error('Could not sample video frame.'))),
          'image/png',
        ),
      );
      return {
        file: new File([blob], stepName(index) + '.png', { type: 'image/png' }),
        presentedTimeSeconds: actualTime,
        sourceWidth: w,
        sourceHeight: h,
      };
    };
    return { duration, width: w, height: h, sample, close };
  } catch (e) {
    close();
    throw e;
  }
}
async function digest(file: File) {
  const bytes = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(bytes), (x) => x.toString(16).padStart(2, '0')).join('');
}

export async function runSequence(
  files: File[],
  clip: File | undefined,
  external: File | undefined,
  policy: Policy,
  rate: number,
  signal: AbortSignal,
  onProgress: (s: string) => void,
  onFrame: (frame: FrameResult) => void,
) {
  if (!Number.isFinite(rate) || rate < 0.25 || rate > 12)
    throw new Error('Use a sampling rate between 0.25 and 12 frames per second.');
  if (
    !clip &&
    (files.length < 2 ||
      files.length > MAX_FRAMES ||
      files.reduce((s, f) => s + f.size, external?.size ?? 0) > 128 * 1024 * 1024)
  )
    throw new Error('Choose 2–24 ordered images totaling at most 128 MiB with the reference.');
  const movie = clip ? await openMovie(clip, signal) : undefined;
  let worker: Worker | undefined;
  function ask(request: Request): Promise<Reply> {
    return new Promise((resolve, reject) => {
      signal.throwIfAborted();
      const abort = () => reject(new DOMException('Cancelled', 'AbortError'));
      signal.addEventListener('abort', abort, { once: true });
      worker!.onerror = () => {
        signal.removeEventListener('abort', abort);
        reject(new Error('The analysis worker stopped.'));
      };
      worker!.onmessage = ({ data }: MessageEvent<Reply>) => {
        signal.removeEventListener('abort', abort);
        if (data.kind === 'error') reject(new Error(data.message));
        else resolve(data);
      };
      worker!.postMessage(request);
    });
  }
  try {
    worker = new Worker(new URL('./bosSequence.worker.ts', import.meta.url), { type: 'module' });
    const count = movie
      ? Math.max(2, Math.min(MAX_FRAMES, Math.ceil(movie.duration * rate)))
      : files.length;
    const time = (i: number) => (movie ? ((i + 0.5) * movie.duration) / count : i / rate);
    const frame = async (i: number): Promise<FrameInput> =>
      movie ? movie.sample(time(i), i) : { file: files[i] };
    if (policy === 'median' && count < 3) throw new Error('A temporal median needs at least three frames.');
    const indices =
      policy === 'fixed'
        ? [0]
        : Array.from({ length: count < 5 ? 3 : 5 }, (_, i) =>
            Math.round((i * (count - 1)) / (count < 5 ? 2 : 4)),
          );
    const refs: FrameInput[] = [];
    const referenceSamples = new Map<number, FrameInput>();
    if (policy === 'fixed' && external) refs.push({ file: external });
    else
      for (const i of indices) {
        signal.throwIfAborted();
        const sampled = await frame(i);
        refs.push(sampled);
        if (movie) referenceSamples.set(i, sampled);
      }
    onProgress('Preparing reference…');
    const provenance = await ask({ kind: 'reference', inputs: refs });
    if (provenance.kind !== 'ready') throw new Error('Unexpected reference response.');
    refs.length = 0;
    const frames: FrameResult[] = [];
    for (let i = 0; i < count; i++) {
      signal.throwIfAborted();
      onProgress('Analyzing frame ' + (i + 1) + ' of ' + count + '…');
      const input = referenceSamples.get(i) ?? (await frame(i));
      referenceSamples.delete(i);
      const value = await ask({ kind: 'frame', input });
      if (value.kind !== 'result') throw new Error('Unexpected worker result.');
      const measured: FrameResult = {
        index: i,
        timeSeconds: time(i),
        presentedTimeSeconds: input.presentedTimeSeconds,
        name: input.file.name,
        sha256: value.sha256,
        sourceWidth: value.sourceWidth,
        sourceHeight: value.sourceHeight,
        result: value.result,
        rejection: value.reason,
      };
      frames.push(measured);
      onFrame(measured);
    }
    const magnitudes = frames
      .flatMap(
        (f) =>
          f.result?.vectors.flatMap((v) =>
            v.dx === null || v.dy === null ? [] : [Math.hypot(v.dx, v.dy)],
          ) ?? [],
      )
      .sort((a, b) => a - b);
    return {
      pluginId: 'background-oriented-schlieren',
      algorithmVersion: 'background-oriented-schlieren/1',
      method: 'sequence-translation-zncc/1',
      schemaVersion: 1,
      settings,
      policy,
      rate,
      referenceFrameIndices: external && policy === 'fixed' ? [] : indices,
      referenceRaster: {
        width: provenance.width,
        height: provenance.height,
        luminance: '8-bit sRGB BT.709 luma, top-down row-major',
        sha256: provenance.pixelSha256,
      },
      referenceSourceHashes: provenance.sourceHashes,
      reference:
        external && policy === 'fixed' ? { name: external.name, sha256: provenance.sourceHashes[0] } : null,
      sourceVideo:
        movie && clip
          ? {
              name: clip.name,
              sha256: await digest(clip),
              byteLength: clip.size,
              width: movie.width,
              height: movie.height,
              duration: movie.duration,
            }
          : null,
      timeBasis: movie
        ? 'Requested sampling time and actual media presentation time in seconds'
        : 'Declared still-frame cadence in seconds',
      coordinateSystem: 'Analysis pixels in the oriented reference raster, +x right, +y down',
      colorLimitPx: Math.max(0.15, magnitudes[Math.floor(0.95 * (magnitudes.length - 1))] ?? 0),
      frames,
    };
  } finally {
    worker?.terminate();
    movie?.close();
  }
}
export type SequenceResult = Awaited<ReturnType<typeof runSequence>>;
