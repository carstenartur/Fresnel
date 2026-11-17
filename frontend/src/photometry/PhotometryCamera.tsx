import {useEffect,useRef,useState,type FormEvent} from 'react';
import {captureSequence,type CameraSession} from './cameraSequence';
import {displayFrame,nativeSize,stage} from './stage';
import {pluginId,type Geometry,type Manifest} from './data';

const root='/api/experiments/photometry-captures';
type Camera={id:string;name:string};
type Creation={id:string;cameraId:string;manualLocksConfirmed:boolean;sequenceId:string};
class CaptureRequestError extends Error {
  readonly status:number;
  constructor(status:number,message:string) { super(message); this.status=status; }
}
async function request<T>(auth:string,url:string,method='GET',body?:object,keepalive=false):Promise<T> {
  const headers:Record<string,string>={Authorization:auth};
  if (body) headers['Content-Type']='application/json';
  const r=await fetch(url,{method,headers,body:body ? JSON.stringify(body) : undefined,
    keepalive,cache:'no-store',redirect:'error',credentials:'omit'});
  if (!r.ok) {
    if (r.status===404) throw new CaptureRequestError(r.status,'Photographer capture is unavailable. Check pairing and enable the integration.');
    if (r.status===401 || r.status===403) throw new CaptureRequestError(r.status,'Fresnel denied access. Check the operator credentials.');
    let detail=''; try {const problem=await r.json();detail=problem.code ?? problem.detail ?? '';} catch { /* empty response */ }
    throw new CaptureRequestError(r.status,'Fresnel capture request failed (HTTP '+r.status+'). '+detail);
  }
  return r.json();
}
export function PhotometryCamera({manifest,onRestore}:{manifest:Manifest;onRestore:(g:Geometry,f:Record<string,File>)=>Promise<void>}) {
  const [auth,setAuth]=useState(''),[username,setUsername]=useState('');
  const [cameras,setCameras]=useState<Camera[]>([]),[cameraId,setCameraId]=useState(''),[locks,setLocks]=useState(false);
  const [sessions,setSessions]=useState<CameraSession[]>([]),[current,setCurrent]=useState<CameraSession|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const canvas=useRef<HTMLCanvasElement>(null), cancelActive=useRef<null|(()=>void)>(null);
  const creating=useRef<Creation|null>(null);
  useEffect(()=>()=>cancelActive.current?.(),[]);
  function selectSession(s:CameraSession) {
    setCurrent(s); setSessions(prev=>[s,...prev.filter(x=>x.id!==s.id)]);
  }
  async function action(work:()=>Promise<void>) {
    setBusy(true); setError('');
    try {await work();} catch(e) {setError(e instanceof Error ? e.message : String(e));}
    finally {setBusy(false);}
  }
  async function signIn(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form=e.currentTarget,data=new FormData(form),name=String(data.get('username'));
    const bytes=new TextEncoder().encode(name+':'+String(data.get('password')));
    const header='Basic '+btoa(Array.from(bytes,v=>String.fromCharCode(v)).join(''));
    await action(async()=>{
      const [history,available]=await Promise.all([request<CameraSession[]>(header,root),request<Camera[]>(header,root+'/cameras')]);
      setAuth(header); setUsername(name); form.reset();
      const matches=history.filter(s=>s.sequenceId===manifest.sequenceId);
      setSessions(matches);setCurrent(matches[0]??null);setCameras(available);setCameraId(available[0]?.id??'');
      try {creating.current=JSON.parse(sessionStorage.getItem('fresnel.photo.create.'+name)??'null');} catch {creating.current=null;}
    });
  }
  async function create() {
    let plan=creating.current;
    if (!plan || plan.sequenceId!==manifest.sequenceId) plan={id:crypto.randomUUID(),cameraId,manualLocksConfirmed:locks,sequenceId:manifest.sequenceId};
    creating.current=plan;
    try {sessionStorage.setItem('fresnel.photo.create.'+username,JSON.stringify(plan));} catch { /* memory fallback */ }
    let next:CameraSession;
    try {
      next=await request<CameraSession>(auth,root+'/'+plan.id,'PUT',{cameraId:plan.cameraId,
        geometry:manifest.geometry,manualLocksConfirmed:plan.manualLocksConfirmed});
    } catch (error) {
      // A capability rejection before persistence permits the operator to change settings.
      // An ambiguous network failure keeps the same creation UUID for a safe retry.
      if (error instanceof CaptureRequestError && [400,409].includes(error.status)) {
        try { selectSession(await request<CameraSession>(auth,root+'/'+plan.id)); }
        catch (lookup) {
          if (lookup instanceof CaptureRequestError && lookup.status===404) {
            creating.current=null;
            try {sessionStorage.removeItem('fresnel.photo.create.'+username);} catch { /* memory fallback */ }
          }
        }
      }
      throw error;
    }
    selectSession(next);
    if (next.state!=='CREATING') {
      creating.current=null;
      try {sessionStorage.removeItem('fresnel.photo.create.'+username);} catch { /* memory fallback */ }
    }
  }
  async function run() {
    const target=canvas.current, session=current;
    if (!target || !session || cancelActive.current) return;
    let lost=false, engaged=false, done=false;
    let cancellation:Promise<void>|undefined;
    const continuous=()=>!lost && !document.hidden && document.fullscreenElement===target && nativeSize(target);
    const cancel=()=>{
      if (done) return;
      lost=true;
      cancellation??=request<CameraSession>(auth,root+'/'+session.id,'DELETE',undefined,true)
        .then(selectSession).catch(()=>setError('Cancellation could not be confirmed. Refresh and retry cancellation before using this camera.'));
      if (document.fullscreenElement===target) void document.exitFullscreen().catch(()=>undefined);
    };
    const check=()=>{if (engaged && !continuous()) cancel();};
    const leave=()=>cancel();
    document.addEventListener('visibilitychange',check);
    document.addEventListener('fullscreenchange',check);
    window.addEventListener('resize',check);window.addEventListener('pagehide',leave);
    cancelActive.current=cancel;
    try {
      await target.requestFullscreen({navigationUI:'hide'});
      await displayFrame();engaged=true;
      if (!continuous()) throw new Error('Use the native fullscreen raster from the illumination manifest.');
      await captureSequence(manifest,session,{
        continuous,
        stageAndSettle:async p=>{
          await stage(target,manifest,p,continuous);
          await displayFrame(); await displayFrame();
          await new Promise(done=>setTimeout(done,manifest.geometry.settleDelayMs));
        },
        advance:(step,phase,ack)=>request<CameraSession>(auth,root+'/'+session.id+'/steps/'+step+'/'+phase,'POST',ack),
        updated:selectSession,
      });
      done=true;
    } catch(e) {cancel();await cancellation;throw e;}
    finally {
      document.removeEventListener('visibilitychange',check); document.removeEventListener('fullscreenchange',check);
      window.removeEventListener('resize',check); window.removeEventListener('pagehide',leave);
      if (document.fullscreenElement===target) await document.exitFullscreen().catch(()=>undefined);
      cancelActive.current=null;
    }
  }
  async function openCaptured() {
    if (!current) return;
    const path='/api/measurement-sessions/'+current.archiveId;
    const saved=await request<{pluginId:string;parameters:Geometry;assets:Record<string,{sha256:string;byteLength:number;mediaType:string}>}>(auth,path);
    if(saved.pluginId!==pluginId) throw new Error('Unexpected archived measurement.');
    const files:Record<string,File>={};
    for(const id of current.acceptedSteps) {
      const a=saved.assets[id];
      if (!a) throw new Error('Missing archived exposure '+id);
      const r=await fetch(path+'/assets/'+id,{headers:{Authorization:auth},credentials:'omit',redirect:'error',cache:'no-store'});
      if (!r.ok) throw new Error('Could not read archived exposure '+id);
      const blob=await r.blob();
      const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer())),v=>v.toString(16).padStart(2,'0')).join('');
      if (a.byteLength!==blob.size || hash!==a.sha256) throw new Error('Invalid archived exposure checksum for '+id);
      files[id]=new File([blob],id+'.png',{type:a.mediaType});
    }
    await onRestore(saved.parameters,files);
  }
  return <details><summary>Synchronized Photographer capture</summary>
    {!auth ? <form onSubmit={signIn}><fieldset disabled={busy}>
      <label>Operator username <input name="username" autoComplete="username" required/></label>
      <label>Operator password <input name="password" autoComplete="current-password" type="password" required/></label>
      <button>Connect camera provider</button></fieldset></form> : <fieldset disabled={busy}>
      <label>Photographer camera <select value={cameraId} onChange={e=>setCameraId(e.target.value)}>
        {cameras.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
      <label><input type="checkbox" checked={locks} onChange={e=>setLocks(e.target.checked)}/> I have manually locked exposure and focus if hardware locks are unavailable.</label>
      <button disabled={!cameraId} onClick={()=>void action(create)}>Prepare camera capture</button>
      {!!sessions.length && <label>Camera session <select value={current?.id??''} onChange={e=>setCurrent(sessions.find(s=>s.id===e.target.value)??null)}>
        {sessions.map(s=><option key={s.id} value={s.id}>{s.state} · {s.id.slice(0,8)}</option>)}</select></label>}
      {current && <>
        <p role="status">{current.state} · {current.acceptedSteps.length} / {manifest.patterns.length} exposures accepted{current.nextStep && ' · Next '+current.nextStep}</p>
        {current.state==='READY' && <button onClick={()=>void action(run)}>Run camera sequence full screen</button>}
        {current.pendingStep && <p>Presentation was interrupted. Cancel this capture before starting a new sequence.</p>}
        {!['COMPLETED','CANCELLED'].includes(current.state) && <button onClick={()=>void action(async()=>selectSession(await request(auth,root+'/'+current.id,'DELETE')))}>Cancel camera capture</button>}
        <button onClick={()=>void action(async()=>selectSession(await request(auth,root+'/'+current.id)))}>Refresh camera capture</button>
        {current.acceptedSteps.length>0 && <button onClick={()=>void action(openCaptured)}>Open captured exposures</button>}
      </>}
      <button onClick={()=>{setAuth('');setCurrent(null);setSessions([]);setError('');}}>Disconnect operator</button>
    </fieldset>}
    <canvas ref={canvas} className="photometry-illumination" aria-label="Synchronized illumination" width={manifest.geometry.widthPx} height={manifest.geometry.heightPx}/>
    {error && <p role="alert">{error}</p>}
  </details>;
}
