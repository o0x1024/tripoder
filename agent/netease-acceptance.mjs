/** Real application acceptance using public MCP tools. Application-specific
 * selectors belong to this test, never to the runtime implementation. Raw UI,
 * APK code and wire bytes stay in the ignored private evidence directory. */
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createInterface} from 'node:readline';
import {readFile,writeFile,mkdir,open} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {tools} from './tools.mjs';
const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,i,args)=>i%2?out:[...out,[key,args[i+1]]],[]));
const serial=options['--serial'],project=options['--project'],directory=resolve(options['--evidence']),inventory=JSON.parse(await readFile(options['--inventory'],'utf8'));
if(!serial||!project||inventory.package!=='com.netease.cloudmusic')throw Error('EXACT_NETEASE_TARGET_REQUIRED');
await mkdir(directory,{recursive:true});const privateDirectory=resolve('.tripoder-local','netease-acceptance-'+Date.now());await mkdir(privateDirectory,{mode:0o700,recursive:true});
const exec=promisify(execFile),transcript=[],checks=[],pending=new Map();let sequence=0,stderr='',target,originalPlan,currentGeneration,activeRevision;
const report={schema:1,package:inventory.package,apkSha256:inventory.apkSha256,serial,project,checks,passed:false,controlTransport:'real-stdio-mcp',sentinelDesktopVerified:false,startedAt:new Date().toISOString()};
const child=spawn(process.execPath,[resolve('agent/server.mjs'),'--serial',serial],{stdio:['pipe','pipe','pipe']});child.stderr.on('data',b=>stderr+=b);
createInterface({input:child.stdout}).on('line',line=>{const message=JSON.parse(line);transcript.push({direction:'response',message});const item=pending.get(message.id);if(item){clearTimeout(item.timer);pending.delete(message.id);item.resolve(message);}});
child.on('exit',code=>{for(const item of pending.values()){clearTimeout(item.timer);item.reject(Error('MCP_EXIT:'+code+':'+stderr));}pending.clear();});
async function rpc(method,params={}){const id=++sequence,message={jsonrpc:'2.0',id,method,params};transcript.push({direction:'request',message});const response=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_TIMEOUT:'+method));},30000);pending.set(id,{resolve,reject,timer});});child.stdin.write(JSON.stringify(message)+'\n');const value=await response;if(value.error)throw Error(JSON.stringify(value.error));return value.result;}
async function tool(name,args={},required=true){const schema=tools.find(t=>t.name===name)?.inputSchema;if(schema?.required.includes('target')){if(name==='guest_invoke'){args={...args,invocation:args.target};delete args.target;}args={...args,target:{space:target.space,package:target.package,installGeneration:target.installGeneration,...(schema.properties.target.required.includes('processGeneration')?{processGeneration:currentGeneration}:{})}};delete args.project;delete args.installGeneration;}const value=await rpc('tools/call',{name,arguments:args}),receipt=JSON.parse(value.content[0].text);if(required&&!receipt.ok)throw Error(name+':'+JSON.stringify(receipt));return required?receipt.result:receipt;}
function check(name,value,detail){assert.ok(value,name);checks.push({name,passed:true,...(detail===undefined?{}:{detail})});}
function progress(phase,detail={}){console.log(JSON.stringify({phase,...detail}));}
async function stop(){return tool('application_stop',{operationId:randomUUID(),project,installGeneration:target.installGeneration});}
async function configure(configuration){const prior=await tool('probes_get',{project});return tool('probes_configure',{operationId:randomUUID(),project,expectedRevision:prior.revision,configuration});}
async function start(revision){const at=Date.now(),value=await tool('process_start',{operationId:randomUUID(),project,component:'',probeRevision:revision});currentGeneration=value.processGeneration;return {...value,applicationReadyRoundTripMs:Date.now()-at};}
async function allDex(loader=''){const rows=[];let cursor='';do{const value=await tool('dex_list',{classLoaderId:loader,cursor,limit:64});rows.push(...value.dexFiles);cursor=value.nextCursor;}while(cursor);return rows;}
async function awaitArtifacts(ids){const end=Date.now()+20000;while(Date.now()<end){const rows=(await tool('artifacts_list',{processGeneration:currentGeneration})).artifacts;for(const id of ids){const row=rows.find(a=>a.artifactId===id);if(row?.state==='FAILED')throw Error('CAPTURE_FAILED:'+row.error);}if(ids.every(id=>rows.some(a=>a.artifactId===id&&a.state==='COMPLETE')))return rows;await new Promise(r=>setTimeout(r,100));}throw Error('CAPTURE_TIMEOUT');}
async function download(artifact,name){const hash=createHash('sha256'),file=await open(join(privateDirectory,name),'w',0o600);let offset=0;try{while(offset<artifact.size){const part=await tool('artifact_read',{processGeneration:artifact.processGeneration,artifactId:artifact.artifactId,offset,length:16384});assert.equal(part.offset,offset);assert.equal(part.sha256,artifact.sha256);const bytes=Buffer.from(part.data,'base64');assert.ok(bytes.length);await file.write(bytes);hash.update(bytes);offset=part.nextOffset;}await file.sync();}finally{await file.close();}const digest=hash.digest('hex');assert.equal(digest,artifact.sha256);return {bytes:offset,sha256:digest};}
const mathTarget={className:'com.netease.cloudmusic.utils.NeteaseMusicUtils',methodName:'F',argumentTypes:['float','float','float','float'],arguments:[0,0,3,4]};
try{
 await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'netease-real-device-acceptance',version:'2'}});child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
 check('discovers-generic-tools',(await rpc('tools/list')).tools.some(t=>t.name==='classloader_create'));
 const catalog=await tool('projects_list');assert.ok(catalog.applications.some(p=>p.project===project&&p.package===inventory.package));const installs=catalog.installations.filter(p=>p.package===inventory.package&&(!options['--space']||p.space===options['--space']));assert.equal(installs.length,1,'use --space to identify exactly one installation');target={...installs[0],installGeneration:installs[0].generation};check('exact-project-package',target.package===inventory.package);originalPlan=await tool('probes_get',{project});
 await stop();const baselinePlan=await configure({enabled:false});report.baselineStarts=[];
 for(let i=0;i<3;i++){
  const value=await start(baselinePlan.revision),probe=await tool('probe_status'),hook=await tool('hook_status');
  check('default-thin-no-probe-work-'+i,!probe.enabled&&probe.installedHooks===0&&probe.scans===0&&probe.pendingCaptures===0);
  check('default-thin-preserves-compilation-'+i,hook.executionPolicy.preserveCompilation&&hook.executionPolicy.claims===0&&hook.executionPolicy.fixedBindings===1021);
  check('same-exact-apk-'+i,hook.identity.target.apkSha256===inventory.apkSha256);report.versionCode=hook.identity.target.versionCode;report.baselineStarts.push(value.applicationReadyRoundTripMs);
  if(i===2){const loaders=(await tool('classloaders_list')).classLoaders;const root=loaders.find(l=>l.parentId===null);let cls='com.netease.cloudmusic.CloudMusicApplication';report.applicationHierarchy=[];
   for(let depth=0;depth<8&&!cls.startsWith('android.');depth++){const info=await tool('class_inspect',{classLoaderId:root.classLoaderId,className:cls});report.applicationHierarchy.push({className:info.className,superClass:info.superClass});if(info.methods.some(m=>m.methodName==='onCreate'&&m.argumentTypes.length===0&&m.returnType==='void')){report.lifecycleOwner=info.className;break;}cls=info.superClass;}
   check('runtime-hierarchy-resolves-real-onCreate',report.lifecycleOwner==='com.tencent.tinker.loader.app.TinkerApplication');
  }await stop();progress('baseline',{sample:i,ms:value.applicationReadyRoundTripMs});
 }
 const startupSource="import {definePlan} from '@abox/hook';export default definePlan({id:'netease-startup',target,params:{},setup(ctx){ctx.java.method('"+report.lifecycleOwner+"','onCreate',[]).before(()=>{ctx.debug.emitEvent('application.onCreate','before-original');return [];});}});";
 const configured=await configure({enabled:true,discoverLoaders:true,captureDexOnLoad:false,processName:target.package,maxDexBytes:16777216,maxTotalBytes:33554432,maxArtifacts:5,startupHook:{selectors:[{id:'onCreate',kind:'java',className:report.lifecycleOwner,methodName:'onCreate',argumentTypes:[]}],source:startupSource}});
 const analysis=await start(configured.revision);report.analysisProcess=analysis;const status=await tool('hook_status'),probe=await tool('probe_status');activeRevision=status.revision;
 check('startup-hook-natural-call',status.state==='ACTIVE'&&status.hits>=1&&status.errors===0);report.firstInvocation=status.firstInvocation;
 check('first-hit-before-application-created',['BEFORE_APPLICATION','APPLICATION_CONSTRUCTION','APPLICATION_ATTACHED'].includes(status.firstInvocation?.runtimePhase));
 check('analysis-still-preserves-thin',status.executionPolicy.preserveCompilation&&status.executionPolicy.claims===0&&status.executionPolicy.fixedBindings===1021&&probe.installedHooks===10);
 const events=await tool('runtime_events',{sinceSeq:0,limit:64}),created=events.events.find(e=>e.kind==='process.stage'&&e.detail.stage==='APPLICATION_CREATED');
 check('first-hit-monotonic-time-before-onCreate-complete',created&&BigInt(status.firstInvocation.elapsedRealtimeNanos)<BigInt(created.timeNanos));
 const loaders=(await tool('classloaders_list')).classLoaders,root=loaders.find(l=>l.parentId===null),dex=await allDex();report.naturalLoaders=loaders.map(l=>({type:l.type,origin:l.origin}));report.dexCount=dex.length;
 check('runtime-dex-inventory-matches-app-containers',dex.length===inventory.entries.length&&dex.every(d=>inventory.entries.some(e=>e.size===d.size)));
 check('dex-events-published-before-application-construction',events.events.filter(e=>e.kind==='dex.loaded'&&e.stage==='BEFORE_APPLICATION').length===inventory.entries.length);
 await tool('hook_disable',{operationId:randomUUID(),revision:activeRevision});activeRevision=null;
 const tinyEntry=inventory.entries.slice().sort((a,b)=>a.size-b.size)[0],tiny=dex.find(d=>d.size===tinyEntry.size),businessEntry=inventory.entries.find(e=>e.name===inventory.utilsDex),business=dex.find(d=>d.size===businessEntry.size);
 const childArgs={operationId:randomUUID(),dexId:tiny.dexId,parentClassLoaderId:root.classLoaderId},childLoader=await tool('classloader_create',childArgs);
 check('dynamic-child-observed-by-constructor-probe',childLoader.origin==='constructor'&&childLoader.classesInitialized===false);
 check('create-operation-reuses-same-loader',(await tool('classloader_create',childArgs)).classLoaderId===childLoader.classLoaderId);
 const childDex=await allDex(childLoader.classLoaderId);check('new-loader-has-real-runtime-dex',childDex.length===1&&childDex[0].size===tiny.size&&childDex[0].classLoaderId===childLoader.classLoaderId);report.controlledDynamicLoader={type:'dalvik.system.InMemoryDexClassLoader',origin:childLoader.origin,bytes:tiny.size};
 const childEvent=await tool('runtime_events',{sinceSeq:events.nextCursor,limit:64});check('dynamic-constructor-event-retains-target-generation',childEvent.events.some(e=>e.kind==='classloader.created'&&e.detail.classLoaderId===childLoader.classLoaderId&&e.detail.origin==='constructor'&&e.processGeneration===currentGeneration));
 const tinySnapshot=await tool('dex_snapshot',{operationId:randomUUID(),dexId:tiny.dexId}),childSnapshot=await tool('dex_snapshot',{operationId:randomUUID(),dexId:childDex[0].dexId});
 const before=await tool('guest_invoke',{operationId:randomUUID(),target:mathTarget});check('pure-local-original-angle',before.result===53);
 const observed=await tool('hook_observe',{operationId:randomUUID(),selectors:[{id:'angle',kind:'java',className:mathTarget.className,methodName:'F',argumentTypes:mathTarget.argumentTypes,classLoaderId:root.classLoaderId}]});
 const source="import {definePlan} from '@abox/hook';export default definePlan({id:'netease-angle',target,params:{},setup(ctx){ctx.java.method('com.netease.cloudmusic.utils.NeteaseMusicUtils','F',['float','float','float','float'],{classLoaderId:'"+root.classLoaderId+"'}).after(({args,result})=>{if(args[0]===0&&args[1]===0&&args[2]===3&&args[3]===4){const snapshot=ctx.code.snapshotDex('"+business.dexId+"');ctx.debug.emitEvent('angle.modified',{original:result,replacement:135,artifactId:snapshot.artifactId});return 135;}return result;});}});";
 const prepared=await tool('hook_prepare',{operationId:randomUUID(),evidenceId:observed.evidence.id,source});await tool('hook_activate',{operationId:randomUUID(),token:prepared.token,sourceSha256:prepared.sourceSha256});activeRevision=(await tool('hook_status')).revision;
 const modified=await tool('guest_invoke',{operationId:randomUUID(),target:mathTarget}),hook=await tool('hook_status'),hookEvents=await tool('hook_events',{sinceSeq:0});
 check('real-return-value-modified',modified.result===135&&hook.hits>=1&&hook.errors===0);const emitted=hookEvents.events.find(e=>e.tag==='angle.modified');check('callback-reports-actual-original-and-snapshot',emitted?.payload.original===53&&emitted.payload.replacement===135&&typeof emitted.payload.artifactId==='string');
 await tool('hook_disable',{operationId:randomUUID(),revision:activeRevision});activeRevision=null;const restored=await tool('guest_invoke',{operationId:randomUUID(),target:mathTarget});check('same-method-restored-after-disable',restored.result===before.result);report.returnHook={className:mathTarget.className,methodName:'F',argumentTypes:mathTarget.argumentTypes,arguments:mathTarget.arguments,before:before.result,during:modified.result,after:restored.result,hits:hook.hits,errors:hook.errors};
 const artifacts=await awaitArtifacts([tinySnapshot.artifactId,childSnapshot.artifactId,emitted.payload.artifactId]);report.snapshots=[];
 for(const [id,expected,name] of [[tinySnapshot.artifactId,tinyEntry,'root-runtime.dex'],[childSnapshot.artifactId,tinyEntry,'child-runtime.dex'],[emitted.payload.artifactId,businessEntry,'business-runtime.dex']]){
  const artifact=artifacts.find(a=>a.artifactId===id),result=await download(artifact,name);check('snapshot-digest-'+name,result.sha256===expected.sha256);report.snapshots.push({kind:'ART_DEX_MEMORY',file:name,apkEntry:expected.name,bytes:result.bytes,sha256:result.sha256});progress('snapshot',{file:name,bytes:result.bytes});
 }
 const stopped=await tool('probes_stop',{operationId:randomUUID()});check('probe-stop-removes-callbacks',!stopped.enabled&&stopped.installedHooks===0);await stop();
 check('completed-snapshots-survive-process-exit',(await tool('artifacts_list',{processGeneration:currentGeneration})).artifacts.filter(a=>a.state==='COMPLETE').length===3);
 const disabled=await configure({enabled:false});report.restoredStarts=[];
 for(let i=0;i<3;i++){
  const value=await start(disabled.revision),p=await tool('probe_status'),h=await tool('hook_status');
  check('restored-thin-no-analysis-work-'+i,!p.enabled&&p.installedHooks===0&&p.scans===0&&p.pendingCaptures===0&&h.state==='IDLE'&&h.executionPolicy.preserveCompilation&&h.executionPolicy.claims===0&&h.executionPolicy.fixedBindings===1021);report.restoredStarts.push(value.applicationReadyRoundTripMs);
  if(i<2)await stop();else report.finalProcess=value;progress('restored',{sample:i,ms:value.applicationReadyRoundTripMs});
 }
 const finalUi=await tool('ui_dump_hierarchy',{includeInvisible:false});check('real-main-activity-renders',finalUi.activities.some(a=>a.activity==='com.netease.cloudmusic.activity.MainActivity'&&a.nodes.some(n=>n.visible)));
 report.passed=true;
}catch(error){report.error=String(error);process.exitCode=1;}
finally{
 if(target&&originalPlan){try{const plan=await configure(originalPlan.configuration);if(!report.passed){await stop();report.recoveryProcess=await start(plan.revision);}report.originalConfigurationRestored=true;}catch(error){report.cleanupError=String(error);report.passed=false;process.exitCode=1;}}
 await writeFile(join(privateDirectory,'mcp-transcript.json'),JSON.stringify(transcript,null,2),{mode:0o600});
 const metadata=transcript.map(item=>({direction:item.direction,id:item.message.id,method:item.message.method,tool:item.message.params?.name,sha256:createHash('sha256').update(JSON.stringify(item.message)).digest('hex')}));await writeFile(join(directory,'mcp-metadata.json'),JSON.stringify(metadata,null,2));
 report.finishedAt=new Date().toISOString();report.privateEvidenceDirectory=privateDirectory;report.sourceSha256={};for(const name of ['netease-acceptance.mjs','server.mjs','tools.mjs','transport.mjs'])report.sourceSha256['agent/'+name]=createHash('sha256').update(await readFile(resolve('agent',name))).digest('hex');
 await writeFile(join(directory,'summary.json'),JSON.stringify(report,null,2));child.stdin.end();progress('complete',{passed:report.passed,checks:checks.length,error:report.error});
}
