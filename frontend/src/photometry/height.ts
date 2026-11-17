import type { Grid } from './solve';

export interface Relief {
  values: Float32Array; valid: Uint8Array; components: number; curlRms: number;
  slopeResidualRms: number; iterations: number; removePlane: boolean; min: number; max: number;
}
export const heightMethod = 'masked-poisson/1';
export const heightLimits = { minimumConfidence:.5, minimumNormalZ:.32, maximumCurlRms:.12, maximumSlopeResidualRms:.12 };

/** Least-squares Poisson integration on the four-neighbor graph of supported pixels.
 * Every connected island has its own arbitrary zero; no gradient crosses an invalid pixel. */
export function integrate(width: number,height: number,grid: Grid,removePlane: boolean): Relief {
  const n = width*height;
  if (!Number.isInteger(width) || !Number.isInteger(height) || width<2 || height<2 ||
      n>512*512 || grid.valid.length!==n) throw new Error('Invalid relief grid');
  const valid = new Uint8Array(n), dx = new Float32Array(n), dy = new Float32Array(n);
  for (let i=0; i<n; i++) {
    const z=grid.normals[3*i+2];
    if (grid.valid[i] && grid.confidence[i]>=heightLimits.minimumConfidence && z>=heightLimits.minimumNormalZ) {
      valid[i]=1; dx[i]=-grid.normals[3*i]/z; dy[i]=grid.normals[3*i+1]/z;
    }
  }
  const component = new Int32Array(n), queue = new Int32Array(n);
  const groups: { start:number; end:number }[] = [];
  let end=0, curlSum=0, cellCount=0;
  for (let seed=0; seed<n; seed++) {
    if (!valid[seed] || component[seed]) continue;
    const start=end, id=groups.length+1;
    let cells=0, sum=0;
    queue[end++]=seed; component[seed]=id;
    for (let q=start; q<end; q++) {
      const i=queue[q], x=i%width;
      for (const j of [x>0 ? i-1 : -1,x+1<width ? i+1 : -1,i-width,i+width]) {
        if (j>=0 && j<n && valid[j] && !component[j]) { queue[end++]=j; component[j]=id; }
      }
      if (x+1<width && i+width+1<n && valid[i+1] && valid[i+width] && valid[i+width+1]) {
        const top=.5*(dx[i]+dx[i+1]), bottom=.5*(dx[i+width]+dx[i+width+1]);
        const left=.5*(dy[i]+dy[i+width]), right=.5*(dy[i+1]+dy[i+width+1]);
        const curl=top+right-bottom-left;
        sum+=curl*curl; cells++;
      }
    }
    if (cells<1) { for (let q=start;q<end;q++) valid[queue[q]]=0; end=start; continue; }
    if (Math.sqrt(sum/cells)>heightLimits.maximumCurlRms) throw new Error('Surface curl exceeds the integrability gate. Check motion, gloss and lighting.');
    groups.push({start,end}); curlSum+=sum; cellCount+=cells;
  }
  if (!groups.length) throw new Error('Insufficient supported surface area for relative height.');
  const from = new Int32Array(2*n), to = new Int32Array(2*n), delta = new Float32Array(2*n);
  const rhs = new Float64Array(n);
  let edges=0;
  function edge(i:number,j:number,slope:number) {
    from[edges]=i; to[edges]=j; delta[edges++]=slope; rhs[i]-=slope; rhs[j]+=slope;
  }
  for (let i=0;i<n;i++) {
    if (!valid[i]) continue;
    if (i%width+1<width && valid[i+1]) edge(i,i+1,.5*(dx[i]+dx[i+1]));
    if (i+width<n && valid[i+width]) edge(i,i+width,.5*(dy[i]+dy[i+width]));
  }

  // Conjugate gradients from zero stays orthogonal to each component's constant nullspace.
  const solution=new Float64Array(n), r=rhs.slice(), p=rhs.slice(), ap=new Float64Array(n);
  let rr=0;
  for (const value of r) rr+=value*value;
  const tolerance=Math.max(1e-20, rr*1e-12);
  let iterations=0;
  while (rr>tolerance && iterations<Math.min(2048,Math.max(128,6*(width+height)))) {
    ap.fill(0);
    let pap=0;
    for (let e=0;e<edges;e++) {
      const i=from[e],j=to[e],diff=p[i]-p[j];
      ap[i]+=diff; ap[j]-=diff; pap+=diff*diff;
    }
    if (!(pap>0)) throw new Error('Height integration lost numerical rank.');
    const a=rr/pap;
    let next=0;
    for (let q=0;q<end;q++) { const i=queue[q]; solution[i]+=a*p[i]; r[i]-=a*ap[i]; next+=r[i]*r[i]; }
    const beta=next/rr;
    for (let q=0;q<end;q++) { const i=queue[q]; p[i]=r[i]+beta*p[i]; }
    rr=next; iterations++;
  }
  if (rr>tolerance) throw new Error('Height integration did not converge within the iteration limit.');
  let squared=0;
  for (let e=0;e<edges;e++) squared+=(solution[to[e]]-solution[from[e]]-delta[e])**2;
  const slopeResidualRms=Math.sqrt(squared/edges);
  if (slopeResidualRms>heightLimits.maximumSlopeResidualRms) throw new Error('Integrated heights disagree with the measured normals.');
  const values=new Float32Array(n);
  let min=Infinity,max=-Infinity;
  for (const group of groups) {
    const count=group.end-group.start;
    let mx=0,my=0,mz=0;
    for (let q=group.start;q<group.end;q++) {
      const i=queue[q]; mx+=i%width; my+=Math.floor(i/width); mz+=solution[i];
    }
    mx/=count; my/=count; mz/=count;
    let ax=0,ay=0;
    if (removePlane) {
      let xx=0,xy=0,yy=0,xz=0,yz=0;
      for (let q=group.start;q<group.end;q++) {
        const i=queue[q],x=i%width-mx,y=Math.floor(i/width)-my,z=solution[i]-mz;
        xx+=x*x; xy+=x*y; yy+=y*y; xz+=x*z; yz+=y*z;
      }
      const det=xx*yy-xy*xy;
      if (det>0) { ax=(yy*xz-xy*yz)/det; ay=(xx*yz-xy*xz)/det; }
    }
    for (let q=group.start;q<group.end;q++) {
      const i=queue[q];
      values[i]=solution[i]-mz-ax*(i%width-mx)-ay*(Math.floor(i/width)-my);
      min=Math.min(min,values[i]); max=Math.max(max,values[i]);
    }
  }
  return {values,valid,components:groups.length,curlRms:Math.sqrt(curlSum/cellCount),slopeResidualRms,
    iterations,removePlane,min,max};
}
