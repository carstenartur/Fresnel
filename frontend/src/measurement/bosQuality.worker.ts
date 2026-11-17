import { decodeFrame } from './bosImage';
import { assessReference, fullFrame, type Region } from './bosReferenceQuality';
import type { TargetGeometry } from './bosMarkers';

export interface QualityRequest {
  reference: File;
  disturbed?: File;
  target: TargetGeometry | null;
  manual: Region | null;
}
async function inspect(data: QualityRequest) {
  const decoded = await decodeFrame(data.reference, fullFrame, 1024);
  const result = assessReference(decoded.frame, data.target, data.manual, decoded.metadata);
  let disturbedEdgeWidthPx: number | null = null;
  if (data.disturbed) {
    try {
      const other = await decodeFrame(data.disturbed, fullFrame, 1024);
      if (
        other.metadata.sourceWidth === decoded.metadata.sourceWidth &&
        other.metadata.sourceHeight === decoded.metadata.sourceHeight
      ) {
        disturbedEdgeWidthPx = assessReference(other.frame, null, result.region, other.metadata).sharpnessPx;
      }
    } catch {
      // A faulty disturbed image cannot invalidate the evidence from a valid reference.
      // The still-pair worker reports detailed file errors when analysis is requested.
    }
  }
  return {
    result: {
      ...result,
      referenceSha256: decoded.metadata.sha256,
      disturbedEdgeWidthPx,
      sourcePixelsPerQualityPixel: decoded.metadata.sourcePixelsPerAnalysisPixel,
    },
    preview: await decoded.canvas.convertToBlob({ type: 'image/png' }),
  };
}
export type QualityOutput = Awaited<ReturnType<typeof inspect>>;
self.onmessage = async ({ data }: MessageEvent<QualityRequest>) => {
  try {
    self.postMessage(await inspect(data));
  } catch (e) {
    self.postMessage({ error: e instanceof Error ? e.message : 'Could not read the reference image.' });
  }
};
