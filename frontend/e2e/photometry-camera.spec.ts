import {expect,test} from '@playwright/test';
import {captureSequence,type CameraSession} from '../src/photometry/cameraSequence';
import type {Manifest} from '../src/photometry/data';

const plan={sequenceId:'one',patterns:[{id:'dark',pixelSha256:'dark-hash'},{id:'flat',pixelSha256:'flat-hash'}]} as Manifest;
const ready:CameraSession={id:'capture',archiveId:'archive',sequenceId:'one',state:'READY',nextStep:'dark',pendingStep:null,acceptedSteps:[]};

test('never accepts an exposure after losing the presentation, and never triggers before staging',async()=>{
  let continuous=true;
  const events:string[]=[];
  await expect(captureSequence(plan,ready,{
    stageAndSettle:async p=>{events.push('settled '+p.id);},continuous:()=>continuous,
    advance:async(step,phase)=>{events.push(phase+' '+step);continuous=false;
      return {...ready,state:'AWAITING_ACCEPTANCE',pendingStep:step};},updated:()=>{},
  })).rejects.toThrow('continuity');
  expect(events).toEqual(['settled dark','trigger dark']);
  await expect(captureSequence(plan,{...ready,acceptedSteps:['dark'],nextStep:'dark'},{
    stageAndSettle:async()=>{throw new Error('Unexpected presentation');},continuous:()=>true,
    advance:async()=>{throw new Error('Unexpected trigger');},updated:()=>{},
  })).rejects.toThrow('plan');
});

test('full-screen camera capture acknowledges committed pixels before one trigger and one acceptance per step',async({page})=>{
  let manifest:Manifest;
  let capture:CameraSession;
  const stages:string[]=[];
  const presentations=new Map<string,string>();
  await page.route(/\/api\/experiments\/photometry-captures(?:\/.*)?$/,async route=>{
    const u=new URL(route.request().url()),method=route.request().method();
    if (u.pathname.endsWith('/cameras')) return route.fulfill({json:[{id:'test',name:'Mock Photographer'}]});
    if (method==='GET') return route.fulfill({json:[]});
    if (method==='PUT') {
      capture={...ready,id:u.pathname.split('/').at(-1)!,sequenceId:manifest.sequenceId,nextStep:manifest.patterns[0].id,acceptedSteps:[]};
      return route.fulfill({json:capture});
    }
    return route.fulfill({status:404});
  });
  await page.route('**/api/experiments/photometry-captures/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    if(!path.includes('/steps/')) return route.fallback();
    const [step,phase]=path.slice(path.indexOf('/steps/')+7).split('/');
    const p=manifest.patterns[capture.acceptedSteps.length];
    expect(step).toBe(p.id);
    const ack=route.request().postDataJSON();
    expect(ack.pixelSha256).toBe(p.pixelSha256);
    const fullscreen=await page.evaluate(()=>document.fullscreenElement?.getAttribute('aria-label'));
    expect(fullscreen).toBe('Synchronized illumination');
    if (phase==='trigger') {
      expect(presentations.has(step)).toBe(false);
      expect(ack.presentationId).toMatch(/^[a-f0-9-]{36}$/);
      presentations.set(step,ack.presentationId);
      capture={...capture,state:'AWAITING_ACCEPTANCE',pendingStep:step};
    } else {
      expect(phase).toBe('accept');expect(ack.presentationId).toBe(presentations.get(step));
      const acceptedSteps=[...capture.acceptedSteps,step];
      const next=manifest.patterns[acceptedSteps.length];
      capture={...capture,acceptedSteps,pendingStep:null,nextStep:next?.id??null,state:next?'READY':'COMPLETED'};
    }
    stages.push(phase+' '+step);
    return route.fulfill({json:capture});
  });
  await page.goto('/plugins/display-photometric-stereo');
  await page.getByRole('button',{name:'Use this display’s pixel dimensions'}).click();
  const sequence=page.waitForResponse(r=>r.url().endsWith('/display-photometric-stereo/sequence'));
  await page.getByRole('button',{name:'Prepare illumination sequence'}).click();
  manifest=await (await sequence).json();
  await page.getByText('Synchronized Photographer capture',{exact:true}).click();
  await page.getByLabel('Operator username').fill('operator');
  await page.getByLabel('Operator password').fill('fixture');
  await page.getByRole('button',{name:'Connect camera provider'}).click();
  await page.getByRole('button',{name:'Prepare camera capture'}).click();
  await page.getByRole('button',{name:'Run camera sequence full screen'}).click();
  await expect(page.getByText('COMPLETED · 10 / 10 exposures accepted')).toBeVisible({timeout:30000});
  expect(stages).toEqual(manifest.patterns.flatMap(p=>['trigger '+p.id,'accept '+p.id]));
});
