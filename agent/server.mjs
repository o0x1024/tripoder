#!/usr/bin/env node
import { loadConfig, Transport } from './transport.mjs';
import { tools, command } from './tools.mjs';
import { randomUUID } from 'node:crypto';
import { auditFile } from './audit.mjs';

// Base tools lifecycle is shared by these published MCP versions. Unknown
// versions negotiate the latest supported version instead of closing stdio.
export const MCP_VERSION='2025-11-25';
export const MCP_VERSIONS=['2024-11-05','2025-03-26','2025-06-18','2025-11-25'];
export class McpServer {
  constructor(transport,audit){this.transport=transport;this.audit=audit;this.initialized=false;this.ready=false;}
  async handle(request){
    const response=await this.dispatch(request);this.audit?.record(request,response);return response;
  }
  async dispatch(request){
    if(Array.isArray(request)){
      if(request.length===0||request.length>8)return {jsonrpc:'2.0',id:null,error:{code:-32600,message:'Batch must contain 1 to 8 messages'}};
      const responses=[];for(const item of request){const response=await this.handle(item);if(response!==null)responses.push(response);}return responses.length?responses:null;
    }
    if(!request||request.jsonrpc!=='2.0'||typeof request.method!=='string')return {jsonrpc:'2.0',id:request?.id??null,error:{code:-32600,message:'Invalid Request'}};
    const notification=request.id===undefined;
    if(notification){if(request.method==='notifications/initialized'&&this.initialized)this.ready=true;return null;}
    const reply=result=>({jsonrpc:'2.0',id:request.id,result});
    const error=(code,message)=>({jsonrpc:'2.0',id:request.id,error:{code,message}});
    if(request.method==='initialize'){
      if(this.initialized||typeof request.params?.protocolVersion!=='string')return error(-32602,'MCP_INITIALIZE_INVALID');
      this.protocol=MCP_VERSIONS.includes(request.params.protocolVersion)?request.params.protocolVersion:MCP_VERSION;
      this.initialized=true;return reply({protocolVersion:this.protocol,capabilities:{tools:{listChanged:false}},serverInfo:{name:'tripoder',version:'0.1.0'},instructions:'Tripoder requires no authentication or pairing. Use targets_list and target_select to choose a running process. Read content JSON ok and state; unknown mutations require operation_get, never automatic replay. Closing MCP does not stop the guest or disable Hooks.'});
    }
    if(request.method==='ping')return reply({});
    if(!this.ready)return error(-32000,'MCP_NOT_INITIALIZED');
    if(request.method==='tools/list')return reply({tools:this.protocol==='2024-11-05'?tools.map(({annotations,...tool})=>tool):tools});
    if(request.method==='tools/call'){
      let call;try{call=command(request.params?.name,request.params?.arguments??{});}catch(failure){return error(-32602,failure.message);}
      try{
        const receipt=await this.transport.call(call.method,call.params,call.requestId);
        return reply({content:[{type:'text',text:JSON.stringify(receipt)}],isError:!receipt.ok});
      }catch(failure){const mutation=request.params?.arguments?.operationId!==undefined;return reply({content:[{type:'text',text:JSON.stringify({protocol:2,ok:false,operationId:call.requestId,state:mutation?'UNKNOWN':'UNAVAILABLE',error:failure.message,action:mutation?'Query operation_get using this id. Do not repeat side effects with a new id.':'Open Tripoder on the connected phone, then list targets.'})}],isError:true});}
    }
    return error(-32601,'Method not found');
  }
}
async function main(){
  const options={};const args=process.argv.slice(2);
  for(let i=0;i<args.length;i+=2){if(!['--serial','--config','--audit'].includes(args[i])||!args[i+1]||options[args[i]])throw Error('Usage: server.mjs [--serial SERIAL] [--audit ABSOLUTE_PATH]');options[args[i]]=args[i+1];}
  const config=await loadConfig(options['--config']);if(options['--serial'])config.serial=options['--serial'];
  const audit=options['--audit']?auditFile(options['--audit']):undefined;
  const transport=new Transport(config); // Lazy: initialize/tools/list work even with no phone attached.
  const server=new McpServer(transport,audit);let buffered=Buffer.alloc(0),chain=Promise.resolve(),busy=false,closing=false,queued=0;
  const send=response=>{if(response!==null)process.stdout.write(JSON.stringify(response)+'\n');};
  const close=async()=>{if(closing)return;closing=true;await transport.close();audit?.close();};
  process.stdin.on('data',chunk=>{
    buffered=Buffer.concat([buffered,chunk]);if(buffered.length>65536){process.stderr.write('MCP_FRAME_TOO_LARGE\n');process.stdin.destroy();void close();return;}
    let boundary;while((boundary=buffered.indexOf(10))>=0){const line=buffered.subarray(0,boundary);buffered=buffered.subarray(boundary+1);if(++queued>32){process.stderr.write('MCP_QUEUE_BUDGET_EXCEEDED\n');process.stdin.destroy();void close();return;}chain=chain.then(async()=>{try{let request;try{request=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(line));}catch{send({jsonrpc:'2.0',id:null,error:{code:-32700,message:'Parse error'}});return;}busy=true;try{send(await server.handle(request));}finally{busy=false;}}finally{queued--;}});}
  });
  process.stdin.on('end',()=>{void chain.finally(close);});process.stdin.on('error',()=>{void close();});
  for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{void close().finally(()=>process.exit(0));});
}
if(import.meta.url===new URL(process.argv[1],'file:').href)main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
