import {decodeCapture} from './decode';
import {assess,regionFrom,type Region} from './setup';
import {register,registrationReference} from './registration';
self.onmessage=async (event:MessageEvent<{original?:File;files:Record<string,File>;gamma:number;opposite:string;roi:Region}>)=>{
  try {
    if(event.data.original) {
      const decoded=await decodeCapture(event.data.original,event.data.gamma,true);
      self.postMessage({preview:decoded.preview});return;
    }
    const {files,gamma,opposite}=event.data,roi=regionFrom(event.data.roi);
    const first=await decodeCapture(files.flat,gamma,true);
    const {width,height,intensities,preview}=first;
    const samples:Record<string,Float32Array>={},ref=registrationReference(intensities,width,height);
    const motion:string[]=[];
    for(const id of ['dark','light-01',opposite]) if(files[id]){
      const next=await decodeCapture(files[id],gamma);
      if(next.sourceWidth!==first.sourceWidth || next.sourceHeight!==first.sourceHeight) throw new Error('Test frames must have the same upright camera dimensions.');
      if(id==='dark')samples[id]=next.intensities;
      else {const mapped=register(ref,next.intensities);samples[id]=mapped.samples;
        if(mapped.transform.status==='ALIGNED')motion.push(id+' moved by '+mapped.transform.dx+', '+mapped.transform.dy+' analysis pixels.');}
    }
    const evidence=assess(width,height,roi,intensities,samples.dark,samples['light-01'],samples[opposite]);
    evidence.messages.push(...motion);
    self.postMessage({evidence,preview});
  } catch(e) {self.postMessage({error:e instanceof Error?e.message:String(e)});}
};
