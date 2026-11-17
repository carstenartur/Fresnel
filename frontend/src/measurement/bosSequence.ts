import { cameraLimits, type CameraModel } from './bosCameraModel';
import { settings, pyramidSettings, type LocalSearch, type PairResult } from './bosDisplacement';
import type { FrameInput, Reply, Request } from './bosSequence.worker';
export type Policy = 'fixed' | 'median';
export const MAX_FRAMES = 24;
export const stepName = (i: number) => 'frame-' + String(i + 1).padStart(2, '0');

export interface FrameResult {
  index: number;
  timeSeconds: number;
  name: string;
  sha256: string;
  sourceWidth:number; sourceHeight:number;
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
  const close = () => {
    video.removeAttribute('src');
    video.load();
    URL.revokeObjectURL(url);
  };
  try {
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
    const canvas = document.createElement('canvas'),
      scale = Math.min(1, settings.maximumSide / Math.max(w, h));
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    const ctx = canvas.getContext('2d', {willReadFrequently:true});
    if (!ctx) throw new Error('Browser image decoder is unavailable.');
    ctx.imageSmoothingQuality = 'high';
    const sample = async (t: number, index: number): Promise<FrameInput> => {
      if (video.readyState < 2 || Math.abs(t - video.currentTime) > 1e-7)
        await waitFor(video, 'seeked', signal, () => {
          video.currentTime = t;
        });
      signal.throwIfAborted();
      // A seek can finish before the compositor has exposed an opaque decoded frame.
      // Reading back the raster synchronizes canvas drawing before the PNG encoder runs.
      let ready=false;
      for (let i=0;i<8&&!ready;i++) {
        signal.throwIfAborted();
        ctx.clearRect(0,0,canvas.width,canvas.height);
        ctx.drawImage(video,0,0,canvas.width,canvas.height);
        const rgba=ctx.getImageData(0,0,canvas.width,canvas.height).data;
        for (let a=3;a<rgba.length;a+=4) if (rgba[a]) {ready=true;break;}
        if(!ready) await new Promise(resolve=>setTimeout(resolve,25));
      }
      if(!ready) throw new Error('The MP4 decoder did not produce a visible frame.');
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (b) => (b ? resolve(b) : reject(new Error('Could not sample video frame.'))),
          'image/png',
        ),
      );
      return {
        file: new File([blob], stepName(index) + '.png', { type: 'image/png' }),
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
  cameraModel: CameraModel,
  localSearch: LocalSearch,
  signal: AbortSignal,
  onProgress: (s: string) => void,
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
    if (policy === 'fixed' && external) refs.push({ file: external });
    else
      for (const i of indices) {
        signal.throwIfAborted();
        refs.push(await frame(i));
      }
    onProgress('Preparing reference…');
    await ask({ kind: 'reference', inputs: refs, cameraModel, localSearch });
    refs.length = 0;
    const frames: FrameResult[] = [];
    for (let i = 0; i < count; i++) {
      signal.throwIfAborted();
      onProgress('Analyzing frame ' + (i + 1) + ' of ' + count + '…');
      const input = await frame(i);
      const value = await ask({ kind: 'frame', input });
      if (value.kind !== 'result') throw new Error('Unexpected worker result.');
      frames.push({
        index: i,
        timeSeconds: time(i),
        name: input.file.name,
        sha256: value.sha256, sourceWidth:value.sourceWidth, sourceHeight:value.sourceHeight,
        result: value.result,
        rejection: value.reason,
      });
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
      method: 'sequence-' + cameraModel + (localSearch === 'pyramid' ? '-pyramid-zncc/2' : '-zncc/1'),
      localSearch,
      pyramidSettings: localSearch === 'pyramid' ? pyramidSettings : undefined,
      cameraModel,
      schemaVersion: 1,
      settings: cameraModel === 'translation' ? settings : { ...settings, ...cameraLimits },
      policy,
      rate,
      referenceFrameIndices: external && policy === 'fixed' ? [] : indices,
      reference:
        external && policy === 'fixed' ? { name: external.name, sha256: await digest(external) } : null,
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
        ? 'Requested video presentation time in seconds'
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
