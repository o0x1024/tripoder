import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { decode, envelope, message, publicKey } from './transport.mjs';
import { command, tools } from './tools.mjs';
import { McpServer, MCP_VERSION } from './server.mjs';
function key(){const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'});return {...pair,encoded:pair.publicKey.export({format:'der',type:'spki'}).toString('base64')};}
test('pinned device signature, direction and request correlation cannot be substituted',()=>{
  const device=key(),client=key();const config={privateKey:client.privateKey.export({format:'pem',type:'pkcs8'}),devicePublicKey:device.encoded};
  const request=envelope(config,'call','session-1',JSON.stringify({requestId:'op-1'}));
  const payload=JSON.stringify({protocol:1,ok:true,session:'session-1',requestId:'op-1',result:{state:'ACTIVE'}});
  const response={id:'session-1',publicKey:device.encoded,payload,signature:sign('sha256',message('response','session-1',payload),device.privateKey).toString('base64')};
  assert.equal(decode(config,request,response).result.state,'ACTIVE');
  assert.throws(()=>decode(config,request,{...response,payload:payload.replace('ACTIVE','IDLE')}),/SIGNATURE/);
  assert.throws(()=>decode(config,request,{...response,publicKey:client.encoded}),/IDENTITY/);
  assert.throws(()=>decode(config,{...request,id:'session-2'},response),/IDENTITY/);
  const unrelated=payload.replace('op-1','op-2');assert.throws(()=>decode(config,request,{...response,payload:unrelated,signature:sign('sha256',message('response','session-1',unrelated),device.privateKey).toString('base64')}),/CORRELATION/);
});
test('other EC curves are rejected',()=>{const other=generateKeyPairSync('ec',{namedCurve:'secp256k1'});assert.throws(()=>publicKey(other.publicKey.export({format:'der',type:'spki'}).toString('base64')),/CURVE/);});
test('typed tools preserve persistent operation identity and reject scope injection',()=>{
  const args={operationId:'same-op',revision:'rev-123'};assert.deepEqual(command('hook_disable',args),{method:'hook.disable',params:{revision:'rev-123'},requestId:'same-op'});
  assert.throws(()=>command('hook_disable',{...args,project:'other'}),/FIELDS/);
  assert.throws(()=>command('hook_disable',{...args,revision:1}),/TYPE/);
  assert.throws(()=>command('hook_activate',{operationId:'x',sourceSha256:'bad',token:'t'}),/TYPE/);
});
test('MCP version, initialization, receipt and unknown outcome remain explicit',async()=>{
  const calls=[];const server=new McpServer({async call(method,params,id){calls.push({method,params,id});if(method==='hook.disable')throw Error('TEST_LINK_LOSS');return {protocol:1,ok:false,state:'FAILED',error:'PHONE_REVIEW_REQUIRED'};}});
  const rpc=(id,method,params)=>server.handle({jsonrpc:'2.0',id,method,params});
  assert.equal((await rpc(1,'initialize',{protocolVersion:'2025-11-25'})).error.code,-32602);
  assert.equal((await rpc(2,'initialize',{protocolVersion:MCP_VERSION})).result.protocolVersion,MCP_VERSION);
  assert.ok((await rpc(3,'tools/list')).error);
  await server.handle({jsonrpc:'2.0',method:'notifications/initialized'});
  assert.equal((await rpc(4,'tools/list')).result.tools.length,tools.length);
  const denied=await rpc(5,'tools/call',{name:'hook_status',arguments:{}});assert.equal(denied.result.isError,true);assert.equal(JSON.parse(denied.result.content[0].text).state,'FAILED');
  const unknown=await rpc(6,'tools/call',{name:'hook_disable',arguments:{operationId:'exact-id',revision:'rev-1'}});assert.equal(JSON.parse(unknown.result.content[0].text).state,'UNKNOWN');assert.equal(JSON.parse(unknown.result.content[0].text).operationId,'exact-id');assert.equal(calls.length,2);
});
test('MCP bounded batches preserve ids and omit notification responses',async()=>{
  const server=new McpServer({});const replies=await server.handle([{jsonrpc:'2.0',id:41,method:'ping'},{jsonrpc:'2.0',method:'notifications/cancelled'},{jsonrpc:'2.0',id:42,method:'ping'}]);
  assert.deepEqual(replies.map(row=>row.id),[41,42]);assert.equal((await server.handle([])).error.code,-32600);assert.equal((await server.handle(Array(9).fill({jsonrpc:'2.0',id:1,method:'ping'}))).error.code,-32600);
});
