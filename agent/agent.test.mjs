import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { once } from 'node:events';
import { loadConfig,Transport,decode } from './transport.mjs';
import { tools,command } from './tools.mjs';
import { McpServer,MCP_VERSION,MCP_VERSIONS } from './server.mjs';

async function initialize(server,version){const response=await server.handle({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:version}});await server.handle({jsonrpc:'2.0',method:'notifications/initialized'});return response;}
test('published versions negotiate without connecting to a phone',async()=>{
 for(const version of MCP_VERSIONS){const server=new McpServer({call(){throw Error('must remain lazy');}});assert.equal((await initialize(server,version)).result.protocolVersion,version);const list=await server.handle({jsonrpc:'2.0',id:2,method:'tools/list'});assert.equal(list.result.tools.length,42);assert.ok(list.result.tools.some(t=>t.name==='target_select'));}
 const server=new McpServer({});assert.equal((await initialize(server,'2099-01-01')).result.protocolVersion,MCP_VERSION);
});
test('initialize and discovery accept legacy missing config and require neither adb nor keys',async()=>{
 assert.deepEqual(await loadConfig('/does-not-exist/tripoder-session.json'),{});
 const child=spawn(process.execPath,[new URL('./server.mjs',import.meta.url).pathname,'--config','/does-not-exist/tripoder-session.json'],{env:{PATH:'/does-not-exist'},stdio:['pipe','pipe','pipe']});let output='',errors='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>errors+=b);
 child.stdin.end(JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26'}})+'\n'+JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n'+JSON.stringify({jsonrpc:'2.0',id:2,method:'tools/list'})+'\n');
 const [code]=await once(child,'close');assert.equal(code,0,errors);const messages=output.trim().split('\n').map(JSON.parse);assert.equal(messages[0].result.protocolVersion,'2025-03-26');assert.equal(messages[1].result.tools.length,42);assert.equal(errors,'');
});
test('wire accepts unsigned replies but rejects unrelated responses',async()=>{
 const server=net.createServer(socket=>{let buffer=Buffer.alloc(0);socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);if(buffer.length<4||buffer.length<4+buffer.readUInt32BE(0))return;const request=JSON.parse(buffer.subarray(4).toString());assert.deepEqual(Object.keys(request).sort(),['method','params','protocol','requestId']);const body=Buffer.from(JSON.stringify({protocol:2,requestId:request.requestId,ok:true,result:{targets:[]}}));const header=Buffer.alloc(4);header.writeUInt32BE(body.length);socket.end(Buffer.concat([header,body]));});});
 server.listen(0,'127.0.0.1');await once(server,'listening');const transport=new Transport();transport.port=server.address().port;
 try{assert.deepEqual((await transport.call('targets.list',{},'read-1')).result.targets,[]);}finally{transport.port=null;await transport.close();server.close();}
 assert.throws(()=>decode({requestId:'right'},{protocol:2,requestId:'wrong',ok:true}),/CORRELATION/);
});
test('ambiguous targets never silently dispatch a mutation to another app',async()=>{
 const transport=new Transport();const calls=[];transport.exchange=async request=>{calls.push(request);return {protocol:2,requestId:request.requestId,ok:true,result:{targets:[{project:'a',package:'a',processName:'a',processGeneration:'a1'},{project:'b',package:'b',processName:'b',processGeneration:'b1'}]}};};
 const result=await transport.call('hook.activate',{token:'t',sourceSha256:'a'.repeat(64)},'mut-1');assert.equal(result.ok,false);assert.match(result.error,/TARGET_SELECTION_REQUIRED/);assert.equal(calls.length,1);assert.equal(calls[0].method,'targets.list');
});
test('single main process is selected automatically without phone approval',async()=>{
 const transport=new Transport();const methods=[];transport.exchange=async r=>{methods.push(r.method);return {protocol:2,requestId:r.requestId,ok:true,result:r.method==='targets.list'?{targets:[{project:'p',package:'app',processName:'app',processGeneration:'g'},{project:'p',package:'app',processName:'app:worker',processGeneration:'w'}]}:r.method==='target.select'?{session:'scope-1'}:{state:'OPEN'}};};
 assert.equal((await transport.call('session.get',{},'get-1')).result.state,'OPEN');assert.deepEqual(methods,['targets.list','target.select','session.get']);assert.equal(transport.session,'scope-1');
});
test('tool schemas retain exact operation identity and target generation',()=>{
 assert.deepEqual(command('target_select',{project:'p',processGeneration:'g'}).params,{project:'p',processGeneration:'g'});
 assert.equal(command('hook_disable',{operationId:'op-1',revision:'rev-1'}).requestId,'op-1');
 assert.throws(()=>command('hook_disable',{operationId:'op-1',revision:'rev-1',project:'other'}),/FIELDS/);
 assert.throws(()=>command('target_select',{project:'p',processGeneration:''}),/TYPE/);assert.equal(tools.length,42);
});
test('unknown mutation outcomes are returned once without replay',async()=>{
 let calls=0;const server=new McpServer({async call(){calls++;throw Error('TEST_LINK_LOSS');}});await initialize(server,MCP_VERSION);
 const response=await server.handle({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'hook_disable',arguments:{operationId:'exact-id',revision:'rev-1'}}});const result=JSON.parse(response.result.content[0].text);assert.equal(result.state,'UNKNOWN');assert.equal(result.operationId,'exact-id');assert.equal(calls,1);
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
 const args={operationId:'configure-1',project:'p',expectedRevision:'',configuration:{enabled:true,maxDexBytes:1048576,startupHook:{selectors:[{kind:'java'}],source:'source'}}};
 assert.equal(command('probes_configure',args).requestId,'configure-1');
 assert.throws(()=>command('probes_configure',{...args,configuration:{enabled:true,maxDexBytes:67108865}}),/TYPE/);
 assert.throws(()=>command('probes_configure',{...args,configuration:{enabled:true,unpack:true}}),/TYPE/);
 assert.throws(()=>command('artifact_read',{processGeneration:'g',artifactId:'a',offset:0,length:16385}),/TYPE/);
});
