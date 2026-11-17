import { useState, type FormEvent } from 'react';

const ENDPOINT = '/api/admin/capture-providers/photographer/pairing';
const MESSAGES: Record<string, string> = {
  PAIRING_CODE_INVALID: 'This code has expired or has already been used.',
  ALREADY_PAIRED: 'Fresnel already has a saved pairing. Forget it before pairing again.',
  RATE_LIMITED: 'Photographer received too many attempts. Try again later.',
  INCOMPATIBLE: 'This Photographer instance does not support capture protocol version 1.',
};

function basicAuthorization(data: FormData): string {
  const bytes = new TextEncoder().encode(`${data.get('username')}:${data.get('password')}`);
  return 'Basic ' + btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''));
}

export function PhotographerPairingPanel({ onChanged }: { onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const button = (event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null;
    const method = button?.value === 'forget' ? 'DELETE' : 'POST';
    if (method === 'POST' && !String(data.get('code') ?? '').trim()) {
      setMessage('Enter the single-use code issued by Photographer.');
      return;
    }
    setPending(true);
    setMessage('');
    try {
      const response = await fetch(ENDPOINT, {
        method,
        headers: { Authorization: basicAuthorization(data), 'Content-Type': 'application/json' },
        body: method === 'POST' ? JSON.stringify({ pairingCode: data.get('code') }) : undefined,
        cache: 'no-store',
        redirect: 'error',
      });
      if (response.ok) {
        form.reset();
        setMessage(method === 'POST' ? 'Paired with Photographer.' : 'Fresnel has forgotten this pairing.');
        onChanged();
      } else if (response.status === 401 || response.status === 403) {
        setMessage('Enter the credentials of a Fresnel administrator.');
      } else {
        const result = await response.json().catch(() => ({})) as { code?: string };
        setMessage(MESSAGES[result.code ?? ''] ??
          'The pairing could not be confirmed. Check Photographer before requesting a new code.');
      }
    } catch {
      setMessage('The request could not be confirmed. Check the connection before requesting a new code.');
    } finally {
      setPending(false);
    }
  }

  return (
    <details className="capture-provider-status-details" onToggle={e => {
      setOpen(e.currentTarget.open);
      setMessage('');
    }}>
      <summary>Manage Photographer pairing</summary>
      {open && <form className="photographer-pairing-form" onSubmit={submit}>
        <label>Fresnel administrator <input name="username" autoComplete="username" required /></label>
        <label>Password <input name="password" type="password" autoComplete="current-password" required /></label>
        <label>Photographer pairing code <input name="code" type="password" autoComplete="off" maxLength={512} /></label>
        <div>
          <button type="submit" value="pair" disabled={pending}>Pair with Photographer</button>
          <button type="submit" value="forget" disabled={pending}>Forget local pairing</button>
        </div>
        <p>Issue the single-use code in Photographer. To revoke a token, open the pairing in Photographer.</p>
        {message && <p role="status">{message}</p>}
      </form>}
    </details>
  );
}
