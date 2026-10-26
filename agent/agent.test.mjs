import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import http from 'node:http';
import {loadConfig,Transport,validateConfig,MCP_VERSION} from './transport.mjs';
import {McpServer} from './server.mjs';
import {tools,contract,command,operationCall} from './tools.mjs';
const config=await loadConfig(process.env.TRIPODER_MCP_TEST_CONNECTION);
const target={space:'space-a',package:'owned.app',installGeneration:'install-a',processGeneration:'process-a'};
const initialize=async transport=>{await transport.rpc({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'host-tests',version:'1'}}});await transport.rpc({jsonrpc:'2.0',method:'notifications/initialized'});};
const create=async()=>{const t=new Transport(config);await initialize(t);return t;};
const rpc=(t,id,method,params={})=>t.rpc({jsonrpc:'2.0',id,method,params});
const request=(options,body='')=>new Promise((resolve,reject)=>{const req=http.request(config.url,{method:'POST',headers:{accept:'application/json, text/event-stream','content-type':'application/json','content-length':Buffer.byteLength(body)},...options},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});req.on('error',reject);req.end(body);});
test('production Java tools/list is generated from the single contract',async()=>{const t=await create();assert.deepEqual((await rpc(t,2,'tools/list')).result.tools,contract.map(e=>e.tool));await t.close();});
test('retired tools and cross-action arguments are rejected before any dispatch',async()=>{
 const t=await create();try{
  const retired=await t.tool('runtime_capabilities',{});assert.equal(retired.errorCode,'TOOL_RETIRED');assert.equal(retired.dispatched,false);assert.equal(retired.replacement.tool,'runtime_query');assert.equal(retired.replacement.action,'capabilities');
  for(const args of [{action:'unknown'},{action:'status',space:'forbidden'}]){const result=await t.tool('runtime_query',args);assert.equal(result.dispatched,false);assert.equal(result.errorCode,'INVALID_TOOL_ARGUMENTS');}
  const local=new McpServer({rpc(){throw Error('must not dispatch');}});local.ready=true;const receipt=JSON.parse((await local.handle({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'file_pull_cancel',arguments:{operationId:'op'}}})).result.content[0].text);assert.equal(receipt.errorCode,'TOOL_RETIRED');assert.equal(receipt.dispatched,false);
 }finally{await t.close();}
});
test('HTTP accepts initialization without credentials and checks Host and Origin',async()=>{const body=JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION}});assert.equal(await request({},body),200);assert.equal(await request({headers:{origin:'https://attacker.example'}}),403);assert.equal(await request({headers:{host:'attacker.example'}}),403);});
test('Streamable HTTP explicitly declines SSE and removes sessions',async()=>{const t=await create();assert.equal(await request({method:'GET'}),405);assert.equal(await request({method:'DELETE',headers:{'mcp-session-id':t.session}}),204);await assert.rejects(rpc(t,2,'tools/list'),/MCP_HTTP_404/);await t.close();});
test('HTTP-only transport rejects HTTPS and certificate configuration before dispatch',()=>{assert.throws(()=>new Transport({url:config.url.replace('http:','https:')}),/CONNECTION_HTTP_MCP_REQUIRED/);assert.throws(()=>new Transport({...config,certificatePem:'unused'}),/CONNECTION_FIELDS_INVALID/);});
test('initialization and protocol gates reject early tool dispatch',async()=>{const t=new Transport(config);await assert.rejects(rpc(t,1,'tools/list'),e=>e.dispatched===false&&e.message==='MCP_HTTP_404');await rpc(t,2,'initialize',{protocolVersion:MCP_VERSION});await assert.rejects(rpc(t,3,'tools/list'),/MCP_HTTP_400/);await t.close();});
test('phone validation rejects missing target, extra fields and duplicate selectors',async()=>{const t=await create();for(const args of [{},{target:{...target,extra:'x'}},{target:{...target,processGeneration:''}}]){const result=await t.operation('hook.status',args);assert.equal(result.state,'REJECTED');assert.equal(result.dispatched,false);}const selector={id:'same',kind:'java',className:'owned.Counter',methodName:'value',argumentTypes:[]};assert.equal((await t.operation('hook.observe',{operationId:'observe-test',target,selectors:[selector,selector]})).dispatched,false);await t.close();});
test('HTTP entry validates nested payloads before dispatch and preserves valid request fields',async()=>{
  const t=await create();
  try{
    for(const [name,field] of [['http_send_request','request'],['ui_dispatch_action','action'],['guest_invoke','invocation']]){
      const rejected=await t.tool(name,{operationId:'invalid-'+field,target,[field]:{}});
      assert.equal(rejected.errorCode,'INVALID_TOOL_ARGUMENTS');assert.equal(rejected.dispatched,false);
    }
    const request={method:'POST',url:'https://example.com/',headers:{'Content-Type':'application/json'},body:'{}',timeoutMs:5000};
    const valid=await t.operation('hook.httpRequest',{operationId:'request-shape',target,request});
    assert.equal(valid.ok,true);assert.equal(valid.result.method,'hook.httpRequest');assert.deepEqual(valid.result.params.request,request);
  }finally{await t.close();}
});
test('phone routes alternating explicit targets to the same control pipeline',async()=>{const t=await create();for(const selected of [target,{...target,space:'space-b'},target]){const r=await t.operation('hook.status',{target:selected});assert.equal(r.ok,true);assert.deepEqual(r.result.target,selected);}await t.close();});
test('phone retains mutation operation id and read query parameters',async()=>{const t=await create();const r=await t.operation('space.create',{operationId:'exact-operation',space:'host-test',name:'Host'});assert.equal(r.requestId,'exact-operation');assert.equal(r.result.params.operationId,undefined);const q=await t.operation('operation.get',{operationId:'exact-operation'});assert.equal(q.result.params.operationId,'exact-operation');await t.close();});
test('stdio subprocess uses HTTP with no adb or external executable PATH',async()=>{const input=[{jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION}},{jsonrpc:'2.0',method:'notifications/initialized'},{jsonrpc:'2.0',id:2,method:'tools/list'},{jsonrpc:'2.0',id:3,method:'tools/call',params:operationCall('hook.status',{target})}];const child=spawn(process.execPath,[resolve('agent/server.mjs'),'--connection',process.env.TRIPODER_MCP_TEST_CONNECTION],{env:{...process.env,PATH:'/nonexistent',ANDROID_SERIAL:'not-used'},stdio:['pipe','pipe','pipe']});let out='',err='';child.stdout.on('data',b=>out+=b);child.stderr.on('data',b=>err+=b);child.stdin.end(input.map(x=>JSON.stringify(x)).join('\n')+'\n');const [code]=await once(child,'close');assert.equal(code,0,err);const replies=out.trim().split('\n').map(JSON.parse);assert.equal(replies[1].result.tools.length,tools.length);assert.equal(replies[1].result.tools.at(-1).name,'application_import');assert.equal(JSON.parse(replies[2].result.content[0].text).ok,true);});
test('local APK upload uses shared begin/write/commit tools',async()=>{const t=await create(),folder=await mkdtemp(join(tmpdir(),'tripoder-upload-'));try{const apk=join(folder,'a.apk');await writeFile(apk,Buffer.alloc(14000,7));const result=await t.importApks({operationId:'local-upload',space:'a',expectedGeneration:'',apkPaths:[apk]});assert.equal(result.ok,true);assert.equal(result.result.method,'import.commit');assert.equal(result.requestId,'local-upload');}finally{await t.close();await rm(folder,{recursive:true});}});
test('local file failure is proven undispatched and unknown mutations never replay',async()=>{let calls=0;const s=new McpServer({async rpc(){calls++;throw Object.assign(Error('TEST_LOST'),{dispatched:true});}});const r=await s.handle({jsonrpc:'2.0',id:1,method:'tools/call',params:operationCall('space.create',{operationId:'unknown-id'})});assert.equal(JSON.parse(r.result.content[0].text).state,'UNKNOWN');assert.equal(calls,1);const t=await create();await assert.rejects(t.importApks({operationId:'missing-file',space:'a',expectedGeneration:'',apkPaths:['/not-a-real-file']}),e=>e.dispatched===false);await t.close();});
test('connection configuration contains only HTTP endpoint, without private permissions',async()=>{const dir=await mkdtemp(join(tmpdir(),'tripoder-config-'));try{const path=join(dir,'connection.json');const exported={url:config.url};await writeFile(path,JSON.stringify(exported),{mode:0o644});assert.equal((await loadConfig(path)).url,config.url);assert.throws(()=>validateConfig({...exported,token:'removed'}),/FIELDS_INVALID/);assert.throws(()=>validateConfig({...exported,url:'https://example/mcp'}),/HTTP_MCP_REQUIRED/);await assert.rejects(loadConfig('/no-file'),/ENOENT/);}finally{await rm(dir,{recursive:true});}});
test('existing selector contract enforces confirmed native signatures',()=>{const native={id:'n',kind:'native',moduleName:'libowned.so',symbol:'value',argumentTypes:['int32'],returnType:'int32',buildId:'aa',signatureSource:'confirmed'};assert.equal(command('hook_observe',{operationId:'native-op',target,selectors:[native]}).method,'hook.observe');assert.throws(()=>command('hook_observe',{operationId:'bad-native',target,selectors:[{...native,signatureSource:'unknown'}]}),/ENUM_VALUE_INVALID/);});

test('read reconciliation failure is unavailable rather than an unknown mutation',async()=>{const s=new McpServer({async rpc(){throw Object.assign(Error('TEST_LOST'),{dispatched:true});}});const r=await s.handle({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'operation_get',arguments:{operationId:'original-id'}}});const receipt=JSON.parse(r.result.content[0].text);assert.equal(receipt.state,'UNAVAILABLE');assert.equal(receipt.operationId,undefined);});
