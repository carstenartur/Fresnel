import {useEffect,useRef,useState,type PointerEvent} from 'react';
import {type Geometry,type Manifest} from './data';
import {fullRegion,profileValues,type Preflight,type Region} from './setup';

type Profile=ReturnType<typeof profileValues>;
const profilesKey='fresnel.displayProfiles.v1';
function savedProfiles():Record<string,Profile> {
  try {
    const value:unknown=JSON.parse(localStorage.getItem(profilesKey)??'{}');
    if(!value||typeof value!=='object'||Array.isArray(value))return {};
    return Object.fromEntries(Object.entries(value).filter(([k,p])=>k.length<=48 && p &&
      ['screenWidthMm','screenHeightMm','widthPx','heightPx'].every(key=>Number.isFinite(p[key]) && p[key]>0)).slice(0,24));
  } catch {return {};}
}
export function PhysicalSetup({geometry,onChange,disabled}:{geometry:Geometry;onChange:(g:Geometry)=>void;disabled:boolean}) {
  const [profiles,setProfiles]=useState(savedProfiles),[name,setName]=useState(''),[message,setMessage]=useState('');
  function storeProfile() {
    const key=name.trim();
    if(!key)return;
    if(!Object.prototype.hasOwnProperty.call(profiles,key)&&Object.keys(profiles).length>=24){setMessage('The display profile limit is 24.');return;}
    const next={...profiles,[key]:profileValues(geometry)};
    try {localStorage.setItem(profilesKey,JSON.stringify(next));setProfiles(next);setMessage('Saved '+key+' on this browser.');}
    catch {setMessage('Browser storage is unavailable. The resolved display geometry can still be saved as a job.');}
  }
  function preset(which:string) {
    const halfW=geometry.screenWidthMm/2+10,halfH=geometry.screenHeightMm/2+10;
    const offsets:Record<string,[number,number]>={'above':[0,halfH],'below':[0,-halfH],'right':[halfW,0],'left':[-halfW,0]};
    const p=offsets[which];if(p)onChange({...geometry,cameraOffsetXmm:p[0],cameraOffsetYmm:p[1]});
  }
  return <details><summary>Physical setup and saved screens</summary>
    <svg viewBox="0 0 360 130" width="100%" role="img" aria-label="Side view of a camera above a monitor, both facing a stationary object">
      <rect x="53" y="30" width="9" height="68" fill="#45697b"/><path d="M58 98v10m-19 0h34" stroke="#45697b" strokeWidth="3"/>
      <rect x="45" y="16" width="21" height="10" rx="2" fill="#315366"/>
      <path d="M67 21l212 44M67 85l212-20" fill="none" stroke="#bc8535" strokeDasharray="5 4"/>
      <circle cx="281" cy="65" r="11" fill="#ae7851"/><path d="M266 79h32" stroke="#526675" strokeWidth="2"/>
      <g fontSize="12" fill="currentColor"><text x="76" y="17">Camera</text><text x="8" y="62">Screen</text><text x="301" y="69">Object</text>
      <text x="123" y="117">Screen-to-object distance</text></g>
      <path d="M63 101h217m-217-4v8m217-8v8" fill="none" stroke="#718590"/>
    </svg>
    <fieldset disabled={disabled}>
      <label>Camera placement <select aria-label="Camera placement" value="" onChange={e=>preset(e.target.value)}>
        <option value="">Select a placement preset</option><option value="above">Above screen center</option><option value="below">Below screen center</option>
        <option value="left">Left of screen center</option><option value="right">Right of screen center</option></select></label>
      <label>Saved display profile <select aria-label="Saved display profile" value="" onChange={e=>{
        if(e.target.value){onChange({...geometry,...profiles[e.target.value]});setMessage('Applied '+e.target.value+'.');}
      }}><option value="">Choose a saved screen</option>{Object.keys(profiles).map(k=><option key={k}>{k}</option>)}</select></label>
      <label>Screen profile name <input maxLength={48} value={name} onChange={e=>setName(e.target.value)}/></label>
      <button type="button" disabled={!name.trim()} onClick={storeProfile}>Save display profile</button>
    </fieldset>
    {message && <p role="status">{message}</p>}
  </details>;
}

