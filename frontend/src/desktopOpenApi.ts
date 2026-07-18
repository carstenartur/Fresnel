import type { FresnelJobDocument } from './jobApi';

export interface DesktopOpenResult {
  valid: boolean;
  job?: FresnelJobDocument<unknown>;
  errorCode?: string;
  errorMessage?: string;
}

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{40,128}$/;

export async function consumeDesktopOpen(importId: string): Promise<DesktopOpenResult> {
  if (!TOKEN_PATTERN.test(importId)) {
    throw new Error('The desktop open token is malformed.');
  }

  const response = await fetch(`/api/desktop/open/${encodeURIComponent(importId)}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
  });
  if (response.status === 404) {
    throw new Error('The desktop open request has expired or was already consumed.');
  }
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Desktop open failed with HTTP ${response.status}.`);
  }

  const result = await response.json() as DesktopOpenResult;
  if (result.valid && !result.job) {
    throw new Error('The desktop open response did not contain a Fresnel job.');
  }
  if (!result.valid && !result.errorMessage) {
    throw new Error('The desktop open request failed without an error message.');
  }
  return result;
}
