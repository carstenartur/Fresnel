import {useEffect,useState} from 'react';
import {type Geometry} from './data';

export function OriginalExposure({files,geometry}:{files:Record<string,File>;geometry:Geometry}) {
  const ids=Object.keys(files),[id,setId]=useState(ids.includes('flat')?'flat':ids[0]);
  const [url,setUrl]=useState(''),[error,setError]=useState('');
  const file=files[id];
  useEffect(()=>{
    if(!file)return;
    setError('');setUrl('');let link='';
    const worker=new Worker(new URL('./preflight.worker.ts',import.meta.url),{type:'module'});
    worker.onmessage=e=>{
      if(e.data.error)setError(e.data.error);
      else {link=URL.createObjectURL(e.data.preview);setUrl(link);}
      worker.terminate();
    };
    worker.onerror=e=>{setError(e.message);worker.terminate();};
    worker.postMessage({original:file,gamma:geometry.cameraGamma});
    return ()=>{worker.terminate();if(link)URL.revokeObjectURL(link);};
  },[file,geometry.cameraGamma]);
  return <div><label>Original pattern <select aria-label="Original pattern" value={id} onChange={e=>setId(e.target.value)}>
    {ids.map(key=><option key={key}>{key}</option>)}</select></label>
    {url && <img alt={'Camera exposure for '+id} src={url} style={{width:'100%'}}/>}
    {error&&<p role="alert">{error}</p>}
  </div>;
}