export function SetupAssistant({manifest,files,roi,onChange,onSelect,disabled}:{
  manifest:Manifest;files:Record<string,File>;roi:Region;onChange:(r:Region)=>void;onSelect:(id:string)=>void;disabled:boolean;
}) {
  const [evidence,setEvidence]=useState<Preflight|null>(null),[url,setUrl]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [draft,setDraft]=useState<Region|null>(null);
  const worker=useRef<Worker|null>(null),preview=useRef(''),start=useRef<[number,number]|null>(null);
  const opposite=manifest.patterns[2+manifest.geometry.lightCount/2].id;
  useEffect(()=>{
    worker.current?.terminate();setBusy(false);setEvidence(null);setError('');
    return ()=>{worker.current?.terminate();};
  },[files,roi,manifest]);
  useEffect(()=>{
    if (preview.current) URL.revokeObjectURL(preview.current);
    preview.current='';setUrl('');
    return ()=>{if(preview.current) URL.revokeObjectURL(preview.current);};
  },[files.flat,manifest]);
  function inspect(){
    setError('');setBusy(true);
    const task=new Worker(new URL('./preflight.worker.ts',import.meta.url),{type:'module'});worker.current=task;
    const stop=()=>{task.terminate();worker.current=null;setBusy(false);};
    task.onerror=e=>{setError(e.message);stop();};
    task.onmessage=e=>{
      if(e.data.error)setError(e.data.error);
      else {setEvidence(e.data.evidence);if(preview.current)URL.revokeObjectURL(preview.current);
        preview.current=URL.createObjectURL(e.data.preview);setUrl(preview.current);}
      stop();
    };
    task.postMessage({files:{flat:files.flat,dark:files.dark,'light-01':files['light-01'],[opposite]:files[opposite]},
      gamma:manifest.geometry.cameraGamma,opposite,roi});
  }
  function point(e:PointerEvent<HTMLDivElement>):[number,number] {
    const box=e.currentTarget.getBoundingClientRect(),clamp=(n:number)=>Math.max(0,Math.min(1,n));
    return [clamp((e.clientX-box.left)/box.width),clamp((e.clientY-box.top)/box.height)];
  }
  const shown=draft??roi;
  return <details><summary>Test exposures and object region</summary>
    <p>Map a flat exposure to inspect framing and focus. Add the dark frame and two opposite patches to measure ambient light and directional response before continuing.</p>
    <div>{['flat','dark','light-01',opposite].map(id=><button type="button" key={id} disabled={disabled} onClick={()=>onSelect(id)}>Select {id}</button>)}</div>
    <button type="button" disabled={disabled||busy||!files.flat} onClick={inspect}>Inspect test exposures</button>
    {url && <div style={{position:'relative',touchAction:'none',cursor:'crosshair'}} onPointerDown={e=>{
      if(disabled)return;start.current=point(e);e.currentTarget.setPointerCapture(e.pointerId);
    }} onPointerMove={e=>{
      if(!start.current)return;const [x,y]=point(e),[sx,sy]=start.current;
      setDraft({left:Math.min(x,sx),top:Math.min(y,sy),right:Math.max(x,sx),bottom:Math.max(y,sy)});
    }} onPointerUp={e=>{
      if(start.current){const [x,y]=point(e),[sx,sy]=start.current;
        if(Math.abs(x-sx)>.005&&Math.abs(y-sy)>.005)onChange({left:Math.min(x,sx),top:Math.min(y,sy),right:Math.max(x,sx),bottom:Math.max(y,sy)});}
      start.current=null;setDraft(null);
    }} onPointerCancel={()=>{start.current=null;setDraft(null);}}>
      <img src={url} alt="Upright flat camera exposure, drag to select the object" draggable={false} style={{display:'block',width:'100%'}}/>
      <div style={{position:'absolute',pointerEvents:'none',boxSizing:'border-box',border:'2px solid #41dfbd',
        left:shown.left*100+'%',top:shown.top*100+'%',width:(shown.right-shown.left)*100+'%',height:(shown.bottom-shown.top)*100+'%'}}/>
    </div>}
    <fieldset disabled={disabled}><legend>Object region (% of upright image)</legend>
      {(['left','top','right','bottom'] as const).map(k=><label key={k}>Region {k} <input type="number" min={0} max={100} step={1}
        value={Number((roi[k]*100).toFixed(1))} onChange={e=>{const r={...roi,[k]:+e.target.value/100};
          if(r.left>=0&&r.top>=0&&r.right<=1&&r.bottom<=1&&r.left<r.right&&r.top<r.bottom)onChange(r);}}/></label>)}
      <button type="button" onClick={()=>onChange(fullRegion)}>Use whole image</button>
    </fieldset>
    {error&&<p role="alert">{error}</p>}
    {evidence&&<div role="status"><p>{evidence.pixels} object pixels. Clipped flat pixels {(100*evidence.flatClip).toFixed(1)}%.
      {evidence.darkFraction!==null&&' Ambient/flat '+(100*evidence.darkFraction).toFixed(1)+'%.'}
      {evidence.oppositeResponse!==null&&' Opposite-patch difference '+evidence.oppositeResponse.toFixed(3)+' (linear luminance).'}</p>
      {evidence.messages.length>0&&<ul>{evidence.messages.map(m=><li key={m}>{m}</li>)}</ul>}</div>}
  </details>;
}
