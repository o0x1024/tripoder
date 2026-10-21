/** Focused real-device acceptance. Target-specific selectors and expected values are inputs, never runtime policy. */
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createInterface} from 'node:readline';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,i,args)=>i%2?out:[...out,[key,args[i+1]]],[]));
const config=JSON.parse(await readFile(options['--input'],'utf8')),directory=resolve(options['--evidence']);
const expectedActivities=config.expectedActivities||[config.expectedActivity];assert.ok(Array.isArray(expectedActivities)&&expectedActivities.length>0&&expectedActivities.every(value=>typeof value==='string'));
const serial=options['--serial'],hostPackage=options['--host-package'],devicePort=options['--device-port']||'29731';
assert.match(serial,/^[-A-Za-z0-9_.:]+$/);assert.match(hostPackage,/^[A-Za-z0-9_.]+$/);assert.ok(config.package&&config.space&&config.installGeneration&&config.expectedActivity);
await mkdir(directory,{recursive:true});const exec=promisify(execFile),pending=new Map(),transcript=[],checks=[],report={passed:false,serial,hostPackage,checks,modes:[]};
let sequence=0,stderr='',current,revision,preparedSha;
const child=spawn(process.execPath,[resolve('agent/server.mjs'),'--serial',serial,'--device-port',devicePort],{stdio:['pipe','pipe','pipe']});
child.stderr.on('data',bytes=>stderr=(stderr+bytes).slice(-32768));
createInterface({input:child.stdout}).on('line',line=>{const value=JSON.parse(line);transcript.push({direction:'response',value});const item=pending.get(value.id);if(item){clearTimeout(item.timer);pending.delete(value.id);item.resolve(value);}});
child.on('exit',code=>{for(const item of pending.values()){clearTimeout(item.timer);item.reject(Error('MCP_EXIT:'+code+':'+stderr));}pending.clear();});
async function rpc(method,params={}){const id=++sequence,value={jsonrpc:'2.0',id,method,params};transcript.push({direction:'request',value});const result=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_TIMEOUT:'+method));},method==='tools/call'&&params.name==='process_start'?75000:30000);pending.set(id,{resolve,reject,timer});});child.stdin.write(JSON.stringify(value)+'\n');const response=await result;if(response.error)throw Error(JSON.stringify(response.error));return response.result;}
async function tool(name,args={},required=true){const result=await rpc('tools/call',{name,arguments:args}),receipt=JSON.parse(result.content[0].text);if(required&&!receipt.ok)throw Object.assign(Error(name+':'+JSON.stringify(receipt)),{receipt});return required?receipt.result:receipt;}
function check(name,condition,detail){assert.ok(condition,name);checks.push({name,passed:true,...(detail===undefined?{}:{detail})});}
const install={space:config.space,package:config.package,installGeneration:config.installGeneration};
const mutate=(name,args)=>tool(name,{operationId:randomUUID(),...args});
async function ui(){return tool('ui_dump_hierarchy',{target:current,includeInvisible:false});}
function visible(value){return value.activities.some(activity=>expectedActivities.includes(activity.activity)&&activity.nodes.some(node=>node.visible));}
async function foreground(value){
 const text=(await exec(options['--adb'],['-s',serial,'shell','dumpsys','activity','activities'])).stdout;
 const tasks=new Set(value.activities.filter(activity=>expectedActivities.includes(activity.activity)).map(activity=>activity.taskId));
 return text.split('\n').find(line=>line.includes('topResumedActivity=')&&line.includes(hostPackage+'/dev.tripoder.runtime.')&&tasks.has(Number(line.match(/\bt(\d+)\b/)?.[1])))?.trim();
}
async function awaitUi(){
 const deadline=Date.now()+45000;let busyReads=0;
 do{
  let value;
  try{value=await ui();}
  catch(error){
   const receipt=error.receipt;
   if(!receipt?.error?.includes('TimeoutException')||!receipt.operationId||busyReads>=3)throw error;
   await tool('operation_get',{operationId:receipt.operationId});
   await tool('hook_status',{target:current});
   // A new, read-only observation after reconciling the timed-out query.
   // No mutation is replayed or inferred successful from a status read.
   busyReads++;(report.uiReadTimeouts??=[]).push({target:current,operationId:receipt.operationId});continue;
  }
  if(visible(value)&&await foreground(value))return value;
  await new Promise(resolve=>setTimeout(resolve,150));
 }while(Date.now()<deadline);throw Error('EXPECTED_ACTIVITY_NOT_IN_DEVICE_FOREGROUND');
}
try{
 await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'focused-runtime-mode-acceptance',version:'1'}});
 child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
 for(let attempt=0;;attempt++){const ready=await tool('runtime_status',{},false);if(ready.ok)break;if(attempt===9)throw Error('ENDPOINT_NOT_READY:'+ready.error);await new Promise(resolve=>setTimeout(resolve,500));}
 const sourceBefore=await tool('project_get',{package:config.package});
 const catalog=await tool('applications_list');check('exact-installation-present',catalog.installations.some(row=>row.space===install.space&&row.package===install.package&&row.installGeneration===install.installGeneration));
 const originalMode=(await tool('launch_entries_list',{target:install})).mode;report.originalMode=originalMode;report.finalMode=config.finalMode||originalMode;
 const plan=await tool('probes_get',{target:install});assert.equal(plan.configuration.enabled,false,'focused run requires disabled probes');
 let previous=config.previousTarget;
 for(const mode of (config.modes||['thin','thick'])){
  let process;
  if(config.resumeProcess){
   assert.equal(config.modes.length,1);assert.equal(config.resumeProcess.mode,mode);assert.equal(originalMode,mode);
   const known=await tool('operation_get',{operationId:config.resumeProcess.operationId});check(mode+'-original-start-operation-reconciled',known.state==='SUCCEEDED',known);
   current={...install,processGeneration:config.resumeProcess.processGeneration};
   const live=await tool('process_get',{target:current});assert.equal(live.pid,config.resumeProcess.pid);process={...live,processGeneration:current.processGeneration,entryDispatchRequested:true};
  }else{
  await mutate('application_stop',{target:install});await mutate('application_mode_set',{target:install,mode});
  await exec(options['--adb'],['-s',serial,'shell','input','keyevent','KEYCODE_WAKEUP']);
  await exec(options['--adb'],['-s',serial,'shell','am','start','-W','-n',hostPackage+'/dev.tripoder.hooklab.WorkbenchActivity']);
  process=await mutate('process_start',{target:install,component:'',probeRevision:plan.revision});current={...install,processGeneration:process.processGeneration};
  }
  check(mode+'-entry-dispatched',process.entryDispatchRequested===true);await awaitUi();check(mode+'-expected-activity-visible',true,expectedActivities);
  if(previous){const stale=await tool('process_get',{target:previous},false);check('stale-process-target-rejected',!stale.ok&&/STALE_SESSION|STALE_PROCESS|PROCESS_NOT_RUNNING/.test(stale.error||''),stale.error);}
  const status=await tool('hook_status',{target:current});check(mode+'-art-sigsys-signal-chain-survives-guest-handlers',status.nativeSignalChain?.ready===true&&status.nativeSignalChain.claims===1&&status.nativeSignalChain.modules>0,status.nativeSignalChain);check(mode+'-exact-mode-and-apk',status.identity.mode===mode&&status.identity.target.apkSha256===config.apkSha256);
  check(mode+'-compiler-policy-matches-mode',status.executionPolicy.preserveCompilation===(mode==='thin'),status.executionPolicy);if(config.requireJit)check(mode+'-unrelated-jit-remains-enabled',status.executionPolicy.bootstrapComplete===true&&status.executionPolicy.allowUnhookedCompilation===true&&status.executionPolicy.jitEnabled===true,status.executionPolicy);
  if(mode==='thick'&&config.requireThickPublicationPolicy)check('thick-startup-keeps-one-global-invalidation',status.executionPolicy.publicationDeoptimizations===1&&status.executionPolicy.callbackPublications>0&&status.executionPolicy.runtimeStateRestored===true&&status.executionPolicy.observedLevel===0&&status.executionPolicy.processLifetimeLease===false,status.executionPolicy);
  check(mode+'-verified-execution-copy',status.executionCode.source==='VERIFIED_INSTALLATION_COPY'&&status.executionCode.directory.endsWith('/code/'+config.package+'-'+install.installGeneration),status.executionCode);
  assert.match(status.runtimeNativeMappings.corePath,/^\/data\/app\/[A-Za-z0-9~_.=/+-]+\/lib\/arm64\/libtripoderselfhook\.so$/);
  const core=(await exec(options['--adb'],['-s',serial,'shell','sha256sum',status.runtimeNativeMappings.corePath])).stdout.split(/\s+/)[0];check(mode+'-loaded-core-matches-build',core===config.coreSha256,core);
  const before=await mutate('guest_invoke',{target:current,invocation:config.method});check(mode+'-original-result',before.result===config.expectedOriginal,before.result);
  const observed=await mutate('hook_observe',{target:current,selectors:[{id:'focused',kind:'java',className:config.method.className,methodName:config.method.methodName,argumentTypes:config.method.argumentTypes}]});
  check(mode+'-verified-native-module-visible',observed.nativeModules.some(module=>module.state==='AVAILABLE'&&module.path.startsWith(status.executionCode.directory+'/lib/arm64/')));
  const loader=observed.evidence.symbols[0].classLoaderId,conditions=config.method.arguments.map((value,index)=>'args['+index+']==='+JSON.stringify(value)).join('&&')||'true';
  const source="import {definePlan} from '@abox/hook';export default definePlan({id:'focused-runtime-mode',target,params:{},setup(ctx){ctx.java.method("+JSON.stringify(config.method.className)+','+JSON.stringify(config.method.methodName)+','+JSON.stringify(config.method.argumentTypes)+',{classLoaderId:'+JSON.stringify(loader)+"}).after(({args,result})=>{if("+conditions+')return '+JSON.stringify(config.replacement)+';return result;});}});';
  const prepared=await mutate('hook_prepare',{target:current,evidenceId:observed.evidence.id,source});preparedSha=prepared.sourceSha256;
  await mutate('hook_activate',{target:current,token:prepared.token,sourceSha256:prepared.sourceSha256});
  let active=await tool('hook_status',{target:current});revision=active.revision;
  const during=await mutate('guest_invoke',{target:current,invocation:config.method});active=await tool('hook_status',{target:current});
  if(mode==='thick'&&config.requireThickPublicationPolicy)check('thick-general-hook-keeps-caller-invalidation',active.executionPolicy.publicationDeoptimizations===status.executionPolicy.publicationDeoptimizations+1&&active.executionPolicy.runtimeStateRestored===true&&active.executionPolicy.jitEnabled===true,active.executionPolicy);
  check(mode+'-active-hook-changes-real-result',during.result===config.replacement&&active.state==='ACTIVE'&&active.hits>=1&&active.errors===0,{before:before.result,during:during.result,hits:active.hits,errors:active.errors});
  if(config.sustainMs){assert.ok(Number.isInteger(config.sustainMs)&&config.sustainMs>=0&&config.sustainMs<=60000);await new Promise(resolve=>setTimeout(resolve,config.sustainMs));const stable=await tool('hook_status',{target:current});check(mode+'-hook-remains-live-after-sustain',stable.state==='ACTIVE'&&stable.errors===0&&stable.nativeSignalChain.claims===1,{durationMs:config.sustainMs,signalChain:stable.nativeSignalChain});}
  const activeUi=await ui();check(mode+'-activity-visible-with-hook',visible(activeUi));
  const front=await foreground(activeUi);check(mode+'-actual-device-foreground-task',!!front,front);
  const screenshot=await exec(options['--adb'],['-s',serial,'exec-out','screencap','-p',...(config.displayId?['-d',String(config.displayId)]:[])],{encoding:'buffer',maxBuffer:8388608});assert.ok(screenshot.stdout.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])),'screenshot must be a clean PNG');await writeFile(join(directory,mode+'-main.png'),screenshot.stdout);
  await mutate('hook_disable',{target:current,revision});revision=null;preparedSha=null;
  const after=await mutate('guest_invoke',{target:current,invocation:config.method});check(mode+'-disable-restores-result',after.result===config.expectedOriginal,after.result);
  report.modes.push({mode,target:current,pid:process.pid,before:before.result,during:during.result,after:after.result,resumedFromOperation:config.resumeProcess?.operationId||null,nativeSyscalls:status.nativeSyscalls,foregroundActivities:activeUi.activities.filter(activity=>expectedActivities.includes(activity.activity)&&activity.nodes.some(node=>node.visible)).map(activity=>activity.activity)});previous=current;
  console.log(JSON.stringify({phase:'mode-complete',mode,checks:checks.length}));
 }
 const sourceAfter=await tool('project_get',{package:config.package});check('project-source-not-overwritten',sourceAfter.sourceSha256===sourceBefore.sourceSha256);
 if(report.finalMode!=='thick'){
  await mutate('application_stop',{target:install});await mutate('application_mode_set',{target:install,mode:report.finalMode});
  await exec(options['--adb'],['-s',serial,'shell','input','keyevent','KEYCODE_WAKEUP']);
  await exec(options['--adb'],['-s',serial,'shell','am','start','-W','-n',hostPackage+'/dev.tripoder.hooklab.WorkbenchActivity']);
  const process=await mutate('process_start',{target:install,component:'',probeRevision:plan.revision});current={...install,processGeneration:process.processGeneration};await awaitUi();
 }
 report.finalTarget=current;report.passed=true;
}catch(error){report.error=String(error);process.exitCode=1;}
finally{
 if(current&&(revision||preparedSha))try{const status=await tool('hook_status',{target:current});if(status.state==='ACTIVE'&&(revision===status.revision||status.sourceSha256===preparedSha))await mutate('hook_disable',{target:current,revision:status.revision});}catch(error){report.cleanupError=String(error);}
 await writeFile(join(directory,'mcp-transcript.json'),JSON.stringify(transcript,null,2));await writeFile(join(directory,'summary.json'),JSON.stringify(report,null,2));
 child.stdin.end();console.log(JSON.stringify({phase:'complete',passed:report.passed,checks:checks.length,error:report.error}));
}
