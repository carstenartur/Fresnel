export interface BosExposure {
  byteLength: number;
  sha256: string;
  capturedAt: string;
}
export interface BosOrigin {
  provider: 'photographer-rest';
  captureId: string;
  cameraSelection?: string;
  reference: BosExposure;
  disturbed: BosExposure;
}
export interface PairedBosCapture {
  origin: BosOrigin;
  files: { reference: File; disturbed: File };
}

export async function exposureHash(file: Blob) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())), (b) =>
    b.toString(16).padStart(2, '0'),
  ).join('');
}
export function readBosOrigin(value: unknown): BosOrigin | null {
  if (!value || typeof value !== 'object') return null;
  const v = value as Partial<BosOrigin>;
  if (
    v.provider !== 'photographer-rest' ||
    typeof v.captureId !== 'string' ||
    !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v.captureId)
  )
    return null;
  function valid(source: unknown): source is BosExposure {
    if (!source || typeof source !== 'object') return false;
    const s = source as Partial<BosExposure>;
    return (
      Number.isSafeInteger(s.byteLength) &&
      s.byteLength! > 0 &&
      typeof s.sha256 === 'string' &&
      /^[a-f0-9]{64}$/.test(s.sha256) &&
      typeof s.capturedAt === 'string' &&
      Number.isFinite(Date.parse(s.capturedAt))
    );
  }
  if (!valid(v.reference) || !valid(v.disturbed)) return null;
  const fields = (s: BosExposure) => ({
    byteLength: s.byteLength,
    sha256: s.sha256,
    capturedAt: s.capturedAt,
  });
  return {
    provider: 'photographer-rest',
    captureId: v.captureId,
    ...(typeof v.cameraSelection === 'string' && /^[a-f0-9]{64}$/.test(v.cameraSelection)
      ? { cameraSelection: v.cameraSelection }
      : {}),
    reference: fields(v.reference),
    disturbed: fields(v.disturbed),
  };
}
