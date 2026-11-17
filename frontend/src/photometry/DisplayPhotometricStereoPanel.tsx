import { useEffect, useMemo, useRef, useState } from 'react';
import { initialJobParameters, SaveJobControl, type JobPanelProps } from '../jobs/JobFileControls';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { defaults, download, generator, pluginId, type Geometry, type Manifest, type Result } from './data';
import { displayFrame } from './stage';
import { PhotometryCamera } from './PhotometryCamera';
import { PhysicalSetup,SetupAssistant } from './SetupAssistant';
import { fullRegion,regionFrom,profileIdentity,type Region } from './setup';
import { PatternPresenter } from './PatternPresenter';
import { MeasurementWorkflow, useMeasurementTrack, type WorkflowSource } from '../measurement/MeasurementWorkflow';
import { MeasurementArchive } from '../measurement/MeasurementArchive';
import { archiveResult } from './archive';
import type { Relief } from './height';
import { RelativeRelief } from './RelativeRelief';
import { Relighting } from './Relighting';

const captureSources: WorkflowSource[] = [
  {key:'photometry/manual', label:'Mapped camera files', captureId:'photometry-manual', analysisId:'photometry-analyze', archiveId:'photometry-archive', planned:0, requiresTarget:true},
  {key:'photometry/camera', label:'Synchronized Photographer', captureId:'photometry-camera', analysisId:'photometry-camera', archiveId:'photometry-archive', planned:0, requiresTarget:true, requiresSession:true},
];

