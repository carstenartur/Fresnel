import type { BosTargetManifest, BosTargetRequest } from '../bosTargetApi';

export interface TargetEvidence {
  configuredTarget: { targetId: string; semanticSha256: string } | null;
  targetParameters?: BosTargetRequest;
}
export function targetEvidence(
  manifest: BosTargetManifest | null,
  request: BosTargetRequest,
): TargetEvidence {
  if (!manifest) return { configuredTarget: null };
  return {
    configuredTarget: { targetId: manifest.targetId, semanticSha256: manifest.semanticSha256 },
    targetParameters: { ...request },
  };
}
export function restoreTarget(params: Record<string, unknown>): TargetEvidence {
  const id = params.configuredTarget;
  if (
    !id ||
    typeof id !== 'object' ||
    !('targetId' in id) ||
    !('semanticSha256' in id) ||
    typeof id.targetId !== 'string' ||
    !/^bos-[0-9a-f]{12}$/.test(id.targetId) ||
    typeof id.semanticSha256 !== 'string' ||
    !/^[0-9a-f]{64}$/.test(id.semanticSha256)
  )
    return { configuredTarget: null };
  const result: TargetEvidence = {
    configuredTarget: { targetId: id.targetId, semanticSha256: id.semanticSha256 },
  };
  const p = params.targetParameters;
  if (!p || typeof p !== 'object') return result;
  const numeric = [
    'widthPx',
    'heightPx',
    'intendedDpi',
    'patternSeed',
    'dotDiameterPx',
    'targetFillRatio',
    'minimumDotSpacingPx',
    'borderPx',
  ] as const;
  const flags = ['fiducialsEnabled', 'invertPattern'] as const;
  const raw = p as Record<string, unknown>;
  if (
    numeric.every((k) => typeof raw[k] === 'number' && Number.isFinite(raw[k])) &&
    flags.every((k) => typeof raw[k] === 'boolean')
  ) {
    result.targetParameters = Object.fromEntries(
      [...numeric, ...flags].map((k) => [k, raw[k]]),
    ) as unknown as BosTargetRequest;
  }
  return result;
}
