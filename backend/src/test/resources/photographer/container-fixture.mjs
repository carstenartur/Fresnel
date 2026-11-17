// An independent process serving the public v1 fixture bytes. Only accepted keys advance its mock camera.
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { sourceImage } from './photometry-image.mjs';
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';

const contract = JSON.parse(readFileSync(new URL('./contract.json',import.meta.url)));
const v = contract.values;
// 64×64 synthetic photometry frames exceed the tiny 1×1 protocol fixtures.
v.pairResult.descriptor.limits.maximumAssetBytes = 64 * 1024;
v.descriptor.limits.maximumAssetBytes = 64 * 1024;
const prefix = '/api/v1/integrations/fresnel';
const stateFile = process.env.FIXTURE_STATE_FILE ?? '/tmp/capture-state.json';
writeFileSync(process.env.FIXTURE_PID_FILE ?? '/tmp/photographer.pid',String(process.pid));
const state = existsSync(stateFile) ? JSON.parse(readFileSync(stateFile)) : {paired:false, keys:{}, shots:0};
function persist() {
  writeFileSync(stateFile+'.part', JSON.stringify(state));
  renameSync(stateFile+'.part',stateFile);
}
function json(response, status, value) {
  response.writeHead(status, {'Content-Type':'application/json'});
  response.end(JSON.stringify(value));
}
async function handle(req, res) {
  if (req.url === '/ready') return json(res,200,true);
  const path = req.url.slice(prefix.length);
  const body = [];
  for await (const chunk of req) body.push(chunk);
  const payload = Buffer.concat(body).toString();
  if (path === '/pair' && req.method === 'POST' && !state.paired) {
    if (JSON.parse(payload).pairingCode !== v.pair.pairingCode) return json(res,403,v.PAIRING_CODE_INVALID);
    state.paired = true; persist();
    return json(res,201,v.pairResult);
  }
  if (!state.paired || req.headers.authorization !== 'Bearer '+v.pairResult.bearerToken)
    return json(res,401,v.AUTHENTICATION_FAILED);
  if (path === '/health') return json(res,200,v.health);
  if (path === '/cameras') return json(res,200,v.cameras);
  if (path === '/descriptor') return json(res,200,v.descriptor);
  const key = req.headers['idempotency-key'];
  if (req.method === 'POST' && key) {
    const previous = state.keys[key];
    if (previous) {
      if (previous.path !== path || previous.payload !== payload) return json(res,409,v.IDEMPOTENCY_CONFLICT);
      return json(res,200,previous.result);
    }
    let result;
    if (path === '/capture-sessions') {
      state.plan = JSON.parse(payload);
      result = state.plan.workflowType === 'DISPLAY_PHOTOMETRIC_STEREO' ? {...v.sessionRef,sessionId:'capture-display'} : v.sessionRef;
    } else if (path.startsWith('/capture-sessions/capture-display/steps/')) {
      const id = path.split('/')[4], presented = JSON.parse(payload);
      const step = state.plan.steps.find(s => s.id === id);
      if (!step || step.externalPatternId !== presented.externalPatternId || step.externalPatternSha256 !== presented.externalPatternSha256)
        return json(res,409,v.PATTERN_MISMATCH);
      const bytes = sourceImage(id);
      const asset = {sessionId:'capture-display', assetId:'asset-'+id, stepId:id, role:step.role,
        originalFileName:id+'.png', mediaType:'image/png', byteLength:bytes.length,
        sha256:createHash('sha256').update(bytes).digest('hex'), capturedAt:new Date().toISOString()};
      result = {sessionId:'capture-display',stepId:id,asset,completedAt:asset.capturedAt};
    }
    else if (path === '/capture-sessions/capture-bos/steps/reference/trigger') result = v.referenceResult;
    else if (path === '/capture-sessions/capture-bos/steps/disturbed/trigger') result = v.disturbedResult;
    if (!result) return json(res,404,v.ASSET_NOT_FOUND);
    if (result.stepId) state.shots++;
    state.keys[key] = {path,payload,result}; persist();
    return json(res, result.stepId ? 200 : 201,result);
  }
  if (path === '/capture-sessions/capture-bos') return json(res,200,state.shots === 2 ? v.captureCompleted : v.capturePending);
  if (path.startsWith('/capture-sessions/capture-display/assets/')) {
    const assetId = path.split('/')[4];
    const asset = Object.values(state.keys).map(x => x.result?.asset).find(a => a?.assetId === assetId);
    if (!asset) return json(res,404,v.ASSET_NOT_FOUND);
    if (path.endsWith('/metadata')) return json(res,200,asset);
    const bytes = sourceImage(asset.stepId);
    res.writeHead(200,{'Content-Type':'image/png','Content-Length':bytes.length,ETag:'"'+asset.sha256+'"'});
    return res.end(bytes);
  }
  const file = contract.media.find(m => path.startsWith('/capture-sessions/capture-bos/assets/'+m.metadata.assetId));
  if (file) {
    if (path.endsWith('/metadata')) return json(res,200,file.metadata);
    const bytes = Buffer.from(file.bytesHex,'hex');
    res.writeHead(200,{'Content-Type':'image/png','Content-Length':bytes.length,ETag:file.etag});
    return res.end(bytes);
  }
  return json(res,404,v.ASSET_NOT_FOUND);
}
createServer((req,res)=>{ handle(req,res).catch(()=>json(res,500,{error:'invalid fixture request'})); }).listen(Number(process.env.PORT ?? 8080),'0.0.0.0');
