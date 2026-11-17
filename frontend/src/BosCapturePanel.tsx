import {exposureHash, readBosOrigin,readBosSeriesOrigin, type BosAcquisition} from './measurement/bosPairedCapture';
import {useMeasurementTrack} from './measurement/MeasurementWorkflow';
import { useEffect, useMemo, useState, type FormEvent } from 'react';

const ROOT = '/api/experiments/bos-captures';

type Step = 'reference' | 'disturbed' | `frame-${string}`;
type Camera = { id: string; name: string; maximumDisturbedFrames?:number; exposureLock?:boolean; focusLock?:boolean };
type Source = { byteLength: number; sha256: string; capturedAt: string };
type Capture = { id: string; nextStep?:Step; frames?:Record<string,Source>; disturbedCount?:number; lockExposure?:boolean; lockFocus?:boolean; cameraSelection?:string; state: string; pendingStep: Step | null; reference: Source | null; disturbed: Source | null };
type PendingCreate = { id: string; cameraId: string; disturbedCount?:number; lockExposure?:boolean; lockFocus?:boolean };
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

async function verifiedPng(operator:Operator,capture:string,step:Step,source:Source) {
  const response=await request(operator,'/'+encodeURIComponent(capture)+'/images/'+step);
  const blob=await response.blob();
  if(blob.type!=='image/png'||blob.size!==source.byteLength||await exposureHash(blob)!==source.sha256) {
    throw new Error('The stored '+step+' PNG failed its media type, byte length or SHA-256 check.');
  }
  return new File([blob],'bos-'+capture+'-'+step+'.png',{type:'image/png'});
}

