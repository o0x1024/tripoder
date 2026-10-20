import { spawn,execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile,writeFile,mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
const exec=promisify(execFile);const opts=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>i%2?a:[...a,[v,all[i+1]]],[]));
const serial=opts['--serial'],run=opts['--run'],dir=resolve(opts['--evidence']);const ready=JSON.parse(await readFile(opts['--ready'],'utf8'));const remote='/sdcard/Android/data/dev.tripoder.engine/files/';
if(!serial||!/^control-[a-f0-9]{16}$/.test(run))throw Error('OWNED_RUN_REQUIRED');
await mkdir(dir,{recursive:true});const transcript=[],checks=[];let sequence=0;
function check(name,value){assert.ok(value,name);checks.push({name,passed:true});}
async function marker(name){const path=resolve(dir,name+'.json');await writeFile(path,'{}');await exec('adb',['-s',serial,'push',path,remote+run+'-open-'+name+'.json']);}
async function waitMarker(name){const end=Date.now()+20000;while(Date.now()<end){try{await exec('adb',['-s',serial,'shell','cat',remote+run+'-open-'+name+'.json']);return;}catch{}await new Promise(r=>setTimeout(r,80));}throw Error('PHONE_STAGE_TIMEOUT:'+name);}
function client(args,env=process.env){
 const child=spawn(process.execPath,[resolve('agent/server.mjs'),...args],{env,stdio:['pipe','pipe','pipe']});const pending=new Map();let stderr='';child.stderr.on('data',b=>stderr+=b);
 createInterface({input:child.stdout}).on('line',line=>{const response=JSON.parse(line);transcript.push({direction:'response',message:response});const done=pending.get(response.id);if(done){pending.delete(response.id);done.resolve(response);}});
 child.on('exit',code=>{for(const item of pending.values())item.reject(Error('MCP_EXIT:'+code+':'+stderr));pending.clear();});
 return {async rpc(method,params={}){const id=++sequence;const request={jsonrpc:'2.0',id,method,params};transcript.push({direction:'request',message:request});const value=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_REQUEST_TIMEOUT:'+method));},25000);pending.set(id,{resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}});});child.stdin.write(JSON.stringify(request)+'\n');const response=await value;if(response.error)throw Error(JSON.stringify(response.error));return response.result;},
 notify(method){child.stdin.write(JSON.stringify({jsonrpc:'2.0',method})+'\n');},
 async tool(name,args={}){const result=await this.rpc('tools/call',{name,arguments:args});const receipt=JSON.parse(result.content[0].text);assert.equal(receipt.ok,true,JSON.stringify(receipt));return receipt.result;},
 close(){child.stdin.end();}};
}
const a=client(['--serial',serial]);let b;
try{
 for(const version of ['2025-03-26']){const init=await a.rpc('initialize',{protocolVersion:version,capabilities:{},clientInfo:{name:'open-mcp-acceptance',version:'1'}});check('sentinel-protocol-initializes-without-config',init.protocolVersion===version);}
 a.notify('notifications/initialized');const list=await a.rpc('tools/list');check('discovers-42-tools',list.tools.length===42);
 const targets=await a.tool('targets_list');check('discovers-owned-running-process',targets.targets.some(t=>t.project===ready.project&&t.processGeneration===ready.processGeneration));
 const selection=await a.tool('target_select',{project:ready.project,processGeneration:ready.processGeneration});check('target-selection-needs-no-identity-or-review',selection.authenticationRequired===false&&selection.reviewRequired===false);
 const observe=await a.tool('hook_observe',{operationId:randomUUID(),selectors:[{id:'counter',kind:'java',className:'dev.tripoder.previewguest.CounterActivity',methodName:'formatCount',argumentTypes:['int']}]});
 const prepared=await a.tool('hook_prepare',{operationId:randomUUID(),evidenceId:observe.evidence.id,source:ready.source});
 const active=await a.tool('hook_activate',{operationId:randomUUID(),token:prepared.token,sourceSha256:prepared.sourceSha256});check('activates-with-no-phone-approval',active.state==='ACTIVE');
 b=client(['--serial',serial]);const init=await b.rpc('initialize',{protocolVersion:'2025-11-25',capabilities:{},clientInfo:{name:'second-independent-agent',version:'1'}});check('second-agent-negotiates-newer-protocol',init.protocolVersion==='2025-11-25');b.notify('notifications/initialized');await b.tool('target_select',{project:ready.project,processGeneration:ready.processGeneration});check('second-agent-reads-active-target-without-pairing',(await b.tool('hook_status')).state==='ACTIVE');b.close();b=null;
 await marker('active');await waitMarker('visible');
 const click={operationId:randomUUID(),action:{type:'clickText',text:'＋  计数一次'}};await a.tool('ui_dispatch_action',click);await a.tool('ui_dispatch_action',click);await marker('hooked');await waitMarker('hooked-ui');
 const status=await a.tool('hook_status');check('actual-ui-hook-has-two-hits-and-no-errors',status.hits===2&&status.errors===0);check('connection-close-does-not-disable-hook',status.state==='ACTIVE');
 await a.tool('hook_disable',{operationId:randomUUID(),revision:status.revision});await a.tool('ui_dispatch_action',{operationId:randomUUID(),action:{type:'clickText',text:'＋  计数一次'}});await marker('disabled');await waitMarker('restored');
 await a.tool('session_close',{operationId:randomUUID()});check('explicit-close-returns-closed-context',(await a.tool('session_get')).state==='CLOSED');
 check('closed-context-keeps-target-running',(await a.tool('targets_list')).targets.some(t=>t.processGeneration===ready.processGeneration&&t.pid===ready.pid));
 b=client(['--config',resolve(dir,'missing-legacy-session.json')],{...process.env,PATH:'/usr/bin:/bin:/usr/sbin:/sbin'});const legacy=await b.rpc('initialize',{protocolVersion:'2025-06-18',capabilities:{},clientInfo:{name:'legacy-json-client',version:'1'}});check('missing-legacy-pairing-file-no-longer-breaks-initialize',legacy.protocolVersion==='2025-06-18');b.notify('notifications/initialized');await b.tool('target_select',{project:ready.project,processGeneration:ready.processGeneration});check('legacy-client-finds-adb-with-gui-path-and-no-keys',(await b.tool('process_get')).pid===ready.pid);
 await marker('finished');
 await writeFile(resolve(dir,'desktop-result.json'),JSON.stringify({passed:true,checks,phoneApprovalPerformed:false,pairingPerformed:false},null,2));
 console.log(JSON.stringify({passed:true,checks:checks.length}));
}finally{a.close();b?.close();await writeFile(resolve(dir,'mcp-transcript.json'),JSON.stringify(transcript,null,2));}
