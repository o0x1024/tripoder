#!/usr/bin/env node
import {loadConfig,Transport,MCP_VERSION} from '../agent/transport.mjs';
import {pullFiles} from '../agent/file-pull.mjs';
const [connection,operationId,sourceJson,destination]=process.argv.slice(2);
if(!destination)throw Error('Usage: node tools/file-pull.mjs CONNECTION_FILE OPERATION_ID SOURCE_JSON ABSOLUTE_DESTINATION');
const transport=new Transport(await loadConfig(connection)),controller=new AbortController();process.once('SIGINT',()=>controller.abort());
try{
 await transport.rpc({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'tripoder-file-pull',version:'1'}}});
 await transport.rpc({jsonrpc:'2.0',method:'notifications/initialized'});
 const result=await pullFiles(transport,{operationId,source:JSON.parse(sourceJson),destination,followSymlinks:false},{signal:controller.signal,onProgress:p=>process.stderr.write(JSON.stringify(p)+'\n')});process.stdout.write(JSON.stringify(result)+'\n');
}finally{await transport.close();}
