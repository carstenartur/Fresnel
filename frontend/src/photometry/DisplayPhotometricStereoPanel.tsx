import { useEffect, useMemo, useRef, useState } from 'react';
import { initialJobParameters, SaveJobControl, type JobPanelProps } from '../jobs/JobFileControls';
import { PluginEditorShell } from '../schema/PluginEditorShell';
import { defaults, download, generator, pluginId, type Geometry, type Manifest, type Result } from './data';
import { displayFrame } from './stage';
import { PhotometryCamera } from './PhotometryCamera';
import { PatternPresenter } from './PatternPresenter';
import { MeasurementArchive } from '../measurement/MeasurementArchive';
import { archiveResult } from './archive';
import type { Relief } from './height';
import { RelativeRelief } from './RelativeRelief';
import { Relighting } from './Relighting';

export function DisplayPhotometricStereoPanel({ initialJob }: JobPanelProps) {
  const [geometry, setGeometry] = useState(() => initialJobParameters(initialJob,pluginId,defaults));
  const [manifest, setManifest] = useState<Manifest | null>(null);
  const [files, setFiles] = useState<Record<string,File>>({});
  const [selected, select] = useState('dark');
  const [result, setResult] = useState<Result | null>(null);
  const [relief,setRelief] = useState<Relief|null>(null);
  const archived = useMemo(()=>manifest && result ? archiveResult(manifest,result,relief) : null,[manifest,result,relief]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<'screen' | 'sequence' | 'solve' | null>(null);
  const screenProbe=useRef<HTMLCanvasElement>(null);
  const worker = useRef<Worker | null>(null);
  const generation = useRef(0);
  useEffect(() => () => { generation.current++; worker.current?.terminate(); }, []);
  function change(next: Geometry) {
    generation.current++;
    worker.current?.terminate();
    setGeometry(next); setManifest(null); setFiles({}); setResult(null); setRelief(null); setError(''); setBusy(null);
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
  async function prepare(settings: Geometry, restored?: Record<string,File>) {
    const ticket = ++generation.current;
    setError(''); setBusy('sequence');
    try {
      const generated: Manifest = await (await generator('sequence',settings)).json();
      if (generation.current !== ticket) return;
      if (restored && Object.keys(restored).some(id => !generated.patterns.some(p=>p.id===id)))
        throw new Error('Saved source mapping differs from the regenerated illumination plan.');
      setGeometry(generated.geometry); setManifest(generated); setFiles(restored ?? {}); select('dark'); setResult(null); setRelief(null);
    } catch (e) { if (ticket === generation.current) setError(String(e)); if (restored) throw e; }
    finally { if (ticket === generation.current) setBusy(null); }
  }
  function stop() { worker.current?.terminate(); worker.current = null; setBusy(null); }
  function analyze() {
    if (!manifest) return;
    setError(''); setBusy('solve'); setResult(null); setRelief(null);
    const task = new Worker(new URL('./analyze.worker.ts',import.meta.url), { type:'module' });
    worker.current = task;
    task.onerror = e => { setError(e.message || 'Analysis failed.'); stop(); };
    task.onmessage = e => {
      if (e.data.error) setError(e.data.error); else setResult(e.data.result);
      stop();
    };
    task.postMessage({ manifest, files });
  }
  const pattern = manifest?.patterns.find(p => p.id === selected);
  const complete = manifest?.patterns.every(p => !!files[p.id]);
  return <>
    <h2>Display photometric stereo</h2>
    <p>Face a small matte object toward the screen. Mount an upright camera beside the display, aim at the object center, and lock focus, exposure and white balance. Keep the scene still throughout the sequence.</p>
    <canvas ref={screenProbe} className="photometry-illumination" aria-label="Measuring fullscreen raster"/>
    <PluginEditorShell pluginId={pluginId} value={geometry} onChange={change} disabled={!!busy} domainValidationEnabled={false}>
      {(_, validation) => <>
        <button disabled={!!busy} onClick={() => void measureScreen()}>Use this display’s pixel dimensions</button>
        <button disabled={!!busy || !validation?.valid || !!manifest} onClick={() => void prepare(geometry)}>Prepare illumination sequence</button>
        <SaveJobControl pluginId={pluginId} parameters={geometry} disabled={!!busy || !validation?.valid}/>
      </>}
    </PluginEditorShell>
    <MeasurementArchive pluginId={pluginId} parameters={geometry} steps={manifest?.patterns.map(p=>p.id) ?? []}
      files={files} result={archived} onRestore={async (parameters,images)=>{
        const restored=parameters as Geometry;
        stop(); await prepare(restored,images);
      }}/>
    {manifest && <>
      <PhotometryCamera key={manifest.sequenceId} manifest={manifest} onRestore={(g,images)=>prepare(g,images)}/>
      <h3>Manual capture</h3>
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
          setFiles({ ...files,[selected]:f }); setResult(null); setRelief(null); setError('');
        }}/></label>
      {files[selected] && <p>{files[selected].name}</p>}
      <p role="status">{Object.keys(files).length} / {manifest.patterns.length} exposures mapped</p>
      <button onClick={analyze} disabled={!!busy || !complete}>Reconstruct surface</button>
      {busy === 'solve' && <button onClick={stop}>Cancel reconstruction</button>}
      {result && <>
        <Relighting result={result} manifest={manifest} relief={relief}/>
        <RelativeRelief result={result} relief={relief} onChange={setRelief}/>
      </>}
    </>}
    {error && <p role="alert">{error}</p>}
  </>;
}
