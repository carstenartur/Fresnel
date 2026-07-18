import type { WindowFoilRequest } from './api';

/** Download the production-resolution PNG advertised by the Window Foil plugin. */
export async function downloadWindowFoilPng(
  request: WindowFoilRequest,
  filename = 'fresnel-window-foil.png',
): Promise<void> {
  const response = await fetch('/api/designs/foil/export.png', {
    method: 'POST',
    headers: {
      Accept: 'image/png',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Window Foil PNG export failed with HTTP ${response.status}.`);
  }

  const url = URL.createObjectURL(await response.blob());
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
  } finally {
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
