import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {Transport} from './transport.mjs';

test('full schema discovery has a finite budget independent of tool results',async()=>{
  let bytes=550000;
  const server=http.createServer((request,response)=>{
    request.resume();request.on('end',()=>{
      response.setHeader('content-type','application/json');
      response.end(JSON.stringify({jsonrpc:'2.0',id:1,result:{payload:'x'.repeat(bytes)}}));
    });
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const transport=new Transport({url:`http://127.0.0.1:${server.address().port}/mcp`});
  try{
    assert.equal((await transport.rpc({jsonrpc:'2.0',id:1,method:'tools/list'})).result.payload.length,bytes);
    await assert.rejects(transport.rpc({jsonrpc:'2.0',id:1,method:'tools/call'}),/MCP_RESPONSE_TOO_LARGE/);
    bytes=1100000;
    await assert.rejects(transport.rpc({jsonrpc:'2.0',id:1,method:'tools/list'}),/MCP_RESPONSE_TOO_LARGE/);
  }finally{await transport.close();await new Promise(resolve=>server.close(resolve));}
});
