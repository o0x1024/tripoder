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
const report={passed:false,scope:'owned-custom-http-mtls-semantic-observation',mode:'thick',checks:[],calls:[],runs:{},adbUsed:false,arbitraryTlsCoverage:false};
const space='mtls-'+randomUUID().slice(0,12),pkg='dev.tripoder.mtlsfixture';
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
  await call('ui_dispatch_action',{operationId:op(),target:processTarget,action:{kind:'clickText',text:'Run owned mTLS'}});
  const end=Date.now()+45000;let result;
  while(Date.now()<end){result=await summary();if(result.passed===false)throw Error(label+':'+result.error);if(result.passed&&result.runId!==previous.runId)break;await pause(300);}
  check(label+'-real-mtls-and-rejection-controls',result?.passed&&result.runId!==previous.runId&&result.protocol==='TLSv1.3'&&result.clientAuthenticated&&result.missingClientRejected&&result.untrustedServerRejected&&result.exactRequest&&result.exactResponse);
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
  await call('space_manage',{action:'create',operationId:op(),space,name:'Owned mTLS observation'});
  report.space=space;
  const apk=resolve(options['--fixture']??'android/compatibility-test/build/mtls-fixture/mtls-fixture.apk');
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
  for(let i=0;i<40;i++){const tree=await call('process_inspect',{action:'ui_tree',target:processTarget,includeInvisible:false});if(JSON.stringify(tree).includes('Run owned mTLS')){ready=true;break;}await pause(250);}
  check('guest-ui-ready',ready);await run('baseline');
  const selector={id:'exchange',kind:'java',className:pkg+'.OwnedHttp',methodName:'exchange',argumentTypes:['java.lang.String']};
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'owned-mtls-observation',target,params:{},setup(ctx){
    ctx.java.method('${pkg}.OwnedHttp','exchange',['java.lang.String'])
      .before(({args})=>{ctx.debug.emitEvent('mtls.attempt',args[0]);return args;})
      .after(({args,result})=>{ctx.debug.emitEvent('mtls.exchange',{request:args[0],response:result});ctx.debug.captureEvidence('mtls.exchange',{request:args[0],response:result});return result;});
  }});`;
  report.sourceSha256=createHash('sha256').update(source).digest('hex');await writeFile(resolve(directory,'probe.ts'),source);
  const observed=await call('hook_observe',{operationId:op(),target:processTarget,selectors:[selector]});
  const prepared=await call('hook_prepare',{operationId:op(),target:processTarget,evidenceId:observed.evidence.id,source});
  check('exact-source-prepared',prepared.sourceSha256===report.sourceSha256);
  await call('hook_activate',{operationId:op(),target:processTarget,token:prepared.token,sourceSha256:prepared.sourceSha256});
  const active=await run('observed');
  const events=await call('events_query',{action:'hook',target:processTarget,sinceSeq:0});report.events=events;
  const attempts=events.events.filter(e=>e.tag==='mtls.attempt'),exchanges=events.events.filter(e=>e.tag==='mtls.exchange');
  check('attempts-distinct-from-completed-exchanges',attempts.length===3&&exchanges.length===1);
  const body='owned-request-'+active.runId,response='owned-response-'+active.runId;
  const expected={request:`POST /owned/${active.runId} HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n${body}`,response:`HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n${response}`};
  check('captured-original-request-response',JSON.stringify(exchanges[0].payload)===JSON.stringify(expected));
  const status=await call('process_inspect',{action:'hook_status',target:processTarget});report.hook={hits:status.hits,errors:status.errors,revision:status.revision};
  check('three-real-calls-no-hook-errors',status.hits===3&&status.errors===0);
  const rows=(await artifacts()).filter(row=>row.kind==='hook-evidence'&&row.tag==='mtls.exchange');check('one-durable-exchange',rows.length===1);
  check('artifact-bound-to-source-and-installation',rows[0].sourceSha256===report.sourceSha256&&rows[0].installGeneration===target.installGeneration);
  const evidence=await readArtifact(rows[0]);assert.deepEqual(evidence.payload,expected);report.artifacts=rows;
  await call('hook_disable',{operationId:op(),target:processTarget,revision:status.revision});await run('disabled');
  const after=await call('events_query',{action:'hook',target:processTarget,sinceSeq:0});check('disabled-observer-no-new-events',after.events.length===events.events.length);
  await call('application_stop',{operationId:op(),target});report.processStopped=true;
  const retained=await readArtifact(rows[0]);assert.deepEqual(retained.payload,expected);check('evidence-survives-process-stop',true);
  check('no-agent-http-send',report.calls.every(c=>c.name!=='http_send_request'));
  report.passed=true;
}catch(error){report.failure={message:error.message,dispatched:error.dispatched};process.exitCode=1;}
finally{await save();await transport.close();console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,failure:report.failure,evidence:directory}));}
