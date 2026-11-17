import { useState, type FormEvent } from 'react';

const root = '/api/measurement-sessions';
const bundleType = 'application/vnd.carstenartur.fresnel.experiment+zip';
type Files = Partial<Record<string, File>>;
type Asset = { sha256: string; byteLength: number; mediaType: string };
type Saved = { id: string; created: string; pluginId: string; parameters: Record<string, unknown>;
  steps: string[]; assets: Record<string, Asset>; state: string; captureProvider?:string };

interface Props {
  pluginId: string;
  parameters: object;
  steps: readonly string[];
  files: Files;
  result?: Blob | null;
  acceptsSteps?: (steps: readonly string[]) => boolean;
  sourceSessionId?: string;
  onRestore: (parameters: Record<string, unknown>, files: Record<string, File>, sourceId?: string) => void | Promise<void>;
}

async function sha256(blob: Blob) {
  const bytes = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
}

async function request(auth: string, path = '', options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  headers.set('Authorization', auth);
  const response = await fetch(root + path, { ...options, headers, credentials: 'omit', redirect: 'error', cache: 'no-store' });
  if (response.ok) return response;
  const errors: Record<number,string> = { 400: 'Fresnel rejected the capture plan, file or checksum.',
    401: 'Check your Fresnel username and password.', 403: 'Access denied.', 404: 'The saved experiment is unavailable.',
    409: 'The saved experiment conflicts with this capture. Check the session and available storage.',
    413: 'The experiment exceeds the upload limit.' };
  throw new Error(errors[response.status] ?? ('Fresnel returned HTTP ' + response.status + '.'));
}

