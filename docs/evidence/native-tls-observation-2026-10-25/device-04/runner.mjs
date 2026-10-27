/** Owned guest UI -> custom HTTP -> real mTLS. MCP observes; never sends the HTTP request. */
import {Transport,loadConfig,MCP_VERSION} from '../../agent/transport.mjs';
import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,i,args)=>i%2?out:[...out,[key,args[i+1]]],[]));
if(!options['--evidence'])throw Error('--evidence required');
const directory=resolve(options['--evidence']);await mkdir(directory,{recursive:false});
const transport=new Transport(await loadConfig(resolve(options['--connection']??resolve(process.env.HOME,'.tripoder/tripoder-connection.json'))));
const report={runnerSha256:createHash('sha256').update(await readFile(import.meta.filename)).digest('hex'),passed:false,scope:'owned-native-openssl-mtls-buffer-observation',mode:'thick',checks:[],calls:[],runs:{},adbUsed:false,arbitraryTlsCoverage:false};
const space='native-mtls-'+randomUUID().slice(0,12),pkg='dev.tripoder.nativetlsfixture';
let target,processTarget;
const save=()=>writeFile(resolve(directory,'report.json'),JSON.stringify(report,null,2)+'\n');
function check(name,ok){report.checks.push({name,passed:!!ok});assert.ok(ok,name);}
async function call(name,args){
  assert.notEqual(name,'http_send_request');
  const receipt=await transport.tool(name,args);report.calls.push({name,action:args.action,operationId:args.operationId,ok:receipt.ok,error:receipt.ok?undefined:receipt.error});await save();
  if(!receipt.ok)throw Error(name+':'+(receipt.error??receipt.errorCode??receipt.state));return receipt.result;
}
const op=()=>randomUUID(),pause=ms=>new Promise(done=>setTimeout(done,ms));
async function summary(){return JSON.parse((await call('guest_invoke',{operationId:op(),target:processTarget,invocation:{className:pkg+'.Main',methodName:'summary',argumentTypes:[],arguments:[]}})).result);}
async function run(label){
  const previous=await summary();
  await call('ui_dispatch_action',{operationId:op(),target:processTarget,action:{kind:'clickText',text:'Run native mTLS'}});
  const end=Date.now()+45000;let result;
  while(Date.now()<end){result=await summary();if(result.passed===false)throw Error(label+':'+result.error);if(result.passed&&result.runId!==previous.runId)break;await pause(300);}
  check(label+'-real-mtls-concurrent-exact-exchanges',result?.passed&&result.runId!==previous.runId&&result.protocol==='TLSv1.3'&&result.clientAuthenticated&&result.concurrentConnections===2&&result.exchanges.length===2);
  report.runs[label]=result;await save();return result;
}
async function artifacts(){
  const rows=[];let cursor=0;do{const page=await call('artifact_query',{action:'list',processGeneration:processTarget.processGeneration,cursor,limit:32});rows.push(...page.artifacts);cursor=page.nextCursor;}while(cursor!==-1);return rows;
}
async function readArtifact(row){
  let offset=0;const chunks=[];
  while(true){const page=await call('artifact_query',{action:'read',processGeneration:processTarget.processGeneration,artifactId:row.artifactId,offset,length:16384});const bytes=Buffer.from(page.data,'base64');assert.equal(page.nextOffset,offset+bytes.length);chunks.push(bytes);offset=page.nextOffset;if(page.eof)break;assert.ok(bytes.length);}
  const bytes=Buffer.concat(chunks);check('artifact-integrity-'+row.tag,createHash('sha256').update(bytes).digest('hex')===row.sha256&&bytes.length===row.size);return JSON.parse(bytes.toString('utf8'));
}
try{
  const initialized=await transport.rpc({jsonrpc:'2.0',id:op(),method:'initialize',params:{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'owned-mtls-observation',version:'1'}}});assert.ok(initialized.result);
  await transport.rpc({jsonrpc:'2.0',method:'notifications/initialized',params:{}});
  await call('space_manage',{action:'create',operationId:op(),space,name:'Native mTLS QA'});
  report.space=space;
  const apk=resolve(options['--fixture']??'android/compatibility-test/build/native-tls-fixture/native-tls-fixture.apk');
  report.apkSha256=createHash('sha256').update(await readFile(apk)).digest('hex');
  const importId=op();report.importOperationId=importId;await save();
  const imported=await transport.importApks({operationId:importId,space,expectedGeneration:'',apkPaths:[apk]});check('owned-apk-imported',imported.ok);
  target={space,package:pkg,installGeneration:imported.result.generation};report.target=target;
  await call('application_manage',{action:'set_mode',operationId:op(),target,mode:'thick'});
  const configuration=await call('probes_query',{action:'configuration',target});
  const started=await call('process_start',{operationId:op(),target,component:'',probeRevision:configuration.revision});
  processTarget={...target,processGeneration:started.processGeneration};report.process=processTarget;
  // A successful launch receipt is not proof that the Activity is ready.
  let ready=false;
  for(let i=0;i<40;i++){const tree=await call('process_inspect',{action:'ui_tree',target:processTarget,includeInvisible:false});if(JSON.stringify(tree).includes('Run native mTLS')){ready=true;break;}await pause(250);}
  check('guest-ui-ready',ready);await run('baseline');
  const exports=await call('code_query',{action:'native_exports',target:processTarget,moduleName:'libownednativetls.so',symbolPrefix:'SSL_',limit:100});
  report.exports=exports;
  const buildId=exports.exports?.[0]?.buildId;
  assert.ok(buildId,'native export Build ID required');
  const spec=(id,symbol,argumentTypes,returnType)=>({id,kind:'native',moduleName:'libownednativetls.so',symbol,argumentTypes,returnType,buildId,signatureSource:'confirmed'});
  const selectors=[spec('read','SSL_read',['uint64','uint64','int32'],'int32'),spec('write','SSL_write',['uint64','uint64','int32'],'int32'),spec('open','SSL_new',['uint64'],'uint64'),spec('close','SSL_free',['uint64'],'void'),spec('probe','owned_buffer_case',['uint64','uint64','int32','int32'],'int32')];
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'native-mtls-observation',target,params:{},setup(ctx){
    ctx.native.function('libownednativetls.so','owned_buffer_case',{argumentTypes:['uint64','uint64','int32','int32'],returnType:'int32',buildId:'${buildId}'}).after(({args,result})=>{ctx.debug.emitEvent('buffer.probe',{id:String(args[0]),buffer:ctx.native.captureBuffer(1,2,16)});return result;});
    ctx.native.function('libownednativetls.so','SSL_new',{argumentTypes:['uint64'],returnType:'uint64',buildId:'${buildId}'}).after(({result})=>{ctx.debug.emitEvent('tls.open',String(result));return result;});
    ctx.native.function('libownednativetls.so','SSL_free',{argumentTypes:['uint64'],returnType:'void',buildId:'${buildId}'}).before(({args})=>{ctx.debug.emitEvent('tls.close',String(args[0]));return args;});
    ctx.native.function('libownednativetls.so','SSL_write',{argumentTypes:['uint64','uint64','int32'],returnType:'int32',buildId:'${buildId}'}).after(({args,result})=>{const data={connection:String(args[0]),direction:'write',buffer:ctx.native.captureBuffer(1,2,1024)};ctx.debug.emitEvent('tls.bytes',data);ctx.debug.captureEvidence('tls.bytes',data);return result;});
    ctx.native.function('libownednativetls.so','SSL_read',{argumentTypes:['uint64','uint64','int32'],returnType:'int32',buildId:'${buildId}'}).after(({args,result})=>{const data={connection:String(args[0]),direction:'read',buffer:ctx.native.captureBuffer(1,2,1024)};ctx.debug.emitEvent('tls.bytes',data);ctx.debug.captureEvidence('tls.bytes',data);return result;});
  }});`;
  report.sourceSha256=createHash('sha256').update(source).digest('hex');await writeFile(resolve(directory,'probe.ts'),source);
  const observed=await call('hook_observe',{operationId:op(),target:processTarget,selectors});
  const prepared=await call('hook_prepare',{operationId:op(),target:processTarget,evidenceId:observed.evidence.id,source});
  check('exact-source-prepared',prepared.sourceSha256===report.sourceSha256);
  await call('hook_activate',{operationId:op(),target:processTarget,token:prepared.token,sourceSha256:prepared.sourceSha256});
  const active=await run('observed');
  const events=await call('events_query',{action:'hook',target:processTarget,sinceSeq:0});report.events=events;
  check('no-dropped-events',events.droppedEvents===0&&events.nextCursor===events.events.length);
  const probes=events.events.filter(e=>e.tag==='buffer.probe').map(e=>e.payload);
  assert.deepEqual(probes.map(p=>p.buffer.state),['COMPLETE','COMPLETE','INVALID_LENGTH','UNAVAILABLE','NO_DATA','NO_DATA','UNAVAILABLE']);
  assert.equal(probes[0].buffer.hex,'414141');assert.equal(probes[1].buffer.hex,'41'.repeat(16));assert.equal(probes[1].buffer.truncated,true);
  for(const probe of probes.slice(2)){assert.equal(probe.buffer.hex,'');assert.equal(probe.buffer.capturedBytes,0);}
  check('real-memory-bounds-truncation-failure-and-errno',true);
  const streams=new Map(),closed=new Set();let empty=0,partial=0;
  for(const event of events.events){
    if(event.tag==='tls.open'){assert.notEqual(event.payload,'0');assert.ok(!streams.has(event.payload),'no reused pointer within this fixture run');streams.set(event.payload,{read:[],write:[]});}
    else if(event.tag==='tls.close'){assert.ok(streams.has(event.payload));closed.add(event.payload);}
    else if(event.tag==='tls.bytes'){
      const {connection,direction,buffer}=event.payload;assert.ok(streams.has(connection)&&!closed.has(connection));
      if(buffer.state==='NO_DATA'){empty++;assert.equal(buffer.hex,'');assert.equal(buffer.capturedBytes,0);continue;}
      assert.equal(buffer.state,'COMPLETE');assert.equal(buffer.truncated,false);
      const bytes=Buffer.from(buffer.hex,'hex');assert.equal(bytes.length,buffer.capturedBytes);assert.equal(bytes.length,Number(buffer.result));
      if(Number(buffer.result)<Number(buffer.capacity))partial++;
      streams.get(connection)[direction].push(bytes);
    }
  }
  check('two-concurrent-native-tls-lifetimes',streams.size===2&&closed.size===2);
  check('retryable-reads-copy-no-uninitialized-bytes',empty===2);
  check('partial-read-uses-actual-result',partial>0);
  const actual=[...streams.values()].map(s=>({request:Buffer.concat(s.write).toString('ascii'),response:Buffer.concat(s.read).toString('ascii')}));
  assert.deepEqual(actual.sort((a,b)=>a.request.localeCompare(b.request)),active.exchanges.sort((a,b)=>a.request.localeCompare(b.request)));
  check('concurrent-request-response-reassembly-exact',true);
  report.reassembled=actual;
  const status=await call('process_inspect',{action:'hook_status',target:processTarget});report.hook={hits:status.hits,errors:status.errors,revision:status.revision};
  check('native-original-calls-no-hook-errors',status.hits>20&&status.errors===0);
  const rows=(await artifacts()).filter(row=>row.kind==='hook-evidence'&&row.tag==='tls.bytes');
  check('all-native-buffers-persisted',rows.length===events.events.filter(e=>e.tag==='tls.bytes').length);
  for(const row of rows){assert.equal(row.sourceSha256,report.sourceSha256);assert.equal(row.installGeneration,target.installGeneration);}
  const evidence=await readArtifact(rows[0]);assert.ok(evidence.payload.buffer);report.artifacts=rows;
  await call('hook_disable',{operationId:op(),target:processTarget,revision:status.revision});await run('disabled');
  const after=await call('events_query',{action:'hook',target:processTarget,sinceSeq:0});check('disabled-observer-no-new-events',after.events.length===events.events.length);
  await call('application_stop',{operationId:op(),target});report.processStopped=true;
  const retained=await readArtifact(rows[0]);assert.deepEqual(retained.payload,evidence.payload);check('evidence-survives-process-stop',true);
  const persisted=[];for(const row of rows)persisted.push((await readArtifact(row)).payload);
  assert.deepEqual(persisted.map(p=>JSON.stringify(p)).sort(),events.events.filter(e=>e.tag==='tls.bytes').map(e=>JSON.stringify(e.payload)).sort());
  check('all-persisted-bytes-match-events-after-stop',true);
  check('no-agent-http-send',report.calls.every(c=>c.name!=='http_send_request'));
  report.passed=true;
}catch(error){report.failure={message:error.message,dispatched:error.dispatched};process.exitCode=1;}
finally{await save();await transport.close();console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,failure:report.failure,evidence:directory}));}
