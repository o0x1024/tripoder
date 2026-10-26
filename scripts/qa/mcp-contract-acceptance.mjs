// Physical-device acceptance through the authenticated production MCP endpoint.
// Never replays mutations; request metadata contains no credentials or source bodies.
import {Transport,loadConfig,MCP_VERSION} from '../../agent/transport.mjs';
import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const options=Object.fromEntries(process.argv.slice(2).reduce((out,value,index,args)=>index%2?out:[...out,[value,args[index+1]]],[]));
const directory=resolve(options['--evidence']),mode=options['--mode'];
if(!['owned','target'].includes(mode))throw Error('--mode owned|target required');
await mkdir(directory,{recursive:true});
const connection=options['--connection']??resolve(process.env.HOME,'.tripoder/tripoder-connection.json');
const transport=new Transport(await loadConfig(connection));
const report={mode,passed:false,checks:[],calls:[],deviceExecuted:true,sentinelDesktopVerified:false,runnerSha256:createHash('sha256').update(await readFile(import.meta.filename)).digest('hex'),startedAt:new Date().toISOString()};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const check=(name,ok,details)=>{report.checks.push({name,passed:!!ok,...(details?{details}:{})});if(!ok)throw Error(name);};
async function rpc(method,params){const response=await transport.rpc({jsonrpc:'2.0',id:randomUUID(),method,params});if(response?.error)throw Error(response.error.message);return response?.result;}
async function call(name,args,required=true){
  const receipt=await transport.tool(name,args);report.calls.push({tool:name,action:typeof args.action==='string'?args.action:undefined,operationId:args.operationId,ok:receipt.ok,state:receipt.state,errorCode:receipt.errorCode,issues:receipt.issues,error:receipt.ok?undefined:receipt.error});
  await save();if(required&&!receipt.ok)throw Error(name+':'+(receipt.errorCode??receipt.error??receipt.state));return required?receipt.result:receipt;
}
async function save(){await writeFile(resolve(directory,'report.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600});}
async function installations(){return (await call('runtime_query',{action:'applications'})).installations;}
const installTarget=row=>({space:row.space,package:row.package,installGeneration:row.installGeneration});
async function targets(){return (await call('application_query',{action:'targets'})).targets;}
async function manifest(target){
  const entries=[];let cursor=0;do{const page=await call('application_query',{action:'manifest',target,cursor,limit:16});check('manifest-install-generation',page.target.installGeneration===target.installGeneration);entries.push(...page.declarations);cursor=page.nextCursor;}while(cursor!==-1);
  return entries;
}
async function artifacts(generation){let cursor=0;const records=[];do{const page=await call('artifact_query',{action:'list',processGeneration:generation,cursor,limit:32});records.push(...page.artifacts);cursor=page.nextCursor;}while(cursor!==-1);return records;}
async function snapshot(generation,artifact){
  const chunks=[];let offset=0,finished=false;
  while(!finished){const value=await call('artifact_query',{action:'read',processGeneration:generation,artifactId:artifact.artifactId,offset,length:16384});
    const bytes=Buffer.from(value.data,'base64');if(value.nextOffset!==offset+bytes.length||!value.eof&&value.nextOffset<=offset)throw Error('SNAPSHOT_CHUNK_PROGRESS_INVALID');chunks.push(bytes);offset=value.nextOffset;finished=value.eof;
  }
  const bytes=Buffer.concat(chunks);check('snapshot-sha256',bytes.length===artifact.size&&createHash('sha256').update(bytes).digest('hex')===artifact.sha256);return JSON.parse(bytes.toString('utf8'));
}
async function postmortem(target,sourceSha256){
  const processes=[];let cursor=0;do{const page=await call('application_query',{action:'process_history',target,cursor,limit:32});processes.push(...page.processes);cursor=page.nextCursor;}while(cursor!==-1);
  const captures=[];for(const process of processes){const items=await artifacts(process.processGeneration);for(const item of items.filter(a=>a.kind==='hook-evidence'&&a.installGeneration===target.installGeneration&&a.space===target.space&&a.package===target.package&&a.sourceSha256===sourceSha256)){
    const data=await snapshot(process.processGeneration,item);captures.push({processGeneration:process.processGeneration,processState:process.state,artifactId:item.artifactId,tag:item.tag,sha256:item.sha256,sourceSha256:item.sourceSha256,causes:data.payload?.causes?.map(c=>({className:c.className,frames:c.frames,messageLength:c.message?.length??0})),stack:Array.isArray(data.payload)?data.payload:undefined});
  }}return {processes,captures};
}
async function configure(target,selectors,source){
  const previous=await call('probes_query',{action:'configuration',target});
  const next=await call('probes_control',{action:'configure',operationId:randomUUID(),target,expectedRevision:previous.revision,configuration:{enabled:true,discoverLoaders:false,captureDexOnLoad:false,processName:'',startupHook:{selectors,source}}});
  return next.revision;
}
async function disablePlan(target){const previous=await call('probes_query',{action:'configuration',target});await call('probes_control',{action:'configure',operationId:randomUUID(),target,expectedRevision:previous.revision,configuration:{enabled:false}});}
async function start(target,probeRevision){return call('process_start',{operationId:randomUUID(),target,component:'',probeRevision},false);}
async function waitHook(target,timeout=20000){
  const deadline=Date.now()+timeout;let last;while(Date.now()<deadline){last=await call('process_inspect',{action:'hook_status',target},false);if(last.ok&&last.result.state==='ACTIVE')return last.result;if(!(await targets()).some(t=>t.processGeneration===target.processGeneration))break;await sleep(300);}throw Error('HOOK_NOT_ACTIVE:'+JSON.stringify(last));
}
async function invoke(target,className,methodName,required=true){return call('guest_invoke',{operationId:randomUUID(),target,invocation:{className,methodName,argumentTypes:[],arguments:[]}},required);}
function selector(id,className,methodName,argumentTypes){return {id,kind:'java',className,methodName,argumentTypes};}

try{
  const initialized=await rpc('initialize',{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'tripoder-contract-acceptance',version:'3'}});report.serverVersion=initialized.serverInfo.version;
  await transport.rpc({jsonrpc:'2.0',method:'notifications/initialized',params:{}});
  const catalog=(await rpc('tools/list',{})).tools;check('contract-v3',catalog.every(t=>t._meta?.['dev.tripoder/contractVersion']===3));
  check('no-old-manifest-tool',!catalog.some(t=>t.name==='manifest_inspect'));
  const space=options['--space']??'default';
  if(mode==='owned'&&!(await call('runtime_query',{action:'spaces'})).spaces.some(s=>s.space===space))await call('space_manage',{action:'create',operationId:randomUUID(),space,name:'对象契约验收'});
  const pkg=mode==='owned'?'dev.tripoder.probefixture':options['--package']??'com.bocconnect.prd';let row=(await installations()).find(r=>r.package===pkg&&r.space===space);
  if(mode==='owned'){
    if(row){await call('application_stop',{operationId:randomUUID(),target:installTarget(row)});await call('application_remove',{operationId:randomUUID(),target:installTarget(row)});row=undefined;}
    const importOperationId=randomUUID();report.importOperationId=importOperationId;await save();
    const imported=await transport.importApks({operationId:importOperationId,space,expectedGeneration:row?.installGeneration??'',apkPaths:[resolve('android/product/build/code-probe-guest.apk')]});report.importReceipt=imported;
    check('owned-original-apk-import',imported.ok,{state:imported.state});row=(await installations()).find(r=>r.package===pkg&&r.space===space);
  }
  check('exact-installation-found',!!row);const target=installTarget(row);report.target=target;
  const before=await targets();if(before.some(t=>t.space===target.space&&t.package===pkg))await call('application_stop',{operationId:randomUUID(),target});
  const entries=await manifest(target);check('manifest-has-application',entries.some(e=>e.tag==='application'));check('manifest-does-not-start-process',!(await targets()).some(t=>t.space===target.space&&t.package===pkg));
  report.manifest={declarations:entries.length,permissions:entries.filter(e=>e.tag.startsWith('uses-permission')).length,components:entries.filter(e=>['activity','activity-alias','service','receiver','provider'].includes(e.tag)).length};
  const stale=await call('application_query',{action:'manifest',target:{...target,installGeneration:'stale-'+randomUUID()}},false);check('stale-install-rejected',!stale.ok&&stale.error.includes('INSTALL_GENERATION_CONFLICT'));
  const missingProcess=await call('process_inspect',{action:'hook_status',target},false);check('precise-process-identity-error',!missingProcess.ok&&missingProcess.dispatched===false&&missingProcess.issues.some(i=>i.path==='/target/processGeneration'&&i.code==='REQUIRED_PROPERTY_MISSING'));
  const storage=await call('application_query',{action:'storage',target,category:'files',limit:5});check('storage-installation-view',storage.consistency==='LIVE_FILES_NOT_ATOMIC_SNAPSHOT');
  const owner=mode==='owned'?'dev.tripoder.probefixture.ProbeApplication':'cn.swiftpass.bct.utils.CrashHandler';
  const selectors=[selector('caught',owner,'uncaughtException',['java.lang.Thread','java.lang.Throwable']),selector('handle',owner,'handleException',['java.lang.Throwable'])];
  if(mode==='owned')selectors.push(selector('echo',owner,'echoThrowable',['java.lang.Throwable']));
  else selectors.push(selector('watchdog','uyf.b','a',['java.lang.String']));
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'object-contract',target,params:{},setup(ctx){
    ctx.java.method(symbols.caught.className,'uncaughtException',['java.lang.Thread','java.lang.Throwable']).before(({args})=>{ctx.debug.captureEvidence('exception.caught',ctx.java.snapshotThrowable(args[1],{maxFrames:12,maxCauses:4}));ctx.debug.captureEvidence('exception.thread',ctx.java.snapshotThread(args[0]));return args;});
    ctx.java.method(symbols.handle.className,'handleException',['java.lang.Throwable']).before(({args})=>{ctx.debug.captureEvidence('exception.handle',ctx.java.snapshotThrowable(args[0],{maxFrames:12,maxCauses:4}));return args;});
    ${mode==='owned'?`ctx.java.method(symbols.echo.className,'echoThrowable',['java.lang.Throwable']).before(({args})=>args).after(({result})=>result);`:`ctx.java.method(symbols.watchdog.className,'a',['java.lang.String']).before(({args})=>{ctx.debug.captureEvidence('watchdog.stack',ctx.debug.captureJavaStack(12));return args;});`}
  }});`;
  report.sourceSha256=createHash('sha256').update(source).digest('hex');await writeFile(resolve(directory,'probe.ts'),source,{mode:0o600});
  const revision=await configure(target,selectors,source);report.probeRevision=revision;
  const started=await start(target,revision);report.start={ok:started.ok,state:started.state,error:started.error,operationId:started.operationId,result:started.result};
  if(!started.ok){if(started.operationId)report.startReconciliation=await call('operation_get',{operationId:started.operationId},false);report.postmortem=await postmortem(target,report.sourceSha256);report.targetAcceptance={businessStartupAccepted:false,objectHookOnTargetVerified:report.postmortem.captures.some(a=>a.tag.startsWith('exception.')),preservedOriginalUnknown:report.startReconciliation?.result?.state==='UNKNOWN'};throw Error('PROCESS_START_FAILED');}
  const active={...target,processGeneration:started.result.processGeneration};report.processGeneration=active.processGeneration;
  report.hook=await waitHook(active);check('object-signatures-activated',report.hook.state==='ACTIVE');
  if(mode==='owned'){
    let summary;for(let i=0;i<30;i++){summary=await invoke(active,owner,'objectSummary');if(summary.result.includes('calls=3'))break;await sleep(200);}
    check('original-once-object-identity-and-no-overrides',summary.result==='calls=3;identity=true;overrides=0',{summary:summary.result});
    const forbidden=await invoke(active,owner,'forbiddenObjectResult',false);check('json-invocation-rejects-object-return-before-call',!forbidden.ok&&forbidden.error.includes('INVOKE_JAVA_ABI_UNSUPPORTED'));
    const after=await invoke(active,owner,'objectSummary');check('rejected-object-return-has-no-guest-side-effect',after.result===summary.result);
    const missing=await call('code_query',{action:'resolved_class',target:active,className:'owned.DoesNotExist'},false);check('read-failure-is-unavailable',!missing.ok&&missing.state==='UNAVAILABLE');
    check('read-failure-does-not-lock-hook-control',(await call('process_inspect',{action:'modules',target:active})).modules instanceof Array);
    const captured=(await artifacts(active.processGeneration)).filter(a=>a.kind==='hook-evidence');check('explicit-snapshots-persisted',captured.length>=3);
    for(const item of captured){check('snapshot-source-and-install-binding',item.sourceSha256===report.sourceSha256&&item.installGeneration===target.installGeneration);const data=await snapshot(active.processGeneration,item);if(item.tag==='exception.caught'){check('real-Throwable-message',data.payload.causes[0].message==='owned-object-message');check('real-Throwable-cause-and-suppressed',data.payload.causes.length===2&&data.payload.causes[0].suppressed.length===1);}}
    await disablePlan(target);report.futureProbeDisabledBeforeFault=true;
    const death=await invoke(active,owner,'captureThenExit',false);report.intentionalExit={state:death.state,operationId:death.operationId};if(death.operationId)report.exitReconciliation=await call('operation_get',{operationId:death.operationId},false);
    await sleep(1000);check('owned-worker-exited',!(await targets()).some(t=>t.processGeneration===active.processGeneration));
    const retained=(await artifacts(active.processGeneration)).filter(a=>a.kind==='hook-evidence');check('snapshots-survive-worker-death',retained.length>=captured.length+3);await snapshot(active.processGeneration,retained.at(-1));
    report.artifacts=retained.map(a=>({artifactId:a.artifactId,tag:a.tag,sha256:a.sha256,sourceSha256:a.sourceSha256}));
  }else{
    await sleep(15000);const remaining=await targets();report.targetStillRunning=remaining.some(t=>t.processGeneration===active.processGeneration);
    const captured=(await artifacts(active.processGeneration)).filter(a=>a.kind==='hook-evidence');report.artifacts=[];
    for(const item of captured){const data=await snapshot(active.processGeneration,item);report.artifacts.push({artifactId:item.artifactId,tag:item.tag,sha256:item.sha256,sourceSha256:item.sourceSha256,causes:data.payload?.causes?.map(c=>({className:c.className,frames:c.frames,messageLength:c.message?.length??0}))});}
    report.targetAcceptance={toolMechanismPassed:true,businessStartupAccepted:false,exceptionSnapshots:captured.filter(a=>a.tag.startsWith('exception.')).length};
  }
  if(mode==='target')await call('application_stop',{operationId:randomUUID(),target});
  if(mode==='target')await disablePlan(target);report.passed=true;
}catch(error){report.failure={message:error.message,dispatched:error.dispatched};process.exitCode=1;}
finally{await save();await transport.close();console.log(JSON.stringify({mode,passed:report.passed,checks:report.checks.length,failure:report.failure,evidence:directory}));}