export function DisplayPhotometricStereoPanel({ initialJob }: JobPanelProps) {
  const [geometry, setGeometry] = useState(() => initialJobParameters(initialJob,pluginId,defaults));
  const [roi,setRoi]=useState<Region>(fullRegion);
  const [profile,setProfile]=useState<Awaited<ReturnType<typeof profileIdentity>>|null>(null);
  const [sourceSessionId,setSourceSessionId]=useState<string>();
  const [manifest, setManifest] = useState<Manifest | null>(null);
  const [files, setFiles] = useState<Record<string,File>>({});
  const [selected, select] = useState('dark');
  const [result, setResult] = useState<Result | null>(null);
  const [relief,setRelief] = useState<Relief|null>(null);
  const archived = useMemo(()=>manifest && result ? archiveResult(manifest,result,relief) : null,[manifest,result,relief]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<'screen' | 'sequence' | 'solve' | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const activateFiles = useMeasurementTrack('photometry/manual', useMemo(() => ({
    artifacts: Object.entries(files).map(([role,file]) => ({role, file, sha256:result?.sources.find(s=>s.patternId===role)?.sha256})),
    planned: manifest?.patterns.length ?? geometry.lightCount + 2,
    ready: Boolean(manifest?.patterns.every(p=>files[p.id])),
    calibrating: busy === 'screen', analyzing: busy === 'solve', completed: !!result, error, cancelled,
    session: sourceSessionId,
  }), [files, result, manifest, geometry.lightCount, busy, error, cancelled, sourceSessionId]));
  const screenProbe=useRef<HTMLCanvasElement>(null);
  const worker = useRef<Worker | null>(null);
  const generation = useRef(0);
  useEffect(() => () => { generation.current++; worker.current?.terminate(); }, []);
  function change(next: Geometry) {
    setCancelled(false);
    generation.current++;
    worker.current?.terminate();
    setSourceSessionId(undefined); setGeometry(next); setRoi(fullRegion); setProfile(null); setManifest(null); setFiles({}); setResult(null); setRelief(null); setError(''); setBusy(null);
  }
  async function measureScreen() {
    const target=screenProbe.current;
    if(!target)return;
    setBusy('screen');setError('');
    try {
      await target.requestFullscreen({navigationUI:'hide'});
      await displayFrame();await displayFrame();
      if(document.fullscreenElement!==target || document.hidden) throw new Error('Fullscreen measurement was interrupted.');
      const box=target.getBoundingClientRect();
      const widthPx=Math.round(box.width*devicePixelRatio),heightPx=Math.round(box.height*devicePixelRatio);
      if(!widthPx || !heightPx) throw new Error('Could not measure the native full-screen raster.');
      await document.exitFullscreen();
      change({...geometry,widthPx,heightPx});
    } catch(e){if(document.fullscreenElement===target)await document.exitFullscreen().catch(()=>undefined);setError(String(e));setBusy(null);}
  }
  async function prepare(settings: Geometry, restored?: Record<string,File>, region:Region=fullRegion, sourceId?:string) {
    const ticket = ++generation.current;
    setCancelled(false); setError(''); setBusy('sequence');
    try {
      const generated: Manifest = await (await generator('sequence',settings)).json();
      const screenProfile=await profileIdentity(generated.geometry);
      if (generation.current !== ticket) return;
      if (restored && Object.keys(restored).some(id => !generated.patterns.some(p=>p.id===id)))
        throw new Error('Saved source mapping differs from the regenerated illumination plan.');
      if (restored) activateFiles();
      setSourceSessionId(sourceId); setRoi(region); setProfile(screenProfile); setGeometry(generated.geometry); setManifest(generated); setFiles(restored ?? {}); select('dark'); setResult(null); setRelief(null);
    } catch (e) { if (ticket === generation.current) setError(String(e)); if (restored) throw e; }
    finally { if (ticket === generation.current) setBusy(null); }
  }
  function stop() { worker.current?.terminate(); worker.current = null; setBusy(null); }
  function analyze() {
    if (!manifest) return;
    activateFiles(); setCancelled(false); setError(''); setBusy('solve'); setResult(null); setRelief(null);
    const task = new Worker(new URL('./analyze.worker.ts',import.meta.url), { type:'module' });
    worker.current = task;
    task.onerror = e => { setError(e.message || 'Analysis failed.'); stop(); };
    task.onmessage = e => {
      if (e.data.error) setError(e.data.error); else setResult(e.data.result);
      stop();
    };
    task.postMessage({ manifest, files, roi });
  }
  const pattern = manifest?.patterns.find(p => p.id === selected);
  const complete = manifest?.patterns.every(p => !!files[p.id]);
  return <>
    <h2>Display photometric stereo</h2>
    <p>Face a small matte object toward the screen. Mount an upright camera beside the display, aim at the object center, and lock focus, exposure and white balance. Keep the scene still throughout the sequence.</p>
    <canvas ref={screenProbe} className="photometry-illumination" aria-label="Measuring fullscreen raster"/>
    <PhysicalSetup geometry={geometry} onChange={change} disabled={!!busy}/>
    <PluginEditorShell pluginId={pluginId} value={geometry} onChange={change} disabled={!!busy} domainValidationEnabled={false}
      header={schema=>schema.uiSchema.extensions?.includes('measurement-workflow') &&
        <MeasurementWorkflow sources={captureSources} target={manifest?.sequenceId} targetId="parameters-display-photometric-stereo" targetError={!manifest && error || undefined}/>}>
      {(_, validation) => <>
        <button disabled={!!busy} onClick={() => void measureScreen()}>Use this display’s pixel dimensions</button>
        <button disabled={!!busy || !validation?.valid || !!manifest} onClick={() => void prepare(geometry)}>Prepare illumination sequence</button>
        <SaveJobControl pluginId={pluginId} parameters={geometry} disabled={!!busy || !validation?.valid}/>
      </>}
    </PluginEditorShell>
    <MeasurementArchive anchorId="photometry-archive" sourceSessionId={sourceSessionId} pluginId={pluginId} parameters={{geometry,roi,screenProfile:profile}} steps={manifest?.patterns.map(p=>p.id) ?? []}
      files={files} result={archived} onRestore={async (parameters,images,sourceId?:string)=>{
        const restored=(parameters.geometry ?? parameters) as Geometry;
        stop(); await prepare(restored,images,regionFrom(parameters.roi),sourceId);
      }}/>
    {manifest && <>
      <PhotometryCamera key={manifest.sequenceId} manifest={manifest} onRestore={(g,images,sourceId)=>prepare(g,images,fullRegion,sourceId)}/>
      <section id="photometry-manual" tabIndex={-1} onFocusCapture={activateFiles}><h3>Manual capture</h3>
      <SetupAssistant manifest={manifest} files={files} roi={roi} disabled={!!busy} onSelect={select}
        onChange={next=>{setRoi(next);setResult(null);setRelief(null);}}/>
      <p>Light matrix condition number {manifest.conditionNumber.toFixed(2)}. Show each pattern, wait at least {manifest.geometry.settleDelayMs} ms, and take one exposure. Press Esc to return and map the camera file to that pattern.</p>
      <button onClick={() => download(new Blob([JSON.stringify(manifest,null,2)],{type:'application/json'}),'illumination-manifest.json')}>Download sequence manifest</button>
      <label>Pattern <select aria-label="Pattern" disabled={!!busy} value={selected} onChange={e => select(e.target.value)}>
        {manifest.patterns.map(p => <option key={p.id} value={p.id}>{p.id}{files[p.id] ? ' · mapped' : ''}</option>)}
      </select></label>
      {pattern && <PatternPresenter key={manifest.sequenceId + ':' + selected} manifest={manifest} pattern={pattern}/>}
      <label>Camera exposure for {selected} <input key={manifest.sequenceId + selected} type="file" accept="image/png,image/jpeg"
        disabled={!!busy} onChange={e => {
          const f = e.target.files?.[0]; if (!f) return;
          if (f.size > 16*1024*1024) { setError('Choose a file of at most 16 MiB.'); return; }
          setSourceSessionId(undefined); setCancelled(false); activateFiles(); setFiles({ ...files,[selected]:f }); setResult(null); setRelief(null); setError('');
        }}/></label>
      {files[selected] && <p>{files[selected].name}</p>}
      <p role="status">{Object.keys(files).length} / {manifest.patterns.length} exposures mapped</p>
      <button id="photometry-analyze" onClick={analyze} disabled={!!busy || !complete}>Reconstruct surface</button>
      {busy === 'solve' && <button onClick={()=>{stop(); setCancelled(true);}}>Cancel reconstruction</button>}
      {result && <>
        <Relighting result={result} manifest={manifest} relief={relief} files={files}/>
        <RelativeRelief result={result} relief={relief} onChange={setRelief}/>
      </>}
    </section></>}
    {error && <p role="alert">{error}</p>}
  </>;
}
