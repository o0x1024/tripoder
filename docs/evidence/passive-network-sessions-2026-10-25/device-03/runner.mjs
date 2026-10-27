/** Physical-device passive capture sessions. UI drives all guest network requests. */
import {Transport,loadConfig,MCP_VERSION} from '../../agent/transport.mjs';
import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const args=Object.fromEntries(process.argv.slice(2).reduce((out,key,i,all)=>i%2?out:[...out,[key,all[i+1]]],[]));
if(!args['--evidence']||!args['--connection'])throw Error('--evidence and --connection required');
const directory=resolve(args['--evidence']);await mkdir(resolve(directory,'..'),{recursive:true});await mkdir(directory,{recursive:false});
const t=new Transport(await loadConfig(resolve(args['--connection']))),op=()=>randomUUID(),pause=ms=>new Promise(done=>setTimeout(done,ms));
const report={passed:false,scope:'physical-owned-guest-managed-passive-tls-capture',checks:[],runs:{},calls:[],captures:[],adbUsedForMcp:false,arbitraryTlsCoverage:false,runnerSha256:createHash('sha256').update(await readFile(import.meta.filename)).digest('hex')};
let target,processTarget;
const save=async()=>{const {calls,...summary}=report;await writeFile(resolve(directory,'calls.json'),JSON.stringify(calls,null,2)+'\n');await writeFile(resolve(directory,'report.json'),JSON.stringify({...summary,callsFile:'calls.json'},null,2)+'\n');};
function check(name,value){report.checks.push({name,passed:!!value});assert.ok(value,name);}
async function call(name,params,required=true){
  assert.notEqual(name,'http_send_request');const receipt=await t.tool(name,params);report.calls.push({name,action:params.action,operationId:params.operationId,ok:receipt.ok,outcome:receipt.outcome,state:receipt.state,error:receipt.ok?undefined:receipt.error});await save();
  if(required&&!receipt.ok)throw Error(name+':'+(receipt.error??receipt.state));return required?receipt.result:receipt;
}
async function summary(){return JSON.parse((await call('guest_invoke',{operationId:op(),target:processTarget,invocation:{className:'dev.tripoder.nativetlsfixture.Main',methodName:'summary',argumentTypes:[],arguments:[]}})).result);}
async function run(label){const before=await summary();await call('ui_dispatch_action',{operationId:op(),target:processTarget,action:{kind:'clickText',text:'Run native mTLS'}});
  const deadline=Date.now()+45000;let result;do{result=await summary();if(result.passed===false)throw Error(result.error);if(result.passed&&result.runId!==before.runId)break;await pause(200);}while(Date.now()<deadline);
  check(label+'-guest-mtls-exact-concurrent-exchanges',result?.passed&&result.runId!==before.runId&&result.protocol==='TLSv1.3'&&result.clientAuthenticated&&result.concurrentConnections===2);report.runs[label]=result;return result;
}
async function readArtifact(row){const chunks=[];let offset=0;while(true){const value=await call('artifact_query',{action:'read',processGeneration:processTarget.processGeneration,artifactId:row.artifactId,offset,length:16384});const bytes=Buffer.from(value.data,'base64');assert.equal(value.nextOffset,offset+bytes.length);offset=value.nextOffset;chunks.push(bytes);if(value.eof)break;assert.ok(bytes.length);}
  const data=Buffer.concat(chunks);assert.equal(createHash('sha256').update(data).digest('hex'),row.sha256);assert.equal(data.length,row.size);return JSON.parse(data.toString('utf8'));
}
try{
  const initialized=await t.rpc({jsonrpc:'2.0',id:op(),method:'initialize',params:{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'network-session-acceptance',version:'1'}}});assert.ok(initialized.result);await t.rpc({jsonrpc:'2.0',method:'notifications/initialized',params:{}});
  const catalog=await t.rpc({jsonrpc:'2.0',id:op(),method:'tools/list',params:{}});check('new-capture-tools-published',catalog.result.tools.some(tool=>tool.name==='network_control')&&catalog.result.tools.some(tool=>tool.name==='network_query'));
  const space='network-'+op().slice(0,12);report.space=space;await call('space_manage',{action:'create',operationId:op(),space,name:'Passive TLS session QA'});
  const apk=resolve(args['--fixture']??'android/compatibility-test/build/native-tls-fixture/native-tls-fixture.apk');report.fixtureSha256=createHash('sha256').update(await readFile(apk)).digest('hex');
  const importId=op();report.importOperationId=importId;await save();const imported=await t.importApks({operationId:importId,space,expectedGeneration:'',apkPaths:[apk]});assert.ok(imported.ok,JSON.stringify(imported));
  target={space,package:'dev.tripoder.nativetlsfixture',installGeneration:imported.result.generation};report.target=target;await call('application_manage',{action:'set_mode',operationId:op(),target,mode:'thick'});
  const config=await call('probes_query',{action:'configuration',target}),started=await call('process_start',{operationId:op(),target,component:'',probeRevision:config.revision});processTarget={...target,processGeneration:started.processGeneration};report.process=processTarget;
  let ready=false;for(let i=0;i<40;i++){const ui=await call('process_inspect',{action:'ui_tree',target:processTarget,includeInvisible:false});if(JSON.stringify(ui).includes('Run native mTLS')){ready=true;break;}await pause(250);}check('guest-ui-ready',ready);
  await run('baseline');
  const candidate=await call('network_query',{action:'inspect',target:processTarget,moduleName:'libownednativetls.so'});report.candidate=candidate;check('export-profile-is-candidate-requiring-confirmation',candidate.candidate&&candidate.signatureConfirmationRequired&&candidate.automaticAbiInference===false);
  const startArgs={action:'start',target:processTarget,moduleName:candidate.moduleName,buildId:candidate.buildId,profile:candidate.profile,signatureSource:'confirmed'};
  const wrong=await call('network_control',{...startArgs,operationId:op(),buildId:'wrong'},false);check('stale-build-id-known-rejection',!wrong.ok&&wrong.outcome==='REJECTED');
  const active=await call('network_control',{...startArgs,operationId:op()});report.captures.push(active);check('managed-capture-started',active.captureState==='ACTIVE'&&active.state==='ACTIVE'&&active.otherTlsImplementations==='NOT_OBSERVED');
  const blocked=await call('hook_discard',{operationId:op(),target:processTarget},false);check('managed-hook-ownership-enforced',!blocked.ok&&blocked.outcome==='REJECTED');
  const observed=await run('observed');
  const rows=[];let cursor=0;do{const page=await call('events_query',{action:'network',target:processTarget,captureId:active.captureId,sinceSeq:cursor,limit:1});check('passive-query-origin-and-cursor',page.origin==='GUEST_PASSIVE_TLS'&&page.captureId===active.captureId&&page.nextCursor>cursor);rows.push(...page.connections);cursor=page.nextCursor;if(!page.hasMore)break;}while(true);
  check('two-finalized-connection-lifetimes',rows.length===2&&new Set(rows.map(row=>row.connectionId)).size===2);
  const actual=rows.map(row=>{assert.equal(row.byteState,'COMPLETE');assert.equal(row.httpState,'COMPLETE');assert.deepEqual(row.reasons,[]);assert.equal(row.artifact.state,'COMPLETE');assert.equal(row.artifact.sourceSha256,active.sourceSha256);assert.equal(row.artifact.installGeneration,target.installGeneration);assert.ok(row.segments.length>2);return {request:Buffer.from(row.requestHex,'hex').toString('ascii'),response:Buffer.from(row.responseHex,'hex').toString('ascii')};});
  assert.deepEqual(actual.sort((a,b)=>a.request.localeCompare(b.request)),observed.exchanges.sort((a,b)=>a.request.localeCompare(b.request)));check('uniform-query-reassembly-matches-real-guest-and-server',true);
  report.connections=rows;
  const oldId=await call('events_query',{action:'network',target:processTarget,captureId:'wrong',sinceSeq:0,limit:1},false);check('wrong-capture-id-is-explicit-read-unavailable',!oldId.ok&&oldId.state==='UNAVAILABLE'&&oldId.error.includes('NETWORK_CAPTURE_ID_MISMATCH'));
  let stopped=await call('network_control',{action:'stop',operationId:op(),target:processTarget,captureId:active.captureId});for(let i=0;i<30&&stopped.captureState!=='STOPPED';i++){await pause(100);stopped=await call('network_query',{action:'status',target:processTarget});}check('capture-stopped-and-drained',stopped.captureState==='STOPPED');
  const after=await run('disabled'),unchanged=await call('events_query',{action:'network',target:processTarget,captureId:active.captureId,sinceSeq:cursor,limit:1});check('stopped-session-collects-no-later-traffic',unchanged.connections.length===0&&!unchanged.hasMore);
  const next=await call('network_control',{...startArgs,operationId:op()});report.captures.push(next);check('restart-allocates-new-capture-id',next.captureId!==active.captureId);await call('network_control',{action:'stop',operationId:op(),target:processTarget,captureId:next.captureId});
  await call('application_stop',{operationId:op(),target});report.processStopped=true;
  for(const row of rows){const artifact=await readArtifact(row.artifact);assert.equal(artifact.payload.captureId,active.captureId);assert.equal(artifact.payload.connectionId,row.connectionId);assert.equal(artifact.payload.requestHex,row.requestHex);assert.equal(artifact.payload.responseHex,row.responseHex);}check('all-session-evidence-survives-worker-stop-with-valid-sha',true);
  check('no-agent-sent-http',report.calls.every(row=>row.name!=='http_send_request'));report.passed=true;
}catch(error){report.failure={message:error.message,dispatched:error.dispatched};process.exitCode=1;}
finally{await save();await t.close();console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,failure:report.failure,evidence:directory}));}
