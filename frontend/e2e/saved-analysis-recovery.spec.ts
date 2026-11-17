import {createHash,randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {expect,test,type Page} from '@playwright/test';

test('reopens an interrupted private BOS video analysis and commits the result to the same experiment',async({page,context,request})=>{
  const user=process.env.E2E_USER??'user', password=process.env.E2E_PASSWORD??'user';
  const authorization='Basic '+Buffer.from(user+':'+password).toString('base64');
  const headers={Authorization:authorization};
  const id=randomUUID(), path='/api/measurement-sessions/'+id;
  const mp4=await readFile(new URL('./fixtures/bos-eight-frames.mp4',import.meta.url));
  expect((await request.put(path,{headers,data:{pluginId:'background-oriented-schlieren',
    parameters:{sourceKind:'video',rate:2,policy:'fixed',manualRegion:{x:0.1,y:0.1,width:0.8,height:0.8}},steps:['video']}})).ok()).toBe(true);
  try {
    expect((await request.put(path+'/assets/video',{headers:{...headers,'Content-Type':'video/mp4',
      'X-Content-SHA256':createHash('sha256').update(mp4).digest('hex')},data:mp4})).ok()).toBe(true);
    async function openArchive(tab:Page) {
      await tab.goto('/plugins/background-oriented-schlieren');
      await tab.getByText('Analyze a sequence or video',{exact:true}).click();
      const sequence=tab.getByRole('region',{name:'BOS time series'});
      await sequence.getByText('Saved experiments',{exact:true}).click();
      await sequence.getByLabel('Fresnel username').fill(user);
      await sequence.getByLabel('Password',{exact:true}).fill(password);
      await sequence.getByRole('button',{name:'Sign in to saved experiments'}).click();
      await sequence.getByRole('combobox',{name:/^Saved experiment/}).selectOption(id);
      return sequence;
    }
    const original=await openArchive(page);
    // Hold the worker script on the first tab so closing the browser deterministically interrupts computation.
    await page.route(/bosSequence\.worker[^/]*\.js/,()=>{});
    const accepted=page.waitForResponse(r=>r.request().method()==='PUT' && r.url().includes(path+'/analysis/') && r.ok());
    await original.getByRole('button',{name:'Analyze saved captures'}).click();
    await accepted;
    await expect(original.getByText('Analyzing the saved sources…',{exact:true})).toBeVisible();
    await page.close();
    const interrupted=await (await request.get(path,{headers})).json();
    expect(interrupted.state).toBe('ANALYZING');
    expect(interrupted.assets.result).toBeUndefined();

    const fresh=await context.newPage(), resumed=await openArchive(fresh);
    await resumed.getByRole('button',{name:'Resume saved analysis'}).click();
    await expect(resumed.getByText('Analysis completed and saved.',{exact:true})).toBeVisible({timeout:45_000});
    const completed=await (await request.get(path,{headers})).json();
    expect(completed.state).toBe('COMPLETED');
    expect(completed.analysisAttempt).not.toBe(interrupted.analysisAttempt);
    const result=await (await request.get(path+'/assets/result',{headers})).body();
    expect(createHash('sha256').update(result).digest('hex')).toBe(completed.assets.result.sha256);
    const analyzed=JSON.parse(result.toString());
    expect(analyzed.frames.length).toBeGreaterThan(1);
    expect(analyzed.analysisRegion).toEqual({x:0.1,y:0.1,width:0.8,height:0.8});
    expect(analyzed.frames[0].sourceRegion).toEqual({x:14,y:13,width:116,height:102});
  } finally { await request.delete(path,{headers}); }
});
