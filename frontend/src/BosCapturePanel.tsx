import { useEffect, useState, type FormEvent } from 'react';

const ROOT = '/api/experiments/bos-captures';

type Step = 'reference' | 'disturbed';
type Camera = { id: string; name: string };
type Source = { byteLength: number; sha256: string; capturedAt: string };
type Capture = { id: string; state: string; pendingStep: Step | null; reference: Source | null; disturbed: Source | null };
type PendingCreate = { id: string; cameraId: string };
type Operator = { username: string; authorization: string };
const labels: Record<string, string> = {
  CREATING: 'Preparing capture', WAITING_REFERENCE: 'Awaiting reference',
  WAITING_DISTURBED: 'Awaiting disturbed condition', COMPLETED: 'Completed', CANCELLED: 'Cancelled',
};

async function request(operator: Operator, path = '', method = 'GET', body?: object): Promise<Response> {
  const headers: Record<string, string> = { Authorization: operator.authorization };
  if (body) headers['Content-Type'] = 'application/json';
  const response = await fetch(ROOT + path, {
    method, headers, body: body ? JSON.stringify(body) : undefined, cache: 'no-store', redirect: 'error',
    credentials: 'omit',
  });
  if (response.ok) return response;
  if (response.status === 401 || response.status === 403) throw new Error('Fresnel denied access. Check your operator credentials.');
  throw new Error(`Fresnel returned HTTP ${response.status}. Refresh the capture before repeating an action.`);
}

function pendingKey(username: string) { return 'fresnel.bos.pending.' + username; }

function readPending(username: string): PendingCreate | null {
  try {
    const candidate = JSON.parse(sessionStorage.getItem(pendingKey(username)) ?? 'null') as PendingCreate | null;
    return candidate && /^[0-9a-f-]{36}$/i.test(candidate.id) && typeof candidate.cameraId === 'string' ? candidate : null;
  } catch { return null; }
}

function remember(username: string, entry: PendingCreate | null) {
  try {
    if (entry) sessionStorage.setItem(pendingKey(username), JSON.stringify(entry));
    else sessionStorage.removeItem(pendingKey(username));
  } catch { /* Keep the pending identity in component state when browser storage is unavailable. */ }
}

function errorMessage(failure: unknown) {
  return failure instanceof Error ? failure.message : 'Fresnel could not confirm this request.';
}

function SourceImage({ operator, capture, step, source }: {
  operator: Operator; capture: string; step: Step; source: Source;
}) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let live = true;
    let objectUrl = '';
    setUrl('');
    setError('');
    void request(operator, '/' + encodeURIComponent(capture) + '/images/' + step).then(async response => {
      const blob = await response.blob();
      if (blob.type !== 'image/png' || blob.size !== source.byteLength) throw new Error('The stored PNG failed its size or type check.');
      if (live) {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      }
    }).catch(failure => { if (live) setError(errorMessage(failure)); });
    return () => { live = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [operator, capture, step, source.sha256, source.byteLength]);

  return <figure>
    <figcaption>{step === 'reference' ? 'Reference' : 'Disturbed'} · {(source.byteLength / 1024).toFixed(1)} KiB</figcaption>
    <details><summary>SHA-256</summary><code style={{ overflowWrap: 'anywhere' }}>{source.sha256}</code></details>
    {url && <>
      <img src={url} alt={step + ' exposure'} style={{ maxWidth: '100%' }} />
      <a href={url} download={'bos-' + capture + '-' + step + '.png'}>Download {step} PNG</a>
    </>}
    {error && <p role="alert">{error}</p>}
  </figure>;
}

