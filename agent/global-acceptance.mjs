/** Real stdio/device acceptance. Requires a self-owned launcher fixture absent from this Tripoder install. */
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,index,args)=>index%2?out:[...out,[key,args[index+1]]],[]));
if(!options['--serial']||!options['--evidence'])throw Error('Usage: global-acceptance.mjs --serial SERIAL --evidence DIRECTORY [--device-port PORT] [--fixture APK]');
const directory=resolve(options['--evidence']),fixture=resolve(options['--fixture']??'android/product/build/launcher-guest.apk');
await mkdir(directory,{recursive:true});
const args=[resolve('agent/server.mjs'),'--serial',options['--serial'],'--audit',resolve(directory,'mcp-audit.jsonl')];if(options['--device-port'])args.push('--device-port',options['--device-port']);
const child=spawn(process.execPath,args,{stdio:['pipe','pipe','pipe']}),pending=new Map(),checks=[];let sequence=0,stderr='';
child.stderr.on('data',chunk=>stderr+=chunk);
createInterface({input:child.stdout}).on('line',line=>{const value=JSON.parse(line),item=pending.get(value.id);if(item){pending.delete(value.id);clearTimeout(item.timer);item.resolve(value);}});
child.on('exit',code=>{for(const item of pending.values()){clearTimeout(item.timer);item.reject(Error('MCP_EXIT:'+code+':'+stderr));}pending.clear();});
async function rpc(method,params={}){const id=++sequence;const response=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_TIMEOUT:'+method));},45000);pending.set(id,{resolve,reject,timer});});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');const value=await response;if(value.error)throw Error(JSON.stringify(value.error));return value.result;}
async function tool(name,args={},success=true){const response=await rpc('tools/call',{name,arguments:args}),receipt=JSON.parse(response.content[0].text);if(success)assert.equal(receipt.ok,true,JSON.stringify(receipt));return success?receipt.result:receipt;}
function check(name,condition){assert.ok(condition,name);checks.push({name,passed:true});}
const operationId=()=>randomUUID(),pkg='dev.tripoder.launcherfixture',prefix='global-'+randomUUID().slice(0,8),spaceA=prefix+'-a',spaceB=prefix+'-b';
const selector={id:'counter',kind:'java',className:'dev.tripoder.previewguest.CounterActivity',methodName:'formatCount',argumentTypes:['int']};
const invocation={className:selector.className,methodName:selector.methodName,argumentTypes:['int'],arguments:[7]};
const source="import {definePlan} from '@abox/hook';export default definePlan({id:'global-counter',target,params:{},setup(ctx){ctx.java.method('dev.tripoder.previewguest.CounterActivity','formatCount',['int']).replace(({args})=>'global-'+args[0]);}});";
const result={passed:false,checks,scope:'real-stdio-global-control-two-spaces-one-project',deviceHookVerified:false};
try{
 await rpc('initialize',{protocolVersion:'2025-11-25',clientInfo:{name:'tripoder-global-acceptance',version:'1'},capabilities:{}});child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
 const schemas=(await rpc('tools/list')).tools;
 check('no-connection-target-selection',!schemas.some(t=>['target_select','session_get','session_close'].includes(t.name)));
 check('global-capabilities',(await tool('runtime_capabilities')).explicitTargets===true);
 const initial=await tool('applications_list');assert.ok(!initial.applications.some(app=>app.package===pkg),'fixture application must be absent; use an isolated Tripoder test APK');
 await tool('space_create',{operationId:operationId(),space:spaceA,name:'Global A'});await tool('space_create',{operationId:operationId(),space:spaceB,name:'Global B'});
 const importA=operationId(),importB=operationId();
 const a=await tool('application_import',{operationId:importA,space:spaceA,apkPaths:[fixture],expectedGeneration:''});assert.equal(a.package,pkg);
 const original=await tool('project_get',{package:pkg});
 const saved=await tool('project_script_put',{operationId:operationId(),package:pkg,expectedSha256:original.sourceSha256,source});
 const b=await tool('application_import',{operationId:importB,space:spaceB,apkPaths:[fixture],expectedGeneration:''});
 const targetA={space:spaceA,package:pkg,installGeneration:a.generation},targetB={space:spaceB,package:pkg,installGeneration:b.generation};
 const catalog=await tool('applications_list');check('one-project-across-two-spaces',catalog.applications.filter(app=>app.package===pkg).length===1&&catalog.installations.filter(app=>app.package===pkg).length===2);
 const shared=await tool('project_get',{package:pkg});check('second-import-retains-source-and-project',shared.project===original.project&&shared.sourceSha256===saved.sourceSha256);
 const repeated=await tool('application_import',{operationId:operationId(),space:spaceA,apkPaths:[fixture],expectedGeneration:a.generation});check('identical-import-retains-install-generation',repeated.generation===a.generation);
 check('explicit-launch-targets',(await tool('application_launch_targets',{target:targetA})).targets.some(entry=>entry.supported&&entry.launcher));
 const start=async target=>tool('process_start',{operationId:operationId(),target,component:'',probeRevision:(await tool('probes_get',{target})).revision});
 const processA={...targetA,processGeneration:(await start(targetA)).processGeneration},processB={...targetB,processGeneration:(await start(targetB)).processGeneration};
 const pidA=(await tool('process_get',{target:processA})).pid,pidB=(await tool('process_get',{target:processB})).pid;check('spaces-have-distinct-live-processes',pidA!==pidB);result.targets=[{...processA,pid:pidA},{...processB,pid:pidB}];
 const before=(await tool('guest_invoke',{operationId:operationId(),target:processA,invocation})).result;check('baseline-method-has-original-return',before==='7');
 const observed=await tool('hook_observe',{operationId:operationId(),target:processA,selectors:[selector]});
 const prepared=await tool('hook_prepare',{operationId:operationId(),target:processA,evidenceId:observed.evidence.id,source});
 const active=await tool('hook_activate',{operationId:operationId(),target:processA,token:prepared.token,sourceSha256:prepared.sourceSha256});check('hook-activation-confirmed',active.state==='ACTIVE');
 const modified=(await tool('guest_invoke',{operationId:operationId(),target:processA,invocation})).result;check('real-hook-return-value',modified==='global-7');
 check('other-space-retains-original-return',(await tool('guest_invoke',{operationId:operationId(),target:processB,invocation})).result==='7');
 const actual=await tool('hook_status',{target:processA});check('actual-hook-hits-without-errors',actual.hits>=1&&actual.errors===0);result.hookStatus=actual;
 if(options['--native-sha256']){const native=actual.runtimeNativeMappings;check('running-worker-maps-the-current-native-build',native?.coreSha256===options['--native-sha256']&&native.pid===pidA&&native.processGeneration===processA.processGeneration&&native.foreignBackendRows?.length===0&&native.coreRows?.length>0);}

 const stale=await tool('hook_status',{target:{...processA,processGeneration:'not-a-live-process'}},false);check('stale-process-rejected',stale.ok===false);
 await tool('hook_disable',{operationId:operationId(),target:processA,revision:actual.revision});const restored=(await tool('guest_invoke',{operationId:operationId(),target:processA,invocation})).result;check('disable-restores-original-method',restored===before);result.returnValues={before,modified,restored};result.deviceHookVerified=true;
 await tool('application_stop',{operationId:operationId(),target:targetA});check('stopping-a-keeps-b-alive',(await tool('process_get',{target:processB})).pid===pidB);
 const conflict=await tool('project_script_put',{operationId:operationId(),package:pkg,expectedSha256:'0'.repeat(64),source:'overwrite'},false);check('stale-source-write-rejected',!conflict.ok&&conflict.error.includes('SOURCE_REVISION_CONFLICT'));
 await tool('space_delete',{operationId:operationId(),space:spaceA});check('space-delete-keeps-project',(await tool('project_get',{package:pkg})).sourceSha256===saved.sourceSha256);
 await tool('application_remove',{operationId:operationId(),target:targetB});check('last-uninstall-keeps-project',(await tool('project_get',{package:pkg})).project===original.project);
 await tool('application_delete',{operationId:operationId(),package:pkg,installations:[]});check('explicit-application-delete-removes-project',!(await tool('applications_list')).applications.some(app=>app.package===pkg));
 await tool('space_delete',{operationId:operationId(),space:spaceB});
 await tool('application_import_cleanup',{operationId:operationId(),uploadId:importA});await tool('application_import_cleanup',{operationId:operationId(),uploadId:importB});
 result.passed=true;console.log(JSON.stringify({passed:true,checks:checks.length}));
}catch(error){result.error=String(error);throw error;}
finally{child.stdin.end();await writeFile(resolve(directory,'global-result.json'),JSON.stringify(result,null,2)+'\n');}
