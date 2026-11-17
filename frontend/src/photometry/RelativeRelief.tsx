import { useEffect, useRef, useState } from 'react';
import { download, type Result } from './data';
import { heightLimits, heightMethod, type Relief } from './height';

export function RelativeRelief({result,relief,onChange}:{result:Result;relief:Relief|null;onChange:(r:Relief|null)=>void}) {
  const [removePlane,setRemovePlane]=useState(false);
  const [width,setWidth]=useState(''),[range,setRange]=useState(''),[base,setBase]=useState('');
  const [error,setError]=useState(''),[busy,setBusy]=useState(false);
  const worker=useRef<Worker|null>(null), canvas=useRef<HTMLCanvasElement>(null);
  useEffect(()=>()=>worker.current?.terminate(),[]);
  useEffect(()=>{
    const ctx=canvas.current?.getContext('2d');
    if (!ctx || !relief) return;
    const image=ctx.createImageData(result.width,result.height);
    for (let i=0;i<relief.values.length;i++) if (relief.valid[i]) {
      const value=relief.max>relief.min ? 255*(relief.values[i]-relief.min)/(relief.max-relief.min) : 127;
      image.data.fill(value,4*i,4*i+3); image.data[4*i+3]=255;
    }
    ctx.putImageData(image,0,0);
  },[result,relief]);
  function stop() { worker.current?.terminate(); worker.current=null; setBusy(false); }
  function run(kind:'stl'|'integrate') {
    setError(''); setBusy(true);
    const task=new Worker(new URL('./relief.worker.ts',import.meta.url),{type:'module'});
    worker.current=task;
    task.onerror=e=>{setError(e.message);stop();};
    task.onmessage=e=>{
      if(e.data.error) setError(e.data.error);
      else if(e.data.relief) onChange(e.data.relief);
      else download(new Blob([e.data.stl],{type:'model/stl'}),'relative-relief-user-scaled-mm.stl');
      stop();
    };
    task.postMessage(kind==='integrate' ? {kind,result,removePlane} : {kind,relief,width:result.width,height:result.height,widthMm:+width,rangeMm:+range,baseMm:+base});
  }
  return <section><h3>Relative relief</h3>
    <label><input type="checkbox" checked={removePlane} disabled={busy}
      onChange={e=>{setRemovePlane(e.target.checked);onChange(null);}}/> Remove fitted tilt from each supported region</label>
    <button disabled={busy} onClick={()=>run('integrate')}>Integrate relative height</button>
    {busy && <button onClick={stop}>Cancel relief calculation</button>}
    {relief && <>
      <canvas ref={canvas} width={result.width} height={result.height} aria-label="Relative height map" style={{width:'100%',background:'#444'}}/>
      <p>{relief.components} supported region(s) with independent zero heights. RMS curl {relief.curlRms.toPrecision(3)}, fit residual {relief.slopeResidualRms.toPrecision(3)}.</p>
      <button onClick={()=>canvas.current?.toBlob(b=>b && download(b,'relative-height.png'))}>Download relative height PNG</button>
      <details><summary>Export user-scaled relief STL</summary>
        <p>Choose physical dimensions for this model. These dimensions are user-assigned. Width spans the camera image; height range scales the measured relative relief.</p>
        {([['Model width (mm)',width,setWidth],['Assigned height range (mm)',range,setRange],['Base thickness (mm)',base,setBase]] as const).map(([label,v,set])=>
          <label key={label}>{label} <input type="number" min=".01" max="2000" step="any" value={v} onChange={e=>set(e.target.value)}/></label>)}
        <button disabled={busy || ![width,range,base].every(v=>+v>=.01 && +v<=2000) || relief.max-relief.min<1e-6} onClick={()=>run('stl')}>Download user-scaled STL</button>
      </details>
    </>}
    {error && <p role="alert">{error}</p>}
  </section>;
}

export function describeRelief(relief:Relief|null) {
  return !relief ? null : {method:heightMethod,limits:heightLimits,curlRms:relief.curlRms,
    slopeResidualRms:relief.slopeResidualRms,iterations:relief.iterations,components:relief.components,
    removePlane:relief.removePlane,datum:'Independent zero per connected region',scale:'Relative, uncalibrated'};
}