function Acquisition() {
  const [operator, setOperator] = useState<Operator | null>(null);
  const [cameras, setCameras] = useState<Camera[]>([]);
  const [cameraId, setCameraId] = useState('');
  const [list, setList] = useState<Capture[]>([]);
  const [current, setCurrent] = useState<Capture | null>(null);
  const [pending, setPending] = useState<PendingCreate | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');

  function show(capture: Capture) {
    setCurrent(capture);
    setReady(false);
    setList(previous => [capture, ...previous.filter(item => item.id !== capture.id)]);
  }

  async function action(work: () => Promise<void>) {
    setError('');
    setBusy(true);
    try { await work(); }
    catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const username = String(data.get('username') ?? '');
    const bytes = new TextEncoder().encode(username + ':' + String(data.get('password') ?? ''));
    const user = { username, authorization: 'Basic ' + btoa(Array.from(bytes, n => String.fromCharCode(n)).join('')) };
    await action(async () => {
      const previous: Capture[] = await (await request(user)).json();
      setOperator(user);
      setList(previous);
      const saved = readPending(username);
      const existing = previous.find(item => item.id === saved?.id);
      if (existing && existing.state !== 'CREATING') {
        show(existing);
        remember(username, null);
      } else {
        setPending(saved);
        if (existing) show(existing);
      }
      const available: Camera[] = await (await request(user, '/cameras')).json();
      setCameras(available);
      setCameraId(saved?.cameraId ?? available[0]?.id ?? '');
    });
  }

  async function create() {
    if (!operator) return;
    await action(async () => {
      const next = pending ?? { id: crypto.randomUUID(), cameraId };
      setPending(next);
      remember(operator.username, next);
      const capture: Capture = await (await request(operator, '/' + next.id, 'PUT', { cameraId: next.cameraId })).json();
      show(capture);
      if (capture.state !== 'CREATING') {
        remember(operator.username, null);
        setPending(null);
      }
    });
  }

  async function open(id: string) {
    if (!operator) return;
    await action(async () => {
      const capture: Capture = await (await request(operator, '/' + encodeURIComponent(id))).json();
      show(capture);
      if (pending?.id === id && capture.state !== 'CREATING') {
        setPending(null);
        remember(operator.username, null);
      }
    });
  }

  async function advance(step?: Step) {
    if (!operator || !current || (step && !ready)) return;
    setReady(false);
    const path = '/' + encodeURIComponent(current.id);
    await action(async () => {
      let response: Response;
      try {
        const acknowledgement = current.pendingStep === step ? { ready: true, continuous: true } : { ready: true };
        response = step
          ? await request(operator, path + '/steps/' + step, 'POST', acknowledgement)
          : await request(operator, path, 'DELETE');
      } catch (failure) {
        // Read the server's durable acknowledgement before offering another operator action.
        try { show(await (await request(operator, path)).json()); }
        catch { /* Refresh capture remains available if the service is still unreachable. */ }
        throw new Error(errorMessage(failure) + ' Check the current capture and cancel if the condition has changed.');
      }
      const capture: Capture = await response.json();
      show(capture);
      if (pending?.id === capture.id && capture.state !== 'CREATING') {
        remember(operator.username, null);
        setPending(null);
      }
    });
  }

  const step = current?.state === 'WAITING_REFERENCE' ? 'reference' : current?.state === 'WAITING_DISTURBED' ? 'disturbed' : null;
  return <div className="photographer-pairing-form">
    {!operator ? <form className="photographer-pairing-form" onSubmit={signIn}>
      <label>Fresnel username <input name="username" autoComplete="username" required /></label>
      <label>Password <input name="password" type="password" autoComplete="current-password" required /></label>
      <button type="submit" disabled={busy}>Sign in</button>
    </form> : <>
      <p>Signed in as {operator.username}. <button type="button" disabled={busy} onClick={() => {
        setOperator(null); setList([]); setCurrent(null); setCameras([]); setCameraId(''); setPending(null); setError('');
      }}>Sign out</button></p>
      <label>Camera <select value={pending?.cameraId ?? cameraId} disabled={busy || !!pending}
        onChange={event => setCameraId(event.target.value)}>
        {!cameras.length && <option value="">No camera available</option>}
        {cameras.map(camera => <option key={camera.id} value={camera.id}>{camera.name}</option>)}
      </select></label>
      <button type="button" disabled={busy || (!pending && !cameraId)} onClick={() => void create()}>
        {pending ? 'Retry BOS capture creation' : 'Start BOS capture'}
      </button>
      {list.length > 0 && <label>Saved captures <select value={current?.id ?? ''} disabled={busy}
        onChange={event => { if (event.target.value) void open(event.target.value); }}>
        <option value="">Select a capture</option>
        {list.map(capture => <option key={capture.id} value={capture.id}>
          {labels[capture.state] ?? capture.state} · {capture.id}
        </option>)}
      </select></label>}
      {current && <>
        <p role="status">{labels[current.state] ?? current.state}</p>
        {step && <>
          {current.pendingStep === step && <p>If the condition changed since the original acknowledgement, cancel this capture.</p>}
          <label><input type="checkbox" checked={ready} disabled={busy}
            onChange={event => setReady(event.target.checked)} />
            {current.pendingStep === step ? `The original ${step} condition has remained continuously in place.`
              : step === 'reference' ? 'The undisturbed reference condition is ready.' : 'The disturbed condition is ready.'}
          </label>
          <button type="button" disabled={busy || !ready} onClick={() => void advance(step)}>Capture {step}</button>
        </>}
        <div>
          <button type="button" disabled={busy} onClick={() => void open(current.id)}>Refresh capture</button>
          {['CREATING', 'WAITING_REFERENCE', 'WAITING_DISTURBED'].includes(current.state) &&
            <button type="button" disabled={busy} onClick={() => void advance()}>Cancel capture</button>}
        </div>
        {(['reference', 'disturbed'] as const).map(role => current[role] &&
          <SourceImage key={current.id + role} operator={operator} capture={current.id} step={role} source={current[role]} />)}
      </>}
    </>}
    {error && <p role="alert">{error}</p>}
  </div>;
}

export function BosCapturePanel() {
  const [open, setOpen] = useState(false);
  return <details className="capture-provider-status-details" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>Background-oriented schlieren capture</summary>
    {open && <Acquisition />}
  </details>;
}
