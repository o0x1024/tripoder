import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { once } from 'node:events';
import { mkdtemp,writeFile,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { loadConfig,Transport,decode } from './transport.mjs';
import { tools,command } from './tools.mjs';
import { McpServer,MCP_VERSION,MCP_VERSIONS } from './server.mjs';
import { recoveryReceipt } from './recovery.mjs';

const installTarget={space:'space-a',package:'owned.app',installGeneration:'install-a'};
const target={...installTarget,processGeneration:'process-a'};

async function initialize(server,version){const response=await server.handle({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:version}});await server.handle({jsonrpc:'2.0',method:'notifications/initialized'});return response;}
test('published versions negotiate without connecting to a phone',async()=>{
 for(const version of MCP_VERSIONS){const server=new McpServer({call(){throw Error('must remain lazy');}});assert.equal((await initialize(server,version)).result.protocolVersion,version);const list=await server.handle({jsonrpc:'2.0',id:2,method:'tools/list'});assert.equal(list.result.tools.length,tools.length);assert.ok(list.result.tools.some(t=>t.name==='applications_list'));assert.ok(!list.result.tools.some(t=>t.name==='target_select'));}
 const server=new McpServer({});assert.equal((await initialize(server,'2099-01-01')).result.protocolVersion,MCP_VERSION);
});
test('initialize and discovery accept legacy missing config and require neither adb nor keys',async()=>{
 assert.deepEqual(await loadConfig('/does-not-exist/tripoder-session.json'),{});
 const child=spawn(process.execPath,[new URL('./server.mjs',import.meta.url).pathname,'--config','/does-not-exist/tripoder-session.json'],{env:{PATH:'/does-not-exist'},stdio:['pipe','pipe','pipe']});let output='',errors='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>errors+=b);
 child.stdin.end(JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26'}})+'\n'+JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n'+JSON.stringify({jsonrpc:'2.0',id:2,method:'tools/list'})+'\n');
 const [code]=await once(child,'close');assert.equal(code,0,errors);const messages=output.trim().split('\n').map(JSON.parse);assert.equal(messages[0].result.protocolVersion,'2025-03-26');assert.equal(messages[1].result.tools.length,tools.length);assert.equal(errors,'');
});
test('wire accepts unsigned replies but rejects unrelated responses',async()=>{
 const server=net.createServer(socket=>{let buffer=Buffer.alloc(0);socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);if(buffer.length<4||buffer.length<4+buffer.readUInt32BE(0))return;const request=JSON.parse(buffer.subarray(4).toString());assert.deepEqual(Object.keys(request).sort(),['method','params','protocol','requestId']);const body=Buffer.from(JSON.stringify({protocol:2,requestId:request.requestId,ok:true,result:{targets:[]}}));const header=Buffer.alloc(4);header.writeUInt32BE(body.length);socket.end(Buffer.concat([header,body]));});});
 server.listen(0,'127.0.0.1');await once(server,'listening');const transport=new Transport();transport.port=server.address().port;
 try{assert.deepEqual((await transport.call('targets.list',{},'read-1')).result.targets,[]);}finally{transport.port=null;await transport.close();server.close();}
 assert.throws(()=>decode({requestId:'right'},{protocol:2,requestId:'wrong',ok:true}),/CORRELATION/);
});
test('missing targets are rejected before device dispatch, even when one process exists',async()=>{
 let calls=0;const server=new McpServer({call(){calls++;throw Error('must not dispatch');}});await initialize(server,MCP_VERSION);
 for(const name of ['hook_status','process_get','hook_activate']){
  const argumentsOf=name==='hook_activate'?{operationId:'activate',token:'t',sourceSha256:'a'.repeat(64)}:{};
  const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name,arguments:argumentsOf}});
  const receipt=JSON.parse(response.result.content[0].text);assert.equal(receipt.dispatched,false);assert.match(receipt.error,/missing target/);
 }assert.equal(calls,0);
});
test('alternating explicit targets never selects or inherits a connection target',async()=>{
 const transport=new Transport(),calls=[];transport.exchange=async request=>{calls.push(request);return {protocol:2,requestId:request.requestId,ok:true,result:{target:request.params.target}};};
 const other={...target,space:'space-b',installGeneration:'install-b',processGeneration:'process-b'};
 for(const identity of [target,other,target])assert.deepEqual((await transport.call('hook.status',{target:identity},'read-'+calls.length)).result.target,identity);
 assert.deepEqual(calls.map(call=>call.method),['hook.status','hook.status','hook.status']);assert.ok(calls.every(call=>!Object.hasOwn(call,'session')));assert.ok(!Object.hasOwn(transport,'session'));
});
test('tool schemas retain exact operation identity and reject ambiguous or incomplete targets',()=>{
 assert.deepEqual(command('process_get',{target}).params,{target});
 assert.equal(command('hook_disable',{target,operationId:'op-1',revision:'rev-1'}).requestId,'op-1');
 assert.throws(()=>command('hook_disable',{target,operationId:'op-1',revision:'rev-1',project:'other'}),/FIELDS/);
 assert.throws(()=>command('process_get',{target:{...target,processGeneration:''}}),/TYPE/);
 assert.throws(()=>command('process_get',{target:installTarget}),/TYPE/);
 for(const name of ['target_select','session_get','session_close'])assert.throws(()=>command(name,{}),/TOOL_NOT_FOUND/);
});
test('unknown mutation outcomes are returned once without replay',async()=>{
 let calls=0;const server=new McpServer({async call(){calls++;throw Error('TEST_LINK_LOSS');}});await initialize(server,MCP_VERSION);
 const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'hook_disable',arguments:{target,operationId:'exact-id',revision:'rev-1'}}});const result=JSON.parse(response.result.content[0].text);assert.equal(result.state,'UNKNOWN');assert.equal(result.operationId,'exact-id');assert.equal(calls,1);
});
test('MCP batches preserve ids, omit notification replies and reject excess messages',async()=>{
 const server=new McpServer({});const replies=await server.handle([{jsonrpc:'2.0',id:41,method:'ping'},{jsonrpc:'2.0',method:'notifications/cancelled'},{jsonrpc:'2.0',id:42,method:'ping'}]);assert.deepEqual(replies.map(r=>r.id),[41,42]);assert.equal((await server.handle([])).error.code,-32600);assert.equal((await server.handle(Array(9).fill({jsonrpc:'2.0',id:1,method:'ping'}))).error.code,-32600);
});
test('startup controls and artifact reads work without a live selected process',async()=>{
 const transport=new Transport(),methods=[];transport.exchange=async request=>{methods.push(request.method);assert.equal(request.session,undefined);return {protocol:2,requestId:request.requestId,ok:true,result:{}};};
 for(const method of ['runtime.capabilities','projects.list','probes.configure','probes.get','process.start','operation.get','artifact.read'])await transport.call(method,{},'startup-'+methods.length);
 assert.deepEqual(methods,['runtime.capabilities','projects.list','probes.configure','probes.get','process.start','operation.get','artifact.read']);
});
test('probe schema rejects accidental fields and limits before any device call',()=>{
 const args={operationId:'configure-1',target:installTarget,expectedRevision:'',configuration:{enabled:true,maxDexBytes:1048576,startupHook:{selectors:[{id:'startup',kind:'java',className:'owned.Fixture',methodName:'value',argumentTypes:[]}],source:'source'}}};
 assert.equal(command('probes_configure',args).requestId,'configure-1');
 assert.throws(()=>command('probes_configure',{...args,configuration:{enabled:true,maxDexBytes:67108865}}),/TYPE/);
 assert.throws(()=>command('probes_configure',{...args,configuration:{enabled:true,unpack:true}}),/TYPE/);
 assert.throws(()=>command('artifact_read',{processGeneration:'g',artifactId:'a',offset:0,length:16385}),/TYPE/);
});

