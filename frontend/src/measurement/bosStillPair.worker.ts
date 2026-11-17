import { cameraLimits, stabilize, type CameraModel } from './bosCameraModel';
import { analyzePair, settings } from './bosDisplacement';
import { decodeFrame } from './bosImage';
import { fullFrame, type Region } from './bosReferenceQuality';

async function processPair(
  referenceFile: File,
  disturbedFile: File,
  region: Region = fullFrame,
  model: CameraModel = 'translation',
) {
  const reference = await decodeFrame(referenceFile, region);
  const disturbed = await decodeFrame(disturbedFile, region);
  if (
    reference.metadata.sourceWidth !== disturbed.metadata.sourceWidth ||
    reference.metadata.sourceHeight !== disturbed.metadata.sourceHeight
  ) {
    throw new Error('The orientation-corrected reference and disturbed image dimensions must match.');
  }
  self.postMessage({ stage: 'Registering outer dot field and measuring displacement…' });
  const analysis = analyzePair(reference.frame, disturbed.frame, model);
  const stabilized = new OffscreenCanvas(analysis.width, analysis.height);
  const ctx = stabilized.getContext('2d');
  if (!ctx) throw new Error('A 2D image renderer is unavailable.');
  if (model === 'translation') {
    ctx.drawImage(disturbed.canvas, -analysis.registration.dx, -analysis.registration.dy);
  } else {
    const luma = stabilize(disturbed.frame, analysis.registration.matrix);
    const rgba = ctx.createImageData(luma.width, luma.height);
    for (let i = 0; i < luma.pixels.length; i++) {
      rgba.data[i * 4] = rgba.data[i * 4 + 1] = rgba.data[i * 4 + 2] = luma.pixels[i];
      rgba.data[i * 4 + 3] = 255;
    }
    ctx.putImageData(rgba, 0, 0);
  }
  const referencePreview = await reference.canvas.convertToBlob({ type: 'image/png' });
  const stabilizedPreview = await stabilized.convertToBlob({ type: 'image/png' });
  return {
    referencePreview,
    stabilizedPreview,
    result: {
      pluginId: 'background-oriented-schlieren',
      algorithmVersion: 'background-oriented-schlieren/1',
      method: 'still-pair-' + model + '-zncc/1',
      captureProvider: 'manual',
      schemaVersion: 1,
      coordinateSystem:
        "Orientation-corrected reference analysis pixels. Origin is the selected region's top left, +x right, +y down. Each sourceRegion records the source-pixel offset.",
      luminance: 'BT.709 coefficients on browser-decoded sRGB values. Alpha composites on white.',
      sources: { reference: reference.metadata, disturbed: disturbed.metadata },
      settings: model === 'translation' ? settings : { ...settings, ...cameraLimits },
      registrationCoordinates:
        'Row-major 3 by 3 homogeneous matrix maps reference analysis pixels to disturbed analysis pixels. Divide x and y by w. Local vectors are expressed on the reference grid.',
      ...analysis,
    },
  };
}
export type WorkerOutput = Awaited<ReturnType<typeof processPair>>;
self.onmessage = async ({
  data,
}: MessageEvent<{ reference: File; disturbed: File; region?: Region; model?: CameraModel }>) => {
  try {
    self.postMessage(await processPair(data.reference, data.disturbed, data.region, data.model));
  } catch (failure) {
    self.postMessage({
      error: failure instanceof Error ? failure.message : 'Could not analyze this pair.',
    });
  }
};
