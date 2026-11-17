import { decodeCapture } from './decode';
import { inRegion,regionFrom,type Region } from './setup';
import { register, registrationReference } from './registration';
import { reconstruct, type Light } from './solve';
import type { Manifest, Source, Result } from './data';

self.onmessage = async (event: MessageEvent<{ manifest: Manifest; files: Record<string,File>; roi?:Region }>) => {
  try {
    const { manifest, files } = event.data;
    const roi=regionFrom(event.data.roi);
    const sources: Source[] = [], lights: Light[] = [], frames: Float32Array[] = [];
    let dark: Float32Array | undefined, flat: Float32Array | undefined, width = 0, height = 0;
    let reference:ReturnType<typeof registrationReference> | undefined;
    const warnings:string[]=[];
    for (const p of manifest.patterns) {
      const file = files[p.id];
      if (!file) throw new Error('Missing camera exposure for '+p.id);
      const decoded=await decodeCapture(file,manifest.geometry.cameraGamma);
      const {intensities,sourceWidth,sourceHeight}=decoded;
      if(sources.length && (sourceWidth!==sources[0].width || sourceHeight!==sources[0].height))
        throw new Error('All camera images must have the same upright dimensions.');
      width=decoded.width; height=decoded.height;
      let transform:Source['transform']=null;
      if (p.role === 'DARK') dark = intensities;
      else if (p.role === 'FLAT') {
        flat = intensities;
        reference=registrationReference(flat,width,height);
      } else if (p.direction && reference) {
        try {
          const registered=register(reference,intensities);
          transform=registered.transform; frames.push(registered.samples);
        } catch(error) { throw new Error(p.id+': '+(error instanceof Error ? error.message : String(error))); }
        lights.push({ ...p.direction, relativeIntensity:p.relativeIntensity });
      }
      sources.push({ patternId:p.id, sha256:decoded.hash, name:file.name, width:sourceWidth, height:sourceHeight,
        lastModified:file.lastModified, transform, clippedFraction:decoded.clippedFraction });
    }
    if (!dark || !flat) throw new Error('Dark and flat exposures are required.');
    if (sources.some(s=>s.transform?.status==='LOW_TEXTURE'))
      warnings.push('Some exposures lack enough texture to verify alignment. The solve assumes the object and camera remained fixed.');
    if (sources.some(s=>s.transform && s.clippedFraction>.15))
      warnings.push('More than 15% of a directional exposure clipped. Reduce exposure and repeat to recover those observations.');
    let ambient=0,lit=0;
    for (let i=0;i<flat.length;i++) { ambient+=dark[i]; lit+=flat[i]; }
    if (lit>0 && ambient/lit>.35) warnings.push('Ambient light is strong relative to the display. Reduce room light before repeating the capture.');
    for(let i=0;i<flat.length;i++) if(!inRegion(i,width,height,roi))flat[i]=0;
    const grid = reconstruct(lights, dark, flat, frames);
    if (!grid.valid.some(x => x === 1)) throw new Error('No supported surface pixels. Check pattern mapping, exposure, camera locks and object motion.');
    const result: Result = { width, height, grid, sources, warnings, roi };
    self.postMessage({ result });
  } catch (e) { self.postMessage({ error: e instanceof Error ? e.message : String(e) }); }
};
