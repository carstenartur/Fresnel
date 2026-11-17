import { useEffect, useRef, useState } from 'react';
import { download, type Manifest, type Result } from './data';
import { OriginalExposure } from './OriginalExposure';
import { withEvidence } from './pngEvidence';
import { describeRelief } from './RelativeRelief';
import type { Relief } from './height';
import { registrationLimits,registrationMethod } from './registration';
import { limits } from './solve';

const layers = ['Relighting','Normal','Albedo','Confidence','Residual','Validity','Camera exposure'] as const;
type Layer = typeof layers[number];

export function Relighting({ result, manifest, relief,files }: { result: Result; manifest: Manifest; relief: Relief|null; files:Record<string,File> }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [layer, setLayer] = useState<Layer>('Relighting');
  const [light, setLight] = useState([0,0]);
  const [intensity, setIntensity] = useState(1);
  const [ambient, setAmbient] = useState(.1);
  const { grid, width, height } = result;
  const valid = grid.valid.reduce((n, value) => n + value, 0);
  useEffect(() => {
    const ctx = canvas.current?.getContext('2d');
    if (!ctx || layer==='Camera exposure') return;
    const image = ctx.createImageData(width,height);
    const z = Math.sqrt(Math.max(0,1 - light[0]**2 - light[1]**2));
    for (let i = 0; i < grid.valid.length; i++) {
      if (!grid.valid[i] && layer !== 'Validity' && layer !== 'Residual') continue;
      let v: number;
      switch (layer) {
        case 'Normal':
          for (let j = 0; j < 3; j++) image.data[4*i+j] = (grid.normals[3*i+j]+1)*127.5;
          break;
        case 'Confidence': v = grid.confidence[i]; break;
        case 'Residual': v = Math.min(1,10*grid.residual[i]); break;
        case 'Validity': v = grid.valid[i]; break;
        case 'Albedo': v = grid.albedo[i]**(1/2.2); break;
        default: {
          const dot = Math.max(0, light[0]*grid.normals[3*i] + light[1]*grid.normals[3*i+1] + z*grid.normals[3*i+2]);
          v = (grid.albedo[i]*(ambient + intensity*dot))**(1/2.2);
        }
      }
      if (layer !== 'Normal') image.data.fill(255*v!,4*i,4*i+3);
      image.data[4*i+3] = 255;
    }
    ctx.putImageData(image,0,0);
  }, [grid, width, height, light, intensity, ambient, layer]);
  function move(x: number, y: number) {
    const scale = Math.max(1,Math.hypot(x,y));
    setLight([x/scale,y/scale]);
  }
  const lighting={ x:light[0], y:light[1], z:Math.sqrt(Math.max(0,1-light[0]**2-light[1]**2)),intensity,ambient };
  const output = () => canvas.current?.toBlob(async b => {
    if (b) download(await withEvidence(b,{algorithm:manifest.algorithmVersion,sequenceId:manifest.sequenceId,
      layer,scale:'Relative',roi:result.roi,...(layer==='Relighting'?{lighting}:{})}), 'photometry-' + layer.toLowerCase() + '.png');
  }, 'image/png');
  function numeric() {
    const arrays = Object.fromEntries(Object.entries(grid).map(([key,v]) => [key,Array.from(v)]));
    const metadata = { manifest, sources:result.sources, width,height, solver:'trimmed-lambertian/1', limits,
      alignment:{method:registrationMethod,limits:registrationLimits},warnings:result.warnings,roi:result.roi, scale:'Relative', grid:arrays,
      relief:relief && {...describeRelief(relief), values:Array.from(relief.values),valid:Array.from(relief.valid)},
      relighting: lighting };
    download(new Blob([JSON.stringify(metadata)],{type:'application/json'}),'photometry-result.json');
  }
  return <section><h3>Measured surface</h3>
    <p>{valid.toLocaleString()} of {grid.valid.length.toLocaleString()} pixels passed the solve. Unsupported surface pixels are transparent in the relit image.</p>
    {result.warnings.length>0 && <ul>{result.warnings.map(w=><li key={w}>{w}</li>)}</ul>}
    <label>Result layer <select value={layer} onChange={e => setLayer(e.target.value as Layer)}>
      {layers.map(x => <option key={x}>{x}</option>)}</select></label>
    {layer==='Camera exposure' ? <OriginalExposure files={files} geometry={manifest.geometry}/> : <canvas ref={canvas} aria-label="Measured surface result" width={width} height={height}
      style={{display:'block',width:'100%',height:'auto',touchAction:'none',background:'#444'}}
      onPointerDown={e => e.currentTarget.setPointerCapture(e.pointerId)}
      onPointerMove={e => {
        if (layer !== 'Relighting' || (e.pointerType === 'touch' && !e.buttons)) return;
        const box = e.currentTarget.getBoundingClientRect();
        move(2*(e.clientX-box.left)/box.width-1,1-2*(e.clientY-box.top)/box.height);
      }}/>}
    {layer === 'Relighting' && <>
      <p>Move the pointer or drag on the surface to move the virtual light.</p>
      <label>Light horizontal <input type="range" min="-1" max="1" step=".01" value={light[0]} onChange={e=>move(+e.target.value,light[1])}/></label>
      <label>Light vertical <input type="range" min="-1" max="1" step=".01" value={light[1]} onChange={e=>move(light[0],+e.target.value)}/></label>
      <label>Light intensity <input type="range" min="0" max="3" step=".05" value={intensity} onChange={e=>setIntensity(+e.target.value)}/></label>
      <label>Ambient <input type="range" min="0" max="1" step=".05" value={ambient} onChange={e=>setAmbient(+e.target.value)}/></label>
    </>}
    {layer!=='Camera exposure' && <button type="button" onClick={output}>Download {layer.toLowerCase()} PNG</button>}
    <button type="button" onClick={numeric}>Download result grid and provenance</button>
  </section>;
}
