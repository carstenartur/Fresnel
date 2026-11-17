// An independent process serving the public v1 fixture bytes. Only accepted keys advance its mock camera.
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';

const contract = JSON.parse(readFileSync('/fixture/contract.json'));
const v = contract.values;
const prefix = '/api/v1/integrations/fresnel';
const stateFile = '/tmp/capture-state.json';
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
    if (path === '/capture-sessions') result = v.sessionRef;
    else if (path === '/capture-sessions/capture-bos/steps/reference/trigger') result = v.referenceResult;
    else if (path === '/capture-sessions/capture-bos/steps/disturbed/trigger') result = v.disturbedResult;
    if (!result) return json(res,404,v.ASSET_NOT_FOUND);
    if (result.stepId) state.shots++;
    state.keys[key] = {path,payload,result}; persist();
    return json(res, result.stepId ? 200 : 201,result);
  }
  if (path === '/capture-sessions/capture-bos') return json(res,200,state.shots === 2 ? v.captureCompleted : v.capturePending);
  const file = contract.media.find(m => path.startsWith('/capture-sessions/capture-bos/assets/'+m.metadata.assetId));
  if (file) {
    if (path.endsWith('/metadata')) return json(res,200,file.metadata);
    const bytes = Buffer.from(file.bytesHex,'hex');
    res.writeHead(200,{'Content-Type':'image/png','Content-Length':bytes.length,ETag:file.etag});
    return res.end(bytes);
  }
  return json(res,404,v.ASSET_NOT_FOUND);
}
createServer((req,res)=>{ handle(req,res).catch(()=>json(res,500,{error:'invalid fixture request'})); }).listen(8080,'0.0.0.0');
