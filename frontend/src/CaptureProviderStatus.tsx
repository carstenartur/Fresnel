import { useEffect, useRef, useState } from 'react';
import {
  fetchCaptureProviderOverview,
  type CaptureProviderHealthState,
  type CaptureProviderOverview,
} from './captureProviderApi';

const POLL_INTERVAL_MS = 15_000;

const STATE_LABELS: Record<CaptureProviderHealthState, string> = {
  NOT_CONFIGURED: 'Not configured',
  PAIRING: 'Pairing',
  CONNECTED: 'Connected',
  DEGRADED: 'Degraded',
  DISCONNECTED: 'Disconnected',
  INCOMPATIBLE: 'Incompatible',
  AUTHENTICATION_FAILED: 'Authentication failed',
  REVOKED: 'Revoked',
};

/** Compact global status for Photographer and other capture-provider adapters. */
export function CaptureProviderStatus() {
  const [overview, setOverview] = useState<CaptureProviderOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const activeRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    let mounted = true;

    const refresh = async () => {
      activeRequest.current?.abort();
      const controller = new AbortController();
      activeRequest.current = controller;
      try {
        const next = await fetchCaptureProviderOverview(controller.signal);
        if (!mounted) return;
        setOverview(next);
        setError(null);
      } catch (requestError) {
        if (!mounted || controller.signal.aborted) return;
        setError(requestError instanceof Error ? requestError.message : String(requestError));
      }
    };

    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, POLL_INTERVAL_MS);
    return () => {
      mounted = false;
      window.clearInterval(timer);
      activeRequest.current?.abort();
      activeRequest.current = null;
    };
  }, []);

  const state: CaptureProviderHealthState = error
    ? 'DISCONNECTED'
    : overview?.state ?? 'NOT_CONFIGURED';
  const label = error && !overview ? 'Status unavailable' : STATE_LABELS[state];
  const providerNames = overview?.providers.map((provider) => provider.displayName) ?? [];
  const connectedDevices = overview?.providers
    .flatMap((provider) => provider.devices)
    .filter((device) => device.state === 'CONNECTED').length ?? 0;

  return (
    <section
      className={`capture-provider-status state-${state.toLowerCase().replace(/_/g, '-')}`}
      data-testid="capture-provider-status"
      data-state={state}
      aria-live="polite"
      aria-label={`Camera service: ${label}`}
    >
      <div className="capture-provider-status-heading">
        <span className="capture-provider-status-dot" aria-hidden="true" />
        <strong>Camera service</strong>
        <span>{label}</span>
      </div>

      {error ? (
        <p className="capture-provider-status-message">
          Fresnel could not refresh the camera-service status. Existing measurement data is unaffected.
        </p>
      ) : state === 'NOT_CONFIGURED' ? (
        <p className="capture-provider-status-message">
          No Photographer or other remote capture service is configured.
        </p>
      ) : state === 'CONNECTED' ? (
        <p className="capture-provider-status-message">
          {providerNames.join(', ')} · {connectedDevices} connected {connectedDevices === 1 ? 'camera' : 'cameras'}
        </p>
      ) : (
        <p className="capture-provider-status-message">
          {providerNames.length > 0 ? providerNames.join(', ') : 'Capture service'} requires attention.
        </p>
      )}

      {overview && overview.providers.length > 0 && (
        <details className="capture-provider-status-details">
          <summary>Connection details</summary>
          <ul>
            {overview.providers.map((provider) => (
              <li key={provider.id}>
                <strong>{provider.displayName}</strong>: {STATE_LABELS[provider.state]}
                {provider.devices.length > 0 && (
                  <ul>
                    {provider.devices.map((device) => (
                      <li key={device.id}>
                        {device.displayName}: {device.state.toLowerCase().replace(/_/g, ' ')}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}

      {overview && (
        <time dateTime={overview.checkedAt} className="capture-provider-status-time">
          Checked {formatCheckedAt(overview.checkedAt)}
        </time>
      )}
    </section>
  );
}

function formatCheckedAt(value: string): string {
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) return 'recently';
  return timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
