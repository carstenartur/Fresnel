import { expect, test, type Page } from '@playwright/test';

interface Solved {
  width:number; height:number; scale:string; sources:{patternId:string;sha256:string}[];
  grid:{ valid:number[]; normals:number[]; albedo:number[]; confidence:number[]; residual:number[]; lightCount:number[] };
}
async function downloadGrid(page:Page):Promise<Solved> {
  const grid=page.waitForEvent('download');
  await page.getByRole('button',{name:'Download result grid and provenance'}).click();
  const stream=await (await grid).createReadStream(),chunks:Buffer[]=[];
  for await (const chunk of stream) chunks.push(chunk as Buffer);
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Solved;
}
function percentile95(errors:number[]) {
  errors.sort((a,b)=>a-b);
  return errors[Math.floor(.95*(errors.length-1))];
}

// The Failsafe network gate supplies a real paired backend and a containerized Photographer.
test.skip(!process.env.FRESNEL_PHOTOMETRY_NETWORK_IT, 'Run with the paired Photographer network fixture.');
test('paired Photographer records four directional exposures and reconstructs archived camera media after a reload', async ({page})=>{
  test.setTimeout(120_000);
  const user=process.env.E2E_USER??'user',password=process.env.E2E_PASSWORD??'user';
  await page.goto('/plugins/display-photometric-stereo');
  await page.getByText('Camera and display raster',{exact:true}).click();
  await page.getByLabel('Directional lights').fill('4');
  await page.getByRole('button',{name:'Use this display’s pixel dimensions'}).click();
  await page.getByRole('button',{name:'Prepare illumination sequence'}).click();
  await page.getByText('Synchronized Photographer capture',{exact:true}).click();
  await page.getByLabel('Operator username').fill(user);
  await page.getByLabel('Operator password').fill(password);
  await page.getByRole('button',{name:'Connect camera provider'}).click();
  await expect(page.getByRole('option',{name:'Mock camera',exact:true})).toBeAttached();
  await page.getByLabel('I have manually locked exposure and focus',{exact:false}).check();
  await page.getByRole('button',{name:'Prepare camera capture'}).click();
  await page.getByRole('button',{name:'Run camera sequence full screen'}).click();
  await expect(page.getByText('COMPLETED · 6 / 6 exposures accepted')).toBeVisible({timeout:60000});
  await page.getByRole('button',{name:'Open captured exposures'}).click();
  await expect(page.getByText('6 / 6 exposures mapped')).toBeVisible();
  await expect(page.getByRole('region',{name:'Measurement workflow'})).toHaveAttribute('data-state','READY_TO_ANALYZE');
  await page.getByRole('button',{name:'Reconstruct surface'}).click();
  await expect(page.getByRole('heading',{name:'Measured surface'})).toBeVisible();
  const live=await downloadGrid(page);
  await page.reload();
  await page.getByText('Saved experiments',{exact:true}).click();
  await page.getByLabel('Fresnel username').fill(user);
  await page.getByLabel('Password',{exact:true}).fill(password);
  await page.getByRole('button',{name:'Sign in to saved experiments'}).click();
  await page.getByRole('button',{name:'Open saved captures'}).click();
  await expect(page.getByText('6 / 6 exposures mapped')).toBeVisible();
  await expect(page.getByRole('region',{name:'Measurement workflow'})).toHaveAttribute('data-state','READY_TO_ANALYZE');
  await page.getByRole('button',{name:'Reconstruct surface'}).click();
  await expect(page.getByRole('heading',{name:'Measured surface'})).toBeVisible();
  const solved=await downloadGrid(page);
  expect([solved.width,solved.height]).toEqual([64,64]);
  expect(solved.sources.map((s: {patternId:string})=>s.patternId)).toEqual(['dark','flat','light-01','light-02','light-03','light-04']);
  expect(solved.sources.map(s=>s.sha256)).toEqual(live.sources.map(s=>s.sha256));
  expect(solved.grid).toEqual(live.grid);
  const length=Math.hypot(450,160);
  const normal=[0,-160/length,450/length]; // the fixture plane faces the screen; camera is above its center
  const angular:number[]=[],albedo:number[]=[];
  for(let i=0;i<solved.grid.valid.length;i++) if(solved.grid.valid[i]){
    const x=i%64,y=Math.floor(i/64);
    const texture=.5+.08*Math.sin(.41*x+.7*y)+.09*Math.cos(.19*x-.63*y)+.06*Math.sin(.55*x-.22*y);
    const dot=normal.reduce((sum,v,j)=>sum+v*solved.grid.normals[3*i+j],0);
    angular.push(Math.acos(Math.max(-1,Math.min(1,dot)))*180/Math.PI);
    albedo.push(Math.abs(solved.grid.albedo[i]-texture));
  }
  expect(angular.length).toBeGreaterThan(.99*64*64);
  expect(percentile95(angular)).toBeLessThan(1);
  expect(percentile95(albedo)).toBeLessThan(.008);
  expect(solved.scale).toBe('Relative');
});
