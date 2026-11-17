import { integrate } from './height';
import { reliefStl } from './reliefStl';
import type { Result } from './data';
import type { Relief } from './height';

type Task = { kind:'integrate'; result:Result; removePlane:boolean } |
  { kind:'stl'; width:number; height:number; relief:Relief; widthMm:number; rangeMm:number; baseMm:number };
self.onmessage = (event:MessageEvent<Task>) => {
  try {
    const task=event.data;
    if (task.kind==='integrate') self.postMessage({relief:integrate(task.result.width,task.result.height,task.result.grid,task.removePlane)});
    else self.postMessage({stl:reliefStl(task.width,task.height,task.relief,task.widthMm,task.rangeMm,task.baseMm)});
  } catch (error) { self.postMessage({error:error instanceof Error ? error.message : String(error)}); }
};