async function verifiedSequence(operator:Operator,current:Capture) {
  const ordered=Object.entries(current.frames ?? {}).sort(([a],[b]) => a.localeCompare(b));
  if (ordered.length !== current.disturbedCount || !ordered.every(([key],i) => key === 'frame-'+String(i+1).padStart(2,'0')))
    throw new Error('The completed capture has an incomplete frame sequence.');
  const origin = readBosSeriesOrigin({
    provider: 'photographer-rest',
    captureId: current.id,
    cameraSelection: current.cameraSelection,
    reference: current.reference,
    frames: ordered.map(([,source]) => source),
    lockExposure: current.lockExposure,
    lockFocus: current.lockFocus,
  });
  if (!origin) throw new Error('The completed capture has incomplete source metadata.');
  if (origin.frames.reduce((sum,frame) => sum+frame.byteLength, origin.reference.byteLength) > 128*1024*1024)
    throw new Error('The sequence exceeds 128 MiB.');
  const reference = await verifiedPng(operator,current.id,'reference',origin.reference);
  const frames:File[] = [];
  for (const [step,source] of ordered) frames.push(await verifiedPng(operator,current.id,step as Step,source));
  return {origin,reference,frames};
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
    void verifiedPng(operator,capture,step,source).then(blob=>{
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

function Acquisition({onUsePair}:{onUsePair:(pair:BosAcquisition)=>void}) {
  const [operator, setOperator] = useState<Operator | null>(null);
  const [cameras, setCameras] = useState<Camera[]>([]);
  const [cameraId, setCameraId] = useState('');
  const [count,setCount]=useState(1),[lockExposure,setLockExposure]=useState(false),[lockFocus,setLockFocus]=useState(false);
  const selected=cameras.find(c=>c.id===cameraId);
  const maxFrames=selected?.maximumDisturbedFrames??1;
  const countValid=Number.isInteger(count)&&count>=1&&count<=maxFrames;
  const [list, setList] = useState<Capture[]>([]);
  const [current, setCurrent] = useState<Capture | null>(null);
  const [pending, setPending] = useState<PendingCreate | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');

  const activateCamera = useMeasurementTrack('bos/camera',useMemo(()=>({
    artifacts: Object.entries({reference:current?.reference, disturbed:current?.disturbed, ...current?.frames})
      .flatMap(([role,asset]) => asset ? [{role,byteLength:asset.byteLength,sha256:asset.sha256}] : []),
    planned:1 + (current?.disturbedCount ?? (countValid ? count : 1)), target:null, ready:current?.state==='COMPLETED', captureReady:!!current && ['WAITING_REFERENCE','WAITING_DISTURBED'].includes(current.state),
    calibrating:current?.state==='CREATING', capturing:!!current?.pendingStep || !!current && busy,
    cancelled:current?.state==='CANCELLED', error, session:current?.id,
    camera:cameras.find(c=>c.id===cameraId)?.name,
  }),[current,busy,error,cameras,cameraId,count,countValid]));
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
      setCount(saved?.disturbedCount??1);setLockExposure(saved?.lockExposure??false);setLockFocus(saved?.lockFocus??false);
    });
  }

  async function create() {
    if (!operator) return;
    await action(async () => {
      const next = pending ?? { id:crypto.randomUUID(),cameraId,...(count>1?{disturbedCount:count}:{}),
        ...(lockExposure?{lockExposure:true}:{}),...(lockFocus?{lockFocus:true}:{}) };
      setPending(next);
      remember(operator.username, next);
      const {id,...plan}=next;
      const capture: Capture = await (await request(operator, '/' + id, 'PUT', plan)).json();
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

  const step = current?.nextStep ?? (current?.state === 'WAITING_REFERENCE' ? 'reference' : current?.state === 'WAITING_DISTURBED' ? 'disturbed' : null);
  return <div className="photographer-pairing-form" onFocusCapture={activateCamera}>
    {!operator ? <form className="photographer-pairing-form" onSubmit={signIn}>
      <label>Fresnel username <input name="username" autoComplete="username" required /></label>
      <label>Password <input name="password" type="password" autoComplete="current-password" required /></label>
      <button type="submit" disabled={busy}>Sign in</button>
    </form> : <>
      <p>Signed in as {operator.username}. <button type="button" disabled={busy} onClick={() => {
        setOperator(null); setList([]); setCurrent(null); setCameras([]); setCameraId(''); setPending(null); setError('');
      }}>Sign out</button></p>
      <label>Camera <select value={pending?.cameraId ?? cameraId} disabled={busy || !!pending}
        onChange={event => {setCameraId(event.target.value);setCount(1);setLockExposure(false);setLockFocus(false);}}>
        {!cameras.length && <option value="">No camera available</option>}
        {cameras.map(camera => <option key={camera.id} value={camera.id}>{camera.name}</option>)}
      </select></label>
      {selected && <fieldset disabled={busy||!!pending}><legend>Still capture plan</legend>
        {maxFrames>1 && <label>Disturbed frames <input type="number" min="1" max={maxFrames} step="1" value={Number.isNaN(count)?'':count}
          onChange={event=>setCount(event.target.valueAsNumber)}/></label>}
        {selected.exposureLock&&<label><input type="checkbox" checked={lockExposure} onChange={e=>setLockExposure(e.target.checked)}/>Lock exposure</label>}
        {selected.focusLock&&<label><input type="checkbox" checked={lockFocus} onChange={e=>setLockFocus(e.target.checked)}/>Lock focus</label>}
        <p>PNG stills · one reference and {countValid?count:'…'} disturbed exposures · operator-triggered.
          This provider does not publish pixel dimensions in advance. Each exposure records its capture time.</p>
      </fieldset>}
      <button type="button" disabled={busy || (!pending && (!cameraId || !countValid))} onClick={() => void create()}>
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
              : step === 'reference' ? 'The undisturbed reference condition is ready.' : step === 'disturbed' ? 'The disturbed condition is ready.' : 'The disturbed condition for '+step+' is ready.'}
          </label>
          <button type="button" disabled={busy || !ready} onClick={() => void advance(step)}>Capture {step}</button>
        </>}
        <div>
          <button type="button" disabled={busy} onClick={() => void open(current.id)}>Refresh capture</button>
          {['CREATING', 'WAITING_REFERENCE', 'WAITING_DISTURBED'].includes(current.state) &&
            <button type="button" disabled={busy} onClick={() => void advance()}>Cancel capture</button>}
        </div>
        {current.state==='COMPLETED'&&current.reference&&current.disturbed&&<button type="button" disabled={busy}
          onClick={()=>void action(async()=>{
            const origin=readBosOrigin({provider:'photographer-rest',captureId:current.id,cameraSelection:current.cameraSelection,lockExposure:current.lockExposure,lockFocus:current.lockFocus,reference:current.reference,disturbed:current.disturbed});
            if(!origin)throw new Error('The completed capture has incomplete source metadata.');
            if([origin.reference,origin.disturbed].some(s=>s.byteLength>20*1024*1024))throw new Error('Still analysis accepts up to 20 MiB per original.');
            const [reference,disturbed]=await Promise.all([
              verifiedPng(operator,current.id,'reference',origin.reference),verifiedPng(operator,current.id,'disturbed',origin.disturbed),
            ]);
            onUsePair({origin,files:{reference,disturbed}});
          })}>Use verified pair in BOS analysis</button>}
        {current.state === 'COMPLETED' && current.reference && (current.disturbedCount ?? 1) > 1 && (
          <button type="button" disabled={busy} onClick={() => void action(async () => {
            onUsePair(await verifiedSequence(operator, current));
          })}>Use verified sequence in BOS analysis</button>
        )}
        {current.frames && Object.keys(current.frames).length>0 && <p>{Object.keys(current.frames).length} of {current.disturbedCount} disturbed frames captured.</p>}
        {(['reference', 'disturbed'] as const).map(role => current[role] &&
          <SourceImage key={current.id + role} operator={operator} capture={current.id} step={role} source={current[role]} />)}
      </>}
    </>}
    {error && <p role="alert">{error}</p>}
  </div>;
}

export function BosCapturePanel({onUsePair}:{onUsePair:(pair:BosAcquisition)=>void}) {
  const [open, setOpen] = useState(false);
  return <details id="bos-camera-acquisition" tabIndex={-1} className="capture-provider-status-details" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>Background-oriented schlieren capture</summary>
    {open && <Acquisition onUsePair={onUsePair} />}
  </details>;
}
