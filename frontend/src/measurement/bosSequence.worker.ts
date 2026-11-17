import { fullFrame, type Region } from './bosReferenceQuality';
import { stabilize, type CameraModel } from './bosCameraModel';
import { analyzePair, register, type LocalSearch, type GrayFrame, type PairResult } from './bosDisplacement';
import { decodeFrame } from './bosImage';

export interface FrameInput {
  file: File;
  presentedTimeSeconds?: number;
  croppedSourceRegion?: Region;
  sourceWidth?: number;
  sourceHeight?: number;
}
export type Request = { kind: 'reference'; inputs: FrameInput[]; cameraModel: CameraModel; localSearch: LocalSearch; region: Region } | { kind: 'frame'; input: FrameInput };
export type Reply =
  | { kind: 'ready'; sourceHashes: string[]; pixelSha256: string; width: number; height: number }
  | {
      kind: 'result';
      result: PairResult | null;
      reason?: string;
      sha256: string;
      sourceWidth: number;
      sourceHeight: number;
      sourceRegion: Region;
      sourcePixelsPerAnalysisPixel: { x: number; y: number };
    }
  | { kind: 'error'; message: string };
let reference: GrayFrame | undefined;
let region = fullFrame;
let cameraModel: CameraModel = 'translation';
let localSearch: LocalSearch = 'pyramid';
let width = 0,
  height = 0;

async function read(input: FrameInput) {
  const { frame, metadata } = await decodeFrame(input.file, input.croppedSourceRegion ? fullFrame : region);
  const w = input.sourceWidth ?? metadata.sourceWidth,
    h = input.sourceHeight ?? metadata.sourceHeight;
  if (!width) {
    width = w;
    height = h;
  }
  if (w !== width || h !== height)
    throw new Error('All oriented source images must have the same dimensions.');
  const sourceRegion = input.croppedSourceRegion ?? metadata.sourceRegion;
  return {
    frame, sha256: metadata.sha256, sourceWidth: w, sourceHeight: h, sourceRegion,
    sourcePixelsPerAnalysisPixel: { x: sourceRegion.width / frame.width, y: sourceRegion.height / frame.height },
  };
}

// Warp each median sample onto the first reference grid before taking the temporal median.
function align(first: GrayFrame, frame: GrayFrame): GrayFrame {
  const registration = register(first, frame, cameraModel);
  if (cameraModel !== 'translation') return stabilize(frame, registration.matrix);
  const { dx, dy } = registration;
  const { width, height } = first,
    pixels = new Uint8Array(width * height),
    p = frame.pixels;
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const fx = Math.min(width - 1, Math.max(0, x + dx)),
        fy = Math.min(height - 1, Math.max(0, y + dy));
      const ix = Math.floor(fx),
        iy = Math.floor(fy),
        jx = Math.min(ix + 1, width - 1),
        jy = Math.min(iy + 1, height - 1);
      const a = fx - ix,
        b = fy - iy;
      pixels[y * width + x] = Math.round(
        (1 - b) * ((1 - a) * p[iy * width + ix] + a * p[iy * width + jx]) +
          b * ((1 - a) * p[jy * width + ix] + a * p[jy * width + jx]),
      );
    }
  return { width, height, pixels };
}
async function process(message: Request): Promise<Reply> {
  if (message.kind === 'reference') {
    if (!message.inputs.length || message.inputs.length > 5)
      throw new Error('Choose 1–5 reference samples.');
    region = message.region;
    cameraModel = message.cameraModel;
    localSearch = message.localSearch;
    const frames: GrayFrame[] = [];
    const sourceHashes: string[] = [];
    for (const item of message.inputs) {
      const { frame, sha256 } = await read(item);
      sourceHashes.push(sha256);
      frames.push(frames.length ? align(frames[0], frame) : frame);
    }
    const first = frames[0];
    if (frames.length === 1) reference = first;
    else {
      const pixels = new Uint8Array(first.pixels.length),
        values = new Uint8Array(frames.length);
      for (let i = 0; i < pixels.length; i++) {
        for (let j = 0; j < frames.length; j++) values[j] = frames[j].pixels[i];
        values.sort();
        pixels[i] = values[values.length >> 1];
      }
      reference = { width: first.width, height: first.height, pixels };
    }
    const checksum = await crypto.subtle.digest('SHA-256', new Uint8Array(reference.pixels));
    return {
      kind: 'ready',
      sourceHashes,
      width: reference.width,
      height: reference.height,
      pixelSha256: Array.from(new Uint8Array(checksum), (b) => b.toString(16).padStart(2, '0')).join(''),
    };
  }
  if (!reference) throw new Error('Missing registered reference.');
  const { frame, ...source } = await read(message.input);
  try {
    return { kind: 'result', result: analyzePair(reference, frame, cameraModel, localSearch), ...source };
  } catch (error) {
    return {
      kind: 'result',
      result: null,
      reason: error instanceof Error ? error.message : 'Rejected frame',
      ...source,
    };
  }
}
self.onmessage = async ({ data }: MessageEvent<Request>) => {
  try {
    self.postMessage(await process(data));
  } catch (e) {
    self.postMessage({
      kind: 'error',
      message: e instanceof Error ? e.message : 'Could not process frame.',
    });
  }
};
