import type { Manifest, Pattern } from './data';

export interface CameraSession {
  id:string; sequenceId:string; state:string; nextStep:string|null; pendingStep:string|null;
  acceptedSteps:string[]; archiveId:string;
}
export interface Presentation { pixelSha256:string; presentationId:string }
interface Actions {
  stageAndSettle:(pattern:Pattern)=>Promise<void>; continuous:()=>boolean;
  advance:(step:string,phase:'trigger'|'accept',presentation:Presentation)=>Promise<CameraSession>;
  updated:(session:CameraSession)=>void;
}

export async function captureSequence(manifest:Manifest,start:CameraSession,actions:Actions) {
  let session=start;
  for (let index=start.acceptedSteps.length; index<manifest.patterns.length; index++) {
    const pattern=manifest.patterns[index];
    if (session.sequenceId!==manifest.sequenceId || session.state!=='READY' || session.pendingStep ||
        session.nextStep!==pattern.id || session.acceptedSteps.length!==index ||
        session.acceptedSteps.some((id,i)=>id!==manifest.patterns[i].id))
      throw new Error('The saved capture does not match this illumination plan.');
    await actions.stageAndSettle(pattern);
    if (!actions.continuous()) throw new Error('Display continuity lost before capture.');
    const ack={pixelSha256:pattern.pixelSha256,presentationId:crypto.randomUUID()};
    const observed=await actions.advance(pattern.id,'trigger',ack);
    if (!actions.continuous()) throw new Error('Display continuity lost during capture.');
    if (observed.id!==start.id || observed.pendingStep!==pattern.id || observed.state!=='AWAITING_ACCEPTANCE')
      throw new Error('Photographer did not confirm the expected exposure.');
    session=await actions.advance(pattern.id,'accept',ack);
    if (session.id!==start.id || session.sequenceId!==manifest.sequenceId || session.acceptedSteps.length!==index+1 ||
        session.acceptedSteps[index]!==pattern.id) throw new Error('Fresnel did not accept the expected exposure.');
    actions.updated(session);
    if (index+1<manifest.patterns.length && !actions.continuous()) throw new Error('Display continuity lost between captures.');
  }
  if (session.state!=='COMPLETED') throw new Error('Photographer sequence is incomplete.');
  return session;
}
