#!/usr/bin/env node
import {loadConfig,Transport,localImportTool,MCP_VERSION} from './transport.mjs';
import {isMutation,command,localPullTools,referenceMigrations} from './tools.mjs';
import {PullManager} from './file-pull.mjs';
import {verifyArtifact} from './artifact-verify.mjs';
import {recoveryReceipt} from './recovery.mjs';
import {auditFile} from './audit.mjs';

/** Stdio transport adapter. All shared MCP semantics and commands live on the phone. */
export class McpServer {
  constructor(transport,audit,options={}){this.options=options;this.transport=transport;this.audit=audit;this.ready=false;this.pulls=new PullManager(transport);}
  async handle(request){
    let response;
    if(!request||Array.isArray(request)||request.jsonrpc!=='2.0'||typeof request.method!=='string')response={jsonrpc:'2.0',id:request?.id??null,error:{code:-32600,message:'Invalid Request'}};
    else try{
      const replacement=referenceMigrations[request.params?.name];
      if(this.ready&&request.id!==undefined&&request.method==='tools/call'&&replacement&&replacement.tool!==request.params.name){
        response={jsonrpc:'2.0',id:request.id,result:{content:[{type:'text',text:JSON.stringify({ok:false,state:'REJECTED',dispatched:false,errorCode:'TOOL_RETIRED',replacement:{tool:replacement.tool,action:replacement.action},recovery:{action:'Refresh tools and use the replacement action schema. No command was dispatched.',replayAllowed:false}})}],isError:true}};
      }else if(this.ready&&request.id!==undefined&&request.method==='tools/call'&&localPullTools.some(t=>t.name===request.params?.name)){
        const name=request.params.name,args=request.params.arguments??{},wire=command(name,args);const pullArgs={...args};delete pullArgs.action;
        const result=wire.method==='local.artifactVerify'?await verifyArtifact(args,{...this.options,transport:this.transport}):wire.method==='local.filePull'?this.pulls.start(pullArgs):wire.method==='local.filePullStatus'?this.pulls.status(args.operationId):this.pulls.cancel(args.operationId);
        const ok=result.state!=='FAILED';
        response={jsonrpc:'2.0',id:request.id,result:{content:[{type:'text',text:JSON.stringify({ok,result,...(!ok&&wire.method==='local.artifactVerify'?{errorCode:'ARTIFACT_ACCEPTANCE_FAILED'}:{})})}],isError:!ok}};
      }else if(this.ready&&request.id!==undefined&&request.method==='tools/call'&&request.params?.name==='application_import'){
        let receipt;try{receipt=await this.transport.importApks(request.params.arguments??{});}catch(error){receipt={protocol:2,ok:false,state:error.dispatched?'UNKNOWN':'REJECTED',dispatched:!!error.dispatched,operationId:request.params.arguments?.operationId,error:error.message};}
        response={jsonrpc:'2.0',id:request.id,result:{content:[{type:'text',text:JSON.stringify(recoveryReceipt(receipt,'application_import',request.params.arguments??{}))}],isError:!receipt.ok}};
      }else{
        response=await this.transport.rpc(request);
        if(request.method==='tools/call'&&response?.result?.content?.[0]?.type==='text'){
          const block=response.result.content[0];block.text=JSON.stringify(recoveryReceipt(JSON.parse(block.text),request.params.name,request.params.arguments??{}));
        }
        if(request.method==='notifications/initialized')this.ready=true;
        if(request.method==='tools/list'&&response?.result?.tools){
          if(response.result.tools.some(tool=>tool._meta?.['dev.tripoder/contractVersion']!==3))throw Object.assign(Error('MCP_CATALOG_VERSION_MISMATCH: update Tripoder and the local adapter together, then refresh tools'),{dispatched:false});
          response.result.tools.push(...localPullTools,localImportTool);
        }
      }
    }catch(error){
      if(request.id===undefined)throw error;
      if(request.method==='tools/call'){
        const args=request.params?.arguments??{},mutation=isMutation(request.params?.name);const receipt=recoveryReceipt({protocol:2,ok:false,state:error.dispatched&&mutation?'UNKNOWN':error.dispatched?'UNAVAILABLE':'REJECTED',...(error.dispatched===false?{dispatched:false}:{}),...(mutation?{operationId:args.operationId}:{}),error:error.message,...(error.issues?{errorCode:error.errorCode,issues:error.issues}:{})},request.params?.name,args);
        response={jsonrpc:'2.0',id:request.id,result:{content:[{type:'text',text:JSON.stringify(receipt)}],isError:true}};
      }else response={jsonrpc:'2.0',id:request.id,error:{code:-32000,message:error.message}};
    }
    this.audit?.record(request,response);return response;
  }
}
async function main(){
  const options={},args=process.argv.slice(2);for(let i=0;i<args.length;i+=2){if(!['--connection','--audit','--dex-validator'].includes(args[i])||!args[i+1]||options[args[i]])throw Error('Usage: server.mjs --connection ABSOLUTE_PATH [--audit ABSOLUTE_PATH]');options[args[i]]=args[i+1];}
  const config=await loadConfig(options['--connection']);const audit=options['--audit']?auditFile(options['--audit']):undefined;
  const transport=new Transport(config),server=new McpServer(transport,audit,{dexValidator:options['--dex-validator']});let buffer=Buffer.alloc(0),chain=Promise.resolve(),queued=0,closing=false;
  const close=async()=>{if(closing)return;closing=true;await server.pulls.close();await transport.close();audit?.close();};
  process.stdin.on('data',chunk=>{
    buffer=Buffer.concat([buffer,chunk]);if(buffer.length>65536){process.stderr.write('MCP_FRAME_TOO_LARGE\n');process.stdin.destroy();void close();return;}
    let boundary;while((boundary=buffer.indexOf(10))>=0){const line=buffer.subarray(0,boundary);buffer=buffer.subarray(boundary+1);if(++queued>32){process.stderr.write('MCP_QUEUE_BUDGET_EXCEEDED\n');process.stdin.destroy();void close();return;}
      chain=chain.then(async()=>{try{let request;try{request=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(line));}catch{process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:null,error:{code:-32700,message:'Parse error'}})+'\n');return;}
        const response=await server.handle(request);if(response!==null)process.stdout.write(JSON.stringify(response)+'\n');
      }catch(error){process.stderr.write(error.message+'\n');process.stdin.destroy();await close();}finally{queued--;}});
    }
  });
  process.stdin.on('end',()=>{void chain.finally(close);});process.stdin.on('error',()=>{void chain.finally(close);});for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{void chain.finally(close).finally(()=>process.exit(0));});
}
if(import.meta.url===new URL(process.argv[1],'file:').href)main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
export {MCP_VERSION};
