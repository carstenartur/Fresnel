import { analyzePair, settings } from './bosDisplacement';
import { decodeFrame } from './bosImage';

async function processPair(referenceFile: File, disturbedFile: File) {
  const reference = await decodeFrame(referenceFile);
  const disturbed = await decodeFrame(disturbedFile);
  if (
    reference.metadata.sourceWidth !== disturbed.metadata.sourceWidth ||
    reference.metadata.sourceHeight !== disturbed.metadata.sourceHeight
  ) {
    throw new Error('The orientation-corrected reference and disturbed image dimensions must match.');
  }
  self.postMessage({ stage: 'Registering outer dot field and measuring displacement…' });
  const analysis = analyzePair(reference.frame, disturbed.frame);
  const stabilized = new OffscreenCanvas(analysis.width, analysis.height);
  const ctx = stabilized.getContext('2d');
  if (!ctx) throw new Error('A 2D image renderer is unavailable.');
  ctx.drawImage(disturbed.canvas, -analysis.registration.dx, -analysis.registration.dy);
  const referencePreview = await reference.canvas.convertToBlob({ type: 'image/png' });
  const stabilizedPreview = await stabilized.convertToBlob({ type: 'image/png' });
  return {
    referencePreview,
    stabilizedPreview,
    result: {
      pluginId: 'background-oriented-schlieren',
      algorithmVersion: 'background-oriented-schlieren/1',
      method: 'still-pair-translation-zncc/1',
      captureProvider: 'manual',
      schemaVersion: 1,
      coordinateSystem:
        'Orientation-corrected reference analysis pixels. Origin top left, +x right, +y down.',
      luminance: 'BT.709 coefficients on browser-decoded sRGB values. Alpha composites on white.',
      sources: { reference: reference.metadata, disturbed: disturbed.metadata },
      settings,
      ...analysis,
    },
  };
}
export type WorkerOutput = Awaited<ReturnType<typeof processPair>>;
self.onmessage = async ({ data }: MessageEvent<{ reference: File; disturbed: File }>) => {
  try {
    self.postMessage(await processPair(data.reference, data.disturbed));
  } catch (failure) {
    self.postMessage({
      error: failure instanceof Error ? failure.message : 'Could not analyze this pair.',
    });
  }
};
