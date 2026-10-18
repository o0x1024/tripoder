import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { randomUUID, generateKeyPairSync, createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
import { Transport, envelope, loadConfig } from './transport.mjs';
import { MCP_VERSION } from './server.mjs';
const [configFile,metadataFile,evidenceFile,mode='normal']=process.argv.slice(2);
const metadata=JSON.parse(await readFile(metadataFile,'utf8')),config=await loadConfig(configFile),exec=promisify(execFile);
const child=spawn(process.execPath,[resolve('agent/server.mjs'),'--config',configFile],{stdio:['pipe','pipe','pipe']});
const pending=new Map(),transcript=[],checks=[];let sequence=0,stderr='';child.stderr.on('data',data=>{stderr+=data;});
createInterface({input:child.stdout}).on('line',line=>{const value=JSON.parse(line),waiter=pending.get(value.id);if(!waiter)throw Error('UNEXPECTED_RPC_RESPONSE');pending.delete(value.id);clearTimeout(waiter.timer);transcript.push({direction:'response',message:value});waiter.resolve(value);});
function rpc(method,params){const id=++sequence,message={jsonrpc:'2.0',id,method,...(params===undefined?{}:{params})};transcript.push({direction:'request',message});return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_RESPONSE_TIMEOUT'));},30000);pending.set(id,{resolve,reject,timer});child.stdin.write(JSON.stringify(message)+'\n');});}
function check(name,actual){assert.ok(actual,name);checks.push({name,passed:true});}
async function tool(name,args={},success=true){const result=await rpc('tools/call',{name,arguments:args});if(result.error)throw Error(JSON.stringify(result.error));const receipt=JSON.parse(result.result.content[0].text);assert.equal(result.result.isError,!receipt.ok);if(success&&!receipt.ok)throw Error(JSON.stringify(receipt));return receipt;}
async function milestone(phase){const end=Date.now()+60000;while(Date.now()<end){try{const result=await exec('adb',['-s',config.serial,'shell','cat',`/sdcard/Android/data/dev.tripoder.engine/files/agent-${metadata.runId}-${phase}.json`],{timeout:5000,maxBuffer:4096});const data=JSON.parse(result.stdout);assert.equal(data.runId,metadata.runId);return data;}catch(error){if(Date.now()+400>=end)throw error;await new Promise(resolve=>setTimeout(resolve,400));}}}
let passed=false;
try{
  const init=await rpc('initialize',{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'tripoder-acceptance-client',version:'1'}});check('mcp-initialize-exact-version',init.result.protocolVersion===MCP_VERSION);
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
  check('mcp-discovers-19-scoped-tools',(await rpc('tools/list')).result.tools.length===19);
  const session=await tool('session_get');check('actual-project-bound',session.result.project===metadata.project);
  const process=await tool('process_get');check('actual-worker-pid-bound',process.result.pid===metadata.pid);
  const raw=new Transport(config);await raw.open();try{
    const wrong=generateKeyPairSync('ec',{namedCurve:'prime256v1'}).privateKey.export({format:'pem',type:'pkcs8'});
    const request=envelope({...config,privateKey:wrong},'call',config.session,JSON.stringify({protocol:1,requestId:randomUUID(),method:'session.get',params:{}}));
    const denial=await raw.send(request);check('engine-rejects-unpaired-client-signature',!denial.ok&&denial.error.includes('SIGNATURE_REJECTED'));
  }finally{await raw.close();}
  const observeArgs={operationId:randomUUID(),selectors:[{id:'counter',kind:'java',className:'dev.tripoder.previewguest.CounterActivity',methodName:'formatCount',argumentTypes:['int']}]};
  const observed=await tool('hook_observe',observeArgs);const replay=await tool('hook_observe',observeArgs);check('same-operation-same-payload-returns-identical-receipt',JSON.stringify(observed)===JSON.stringify(replay));
  const changed=await tool('hook_observe',{...observeArgs,selectors:[{...observeArgs.selectors[0],id:'changed'}]},false);check('same-operation-different-payload-rejected',!changed.ok&&changed.error.includes('CONTENT_MISMATCH'));
  const prepared=await tool('hook_prepare',{operationId:randomUUID(),evidenceId:observed.result.evidence.id,source:metadata.source});
  check('draft-prepared-without-activation',prepared.result.state==='PREPARED');
  const early=await tool('hook_activate',{operationId:randomUUID(),token:prepared.result.token,sourceSha256:prepared.result.sourceSha256},false);check('phone-review-required-on-device',!early.ok&&early.error.includes('PHONE_REVIEW_REQUIRED'));
  const denied=await tool('operation_get',{operationId:early.operationId});check('denied-activation-has-durable-failed-receipt',denied.result.state==='FAILED');
  await milestone('reviewed');const approval=await tool('session_get');check('phone-approved-exact-source-hash',approval.result.approvedSourceShaSha256===prepared.result.sourceSha256||approval.result.approvedSourceSha256===prepared.result.sourceSha256);
  const active=await tool('hook_activate',{operationId:randomUUID(),token:prepared.result.token,sourceSha256:prepared.result.sourceSha256});check('engine-acknowledges-active',active.result.state==='ACTIVE');
  const dexSearch=await tool('dex_search_classes',{packagePrefix:'dev.tripoder.previewguest',limit:20});check('dex-search-discovers-guest-class',Array.isArray(dexSearch.result.classes)&&dexSearch.result.classes.includes('dev.tripoder.previewguest.CounterActivity'));
  const dexInspect=await tool('dex_inspect_class',{className:'dev.tripoder.previewguest.CounterActivity'});check('dex-inspect-lists-guest-methods',Array.isArray(dexInspect.result.methods)&&dexInspect.result.methods.some(m=>m.methodName==='formatCount'));
  const diag=await tool('crash_diagnostics_get',{});check('crash-diagnostics-returns-bounded-list',Array.isArray(diag.result.failures));
  const events=await tool('hook_events',{sinceSeq:0});check('hook-events-returns-bounded-cursor',Array.isArray(events.result.events)&&typeof events.result.nextCursor==='number');
  await milestone('hits');const status=await tool('hook_status');check('two-actual-ui-method-hits-zero-errors',status.result.hits===2&&status.result.errors===0);
  const uiDump=await tool('ui_dump_hierarchy',{includeInvisible:false});check('ui-dump-returns-window-tree',Array.isArray(uiDump.result.activities)&&uiDump.result.activities.length>=1&&Array.isArray(uiDump.result.activities[0].nodes)&&uiDump.result.activities[0].nodes.length>=1);
  if(mode==='disconnect'){
    child.stdin.end();const closed=await milestone('contact-closed');check('engine-expired-contact-and-closed',closed.state==='CLOSED'&&closed.reason==='CONTACT_EXPIRED');
    const reconcile=new Transport(config);await reconcile.open();try{
      const receipt=await reconcile.call('session.get',{},randomUUID());check('signed-read-can-reconcile-closed-session',receipt.ok&&receipt.result.state==='CLOSED');
      const denied=await reconcile.call('hook.observe',{selectors:observeArgs.selectors},randomUUID());check('revoked-session-refuses-new-hook',!denied.ok&&denied.error.includes('EXPIRED_OR_REVOKED'));
    }finally{await reconcile.close();}
    const confirmation=resolve(dirname(metadataFile),'contact-confirmed.json');await writeFile(confirmation,JSON.stringify({runId:metadata.runId,phase:'contact-confirmed'}));await exec('adb',['-s',config.serial,'push',confirmation,`/sdcard/Android/data/dev.tripoder.engine/files/agent-${metadata.runId}-contact-confirmed.json`],{timeout:10000});passed=true;
  }else{
  const disabled=await tool('hook_disable',{operationId:randomUUID(),revision:status.result.revision});check('engine-acknowledges-disabled',disabled.result.state==='DISABLED');
  await milestone('restored');check('actual-original-ui-restored',true);
  const restored=await tool('hook_status');check('runtime-original-state-idle',restored.result.state==='IDLE');
  const closeOperation=randomUUID(),close=await tool('session_close',{operationId:closeOperation});check('revoke-accepted',close.result.state==='CLOSING'||close.result.state==='CLOSED');
  let closed;for(let i=0;i<30;i++){closed=await tool('session_get');if(closed.result.state==='CLOSED')break;await new Promise(resolve=>setTimeout(resolve,200));}
  check('revoke-confirmed-closed',closed.result.state==='CLOSED');
  // The device must keep the endpoint alive until this client receives the final
  // signed CLOSED receipt. Server-side closure alone does not prove delivery.
  const confirmation=resolve(dirname(metadataFile),'close-confirmed.json');
  await writeFile(confirmation,JSON.stringify({runId:metadata.runId,phase:'close-confirmed',session:config.session,operationId:closeOperation,state:closed.result.state}));
  const phoneConfirmation=`/sdcard/Android/data/dev.tripoder.engine/files/agent-${metadata.runId}-close-confirmed.json`;
  await exec('adb',['-s',config.serial,'push',confirmation,phoneConfirmation+'.pending'],{timeout:10000});
  await exec('adb',['-s',config.serial,'shell','mv',phoneConfirmation+'.pending',phoneConfirmation],{timeout:10000});passed=true;
  }
}finally{
  child.stdin.end();if(child.exitCode===null)await new Promise(resolve=>{const timer=setTimeout(()=>{child.kill();resolve();},5000);child.on('exit',()=>{clearTimeout(timer);resolve();});});
  const redact=(key,value)=>key==='token'&&typeof value==='string'?`sha256:${createHash('sha256').update(value).digest('hex')}`:value;
  await writeFile(evidenceFile,JSON.stringify({schema:1,passed,mode,client:'tripoder-acceptance-client',sentinelClientVerified:false,modelToolUseVerified:false,checks,transcript,stderr},redact,2)+'\n');
}
console.log(JSON.stringify({passed,checks:checks.length}));