const selector={id:'counter',kind:'java',className:'owned.Counter',methodName:'value',argumentTypes:[]};
test('Java and native selector contracts reject malformed input before dispatch',async()=>{
 const java={target,operationId:'observe-valid',selectors:[selector]};
 assert.equal(command('hook_observe',java).params.selectors[0].methodName,'value');
 const native={id:'native',kind:'native',moduleName:'libowned.so',symbol:'value',argumentTypes:['int32'],returnType:'int32',buildId:'aabb',signatureSource:'confirmed'};
 assert.equal(command('hook_observe',{...java,selectors:[native]}).params.selectors[0].kind,'native');
 const {argumentTypes,...missingArguments}=selector;
 for(const bad of [{kind:'java',class:'owned.Counter',method:'value'},missingArguments,{...selector,class:'wrong'},{...selector,argumentTypes:['java.lang.Object']},{...native,signatureSource:'unknown'},{...native,returnType:'pointer'}]){
  assert.throws(()=>command('hook_observe',{...java,selectors:[bad]}),/TOOL_ARGUMENT_TYPE/);
 }
 assert.throws(()=>command('hook_observe',{...java,selectors:[selector,selector]}),/duplicate id/);
 let calls=0;const server=new McpServer({call(){calls++;throw Error('must not dispatch');}});await initialize(server,MCP_VERSION);
 const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'hook_observe',arguments:{...java,selectors:[missingArguments]}}});
 const receipt=JSON.parse(response.result.content[0].text);
 assert.equal(receipt.state,'REJECTED');assert.equal(receipt.dispatched,false);assert.match(receipt.error,/selectors\[0\].*argumentTypes/);assert.equal(calls,0);
 assert.equal(receipt.recovery.replayAllowed,false);
});
test('startup Hook shares the full selector contract',()=>{
 const args={operationId:'configure',target:installTarget,expectedRevision:'',configuration:{enabled:true,startupHook:{selectors:[selector],source:'source'}}};
 assert.equal(command('probes_configure',args).params.configuration.startupHook.selectors[0].id,'counter');
 assert.throws(()=>command('probes_configure',{...args,configuration:{...args.configuration,startupHook:{selectors:[{kind:'java'}],source:'source'}}}),/missing/);
});
test('outcome gate returns original operation and the status recovery command without replay',async()=>{
 let calls=0;const server=new McpServer({async call(){calls++;return {ok:false,state:'FAILED',operationId:'blocked-call',error:'IllegalStateException:HOOK_OUTCOME_QUERY_REQUIRED:original-op'};}});await initialize(server,MCP_VERSION);
 const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'hook_events',arguments:{target,sinceSeq:0}}});
 const receipt=JSON.parse(response.result.content[0].text);
 assert.equal(receipt.pendingOperationId,'original-op');assert.deepEqual(receipt.recovery.steps,[{tool:'operation_get',arguments:{operationId:'original-op'}},{tool:'hook_status',arguments:{target}}]);assert.equal(calls,1);
});
test('operation queries preserve original id outside selected sessions and never become mutations',async()=>{
 const transport=new Transport();let wire;
 transport.exchange=async request=>{wire=request;return {protocol:2,requestId:request.requestId,ok:true,result:{operationId:request.params.operationId,state:'UNKNOWN'}};};
 const call=command('operation_get',{operationId:'original-op'});assert.notEqual(call.requestId,'original-op');
 await transport.call(call.method,call.params,call.requestId);assert.equal(wire.session,undefined);assert.equal(wire.params.operationId,'original-op');
 let calls=0;const server=new McpServer({async call(){calls++;throw Error('TRANSPORT_TIMEOUT_OUTCOME_UNKNOWN');}});await initialize(server,MCP_VERSION);
 const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'operation_get',arguments:{operationId:'original-op'}}});
 const receipt=JSON.parse(response.result.content[0].text);assert.equal(receipt.state,'UNAVAILABLE');assert.equal(receipt.operationId,undefined);assert.equal(calls,1);
});
test('protocol mismatch gives recovery advice and unknown mutations retain exact identity',async()=>{
 for(const [name,args,result] of [
  ['operation_get',{operationId:'original-op'},{ok:false,error:'IllegalArgumentException:AGENT_FIELDS_UNSUPPORTED'}],
  ['hook_disable',{target,operationId:'disable-original',revision:'rev'},{ok:false,state:'UNKNOWN',operationId:'disable-original',error:'lost ack'}],
  ['hook_discard',{target,operationId:'observe-id'},{ok:false,error:'IllegalArgumentException:AGENT_REQUEST_ID_CONTENT_MISMATCH'}],
 ]){
  let calls=0;const server=new McpServer({async call(){calls++;return result;}});await initialize(server,MCP_VERSION);
  const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name,arguments:args}});const receipt=JSON.parse(response.result.content[0].text);
  assert.equal(receipt.recovery.replayAllowed,false);assert.equal(calls,1);
  if(name==='operation_get')assert.match(receipt.recovery.action,/Reconnect/);
  else assert.equal(receipt.recovery.steps[0].arguments.operationId,args.operationId);
 }
});