export function MeasurementArchive({pluginId, parameters, steps, files, result, onRestore, acceptsSteps, sourceSessionId}: Props) {
  const [auth, setAuth] = useState('');
  const [saved, setSaved] = useState<Saved[]>([]);
  const [selected, select] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState<{key:string; id:string} | null>(null);

  async function action(run: () => Promise<void>) {
    setBusy(true); setStatus(''); setError('');
    try { await run(); } catch (e) { setError(e instanceof Error ? e.message : 'Measurement operation failed.'); }
    finally { setBusy(false); }
  }

  async function refresh(authorization: string) {
    const all: Saved[] = await (await request(authorization)).json();
    const matches = all.filter(s => s.pluginId === pluginId && (!acceptsSteps || acceptsSteps(s.steps)));
    setSaved(matches);
    return matches;
  }
  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const bytes = new TextEncoder().encode(String(data.get('username')) + ':' + String(data.get('password')));
    const header = 'Basic ' + btoa(Array.from(bytes, b => String.fromCharCode(b)).join(''));
    await action(async () => {
      const matches = await refresh(header);
      select(matches[0]?.id ?? ''); form.reset(); setAuth(header);
    });
  }

  async function save() {
    const items: Array<{step:string; file:Blob; hash:string; type:string}> = [];
    for (const step of steps) {
      const file = files[step];
      if (!file) continue;
      if (step === 'video') {
        if (file.size > 64 * 1024 * 1024) throw new Error('Each MP4 must be 64 MiB or smaller.');
        items.push({step, file, type:'video/mp4', hash:await sha256(file)});
        continue;
      }
      if (file.size > 20 * 1024 * 1024) throw new Error('Each source image must be 20 MiB or smaller.');
      const type = file.type || (/\.png$/i.test(file.name) ? 'image/png' : /\.jpe?g$/i.test(file.name) ? 'image/jpeg' : '');
      if (type !== 'image/png' && type !== 'image/jpeg') throw new Error('Choose a PNG or JPEG source image.');
      items.push({step, file, type, hash: await sha256(file)});
    }
    if (result && items.length === steps.length) items.push({step:'result', file:result, type:'application/json', hash:await sha256(result)});
    const plan = {pluginId, parameters, steps};
    const key = JSON.stringify([sourceSessionId, plan, items.map(i => [i.step, i.hash])]);
    const current = saved.find(s => s.id === selected);
    const samePlan = current && current.pluginId === pluginId && JSON.stringify(current.steps) === JSON.stringify(steps)
      && JSON.stringify(current.parameters) === JSON.stringify(parameters);
    const sameFiles = current && Object.entries(current.assets).every(([step, asset]) =>
      items.some(item => item.step === step && item.hash === asset.sha256));
    const reusable = samePlan && sameFiles && current?.captureProvider !== 'photographer-rest';
    const id = pending?.key === key ? pending.id : !sourceSessionId && reusable && current ? current.id : crypto.randomUUID();
    setPending({key, id});
    const sourceHashes = Object.fromEntries(items.filter(item => item.step !== 'result').map(item => [item.step,item.hash]));
    const path = '/' + id + (sourceSessionId ? '/derive/' + sourceSessionId : '');
    const created: Saved = await (await request(auth, path, {method:'PUT', headers:{'Content-Type':'application/json'},
      body:JSON.stringify(sourceSessionId ? {plan,sourceHashes} : plan)})).json();
    setSaved(previous => [created, ...previous.filter(s => s.id !== id)]); select(id);
    for (const item of items) {
      if (created.assets[item.step]?.sha256 === item.hash) continue;
      setStatus('Saving ' + item.step + '…');
      await request(auth, '/' + id + '/assets/' + item.step, {method:'PUT',
        headers:{'Content-Type':item.type, 'X-Content-SHA256':item.hash}, body:item.file});
    }
    await refresh(auth); select(id); setStatus('Saved ' + items.length + ' files.');
  }

  async function restore(session: Saved) {
    const loaded: Record<string,File> = {};
    for (const step of session.steps) {
      const asset = session.assets[step];
      if (!asset) continue;
      const blob = await (await request(auth, '/' + session.id + '/assets/' + step)).blob();
      if (blob.size !== asset.byteLength || await sha256(blob) !== asset.sha256) throw new Error('Stored image checksum mismatch.');
      loaded[step] = new File([blob], step + (asset.mediaType === 'video/mp4' ? '.mp4' : asset.mediaType === 'image/png' ? '.png' : '.jpg'), {type:asset.mediaType});
    }
    await onRestore(session.parameters, loaded, session.steps.every(step => loaded[step]) ? session.id : undefined);
    setStatus('Opened ' + Object.keys(loaded).length + (loaded.video ? ' source files.' : ' source images.'));
  }

  async function download() {
    const blob = await (await request(auth, '/' + selected + '/bundle')).blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = 'experiment-' + selected + '.fresnel-exp'; a.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <details className="capture-provider-status-details"><summary>Saved experiments</summary>
    <p>Source images and results are private to your Fresnel account and stay on the server until you delete the experiment.</p>
    {!auth ? <form onSubmit={login}><fieldset disabled={busy}>
      <label>Fresnel username <input name="username" autoComplete="username" required /></label>
      <label>Password <input name="password" type="password" autoComplete="current-password" required /></label>
      <button type="submit">Sign in to saved experiments</button>
    </fieldset></form> : <fieldset disabled={busy}>
      <button type="button" disabled={!steps.some(s => files[s])} onClick={() => void action(save)}>Save current captures</button>
      <label>Saved experiment <select value={selected} onChange={e => select(e.target.value)}>
        <option value="">Choose an experiment</option>
        {saved.map(s => <option key={s.id} value={s.id}>{new Date(s.created).toLocaleString()} · {s.state} · {s.id.slice(0,8)}</option>)}
      </select></label>
      <button type="button" disabled={!selected} onClick={() => void action(async () => {
        await restore(await (await request(auth, '/' + selected)).json());
      })}>Open saved captures</button>
      <button type="button" disabled={!selected} onClick={() => void action(download)}>Download experiment bundle</button>
      <label>Import experiment bundle <input type="file" accept=".fresnel-exp" onChange={e => {
        const file = e.target.files?.[0]; e.target.value = '';
        if (file) void action(async () => {
          const session: Saved = await (await request(auth, '/import', {method:'POST',
            headers:{'Content-Type':bundleType}, body:file})).json();
          await refresh(auth);
          if (session.pluginId !== pluginId || (acceptsSteps && !acceptsSteps(session.steps))) { setStatus('Imported. Open it in its matching ' + session.pluginId + ' capture panel.'); return; }
          select(session.id); await restore(session);
        });
      }} /></label>
      <button type="button" disabled={!selected} onClick={() => void action(async () => {
        if (!window.confirm('Delete this saved experiment and all its source and result files?')) return;
        await request(auth, '/' + selected, {method:'DELETE'}); setPending(null); select((await refresh(auth))[0]?.id ?? '');
        setStatus('Deleted the saved experiment.');
      })}>Delete saved experiment</button>
      <button type="button" onClick={() => {setAuth(''); setSaved([]); select(''); setPending(null); setStatus(''); setError('');}}>Sign out</button>
    </fieldset>}
    {status && <p role="status">{status}</p>}{error && <p role="alert">{error}</p>}
  </details>;
}