test('unknown journal queries retain success envelope without inventing reconciliation or polling',()=>{
 const receipt=recoveryReceipt({ok:true,result:{operationId:'original',state:'UNKNOWN'}},'operation_get',{operationId:'original'});
 assert.equal(receipt.ok,true);assert.equal(receipt.result.state,'UNKNOWN');assert.equal(receipt.errorCode,undefined);assert.deepEqual(receipt.recovery.steps,[]);assert.equal(receipt.recovery.replayAllowed,false);
});


test('global control tools cover application lifecycle and project recovery without selected context',()=>{
 for(const name of ['runtime_status','spaces_list','space_create','space_rename','space_delete','applications_list','device_applications_list','application_import','application_import_device','application_remove','application_delete','application_mode_set','project_get','project_script_put','project_selectors_put','project_archives_list','project_archive_restore'])assert.ok(tools.some(tool=>tool.name===name),name);
 for(const tool of tools)if(tool.name.startsWith('hook_')||tool.name==='process_get')assert.ok(tool.inputSchema.required.includes('target'),tool.name);
 assert.throws(()=>command('application_stop',{operationId:'stop',target:{...installTarget,installGeneration:''}}),/TYPE/);
 assert.throws(()=>command('application_delete',{operationId:'delete',package:'owned.app',installations:[{...installTarget,processGeneration:'unexpected'}]}),/TYPE/);
});
test('APK import streams exact original bytes in bounded chunks, with explicit space and install generation',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'tripoder-import-test-'));const data=Buffer.alloc(42001);for(let i=0;i<data.length;i++)data[i]=i%251;const path=join(dir,'original.apk');await writeFile(path,data);
 const transport=new Transport(),calls=[],received=[];transport.exchange=async request=>{calls.push(request);assert.ok(!Object.hasOwn(request,'session'));
  if(request.method==='import.begin'){assert.equal(request.params.space,'b');assert.equal(request.params.expectedGeneration,'old');assert.equal(request.params.files[0].sha256,createHash('sha256').update(data).digest('hex'));return {protocol:2,requestId:request.requestId,ok:true,result:{offsets:[0]}};}
  if(request.method==='import.write'){const chunk=Buffer.from(request.params.data,'base64');assert.ok(chunk.length<=12288);assert.equal(request.params.offset,received.reduce((size,b)=>size+b.length,0));assert.equal(request.params.uploadId,'import-original');received.push(chunk);}
  return {protocol:2,requestId:request.requestId,ok:true,result:{}};
 };
 try{await transport.call('application.import',{space:'b',expectedGeneration:'old',apkPaths:[path]},'import-original');assert.deepEqual(Buffer.concat(received),data);assert.equal(calls.at(-1).method,'import.commit');assert.equal(calls.at(-1).requestId,'import-original');}finally{await rm(dir,{recursive:true,force:true});}
});
test('upload failure stops before install commit and is never replayed automatically',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'tripoder-import-stop-'));const path=join(dir,'base.apk');await writeFile(path,Buffer.alloc(30000));const calls=[];const transport=new Transport();
 transport.exchange=async request=>{calls.push(request.method);if(request.method==='import.begin')return {protocol:2,requestId:request.requestId,ok:true,result:{offsets:[0]}};throw Error('lost chunk acknowledgement');};
 try{await assert.rejects(transport.call('application.import',{space:'a',expectedGeneration:'',apkPaths:[path]},'upload'),/lost chunk/);assert.deepEqual(calls,['import.begin','import.write']);}finally{await rm(dir,{recursive:true,force:true});}
});


test('guest invocation separates process identity from the Java method target',()=>{
 const invocation={className:'owned.Counter',methodName:'value',argumentTypes:[],arguments:[]};
 assert.deepEqual(command('guest_invoke',{operationId:'invoke',target,invocation}).params,{target,invocation});
 assert.throws(()=>command('guest_invoke',{operationId:'invoke',target:invocation}),/FIELDS/);
 assert.throws(()=>command('guest_invoke',{operationId:'invoke',target:invocation,invocation}),/TYPE/);
});
