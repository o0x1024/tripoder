import {open} from 'node:fs/promises';
import {constants} from 'node:fs';
import {isAbsolute} from 'node:path';
import https from 'node:https';
import {X509Certificate,createHash,randomUUID} from 'node:crypto';
import {localImportTool,command,operationCall} from './tools.mjs';

export const MCP_VERSION='2025-11-25';
export async function loadConfig(path){
  if(!path||!isAbsolute(path))throw Error('CONNECTION_ABSOLUTE_PATH_REQUIRED');
  const handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try{const info=await handle.stat();if(!info.isFile()||info.size>32768||process.platform!=='win32'&&((info.mode&0o077)!==0||info.uid!==process.getuid()))throw Error('CONNECTION_FILE_PERMISSIONS_USE_0600');let parsed;try{parsed=JSON.parse(await handle.readFile('utf8'));}catch{throw Error('CONNECTION_CONFIG_INVALID');}return validateConfig(parsed);}finally{await handle.close();}
}
export function validateConfig(config){
  if(!config||Object.keys(config).sort().join(',')!=='certificatePem,token,url')throw Error('CONNECTION_FIELDS_INVALID');
  const url=new URL(config.url);if(url.protocol!=='https:'||url.pathname!=='/mcp'||url.username||url.password||url.hash||url.search)throw Error('CONNECTION_HTTPS_MCP_REQUIRED');
  if(typeof config.token!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(config.token))throw Error('CONNECTION_TOKEN_INVALID');
  const cert=new X509Certificate(config.certificatePem);if(!cert.checkIP(url.hostname)&&!cert.checkHost(url.hostname))throw Error('CONNECTION_CERTIFICATE_ADDRESS_MISMATCH');
  return {...config,fingerprint:cert.fingerprint256};
}
export class Transport {
  constructor(config){this.config=config;this.session=null;this.ready=false;this.closed=false;this.sequence=0;this.localPrefix=randomUUID();this.queue=Promise.resolve();}
  async rpc(request){
    if(this.closed)throw Object.assign(Error('TRANSPORT_CLOSED'),{dispatched:false});
    const body=Buffer.from(JSON.stringify(request));if(body.length>65536)throw Object.assign(Error('MCP_FRAME_TOO_LARGE'),{dispatched:false});
    const headers={'content-type':'application/json','accept':'application/json, text/event-stream','authorization':'Bearer '+this.config.token,'content-length':body.length};
    if(this.session){headers['mcp-session-id']=this.session;headers['mcp-protocol-version']=MCP_VERSION;}
    return new Promise((resolve,reject)=>{
      let sent=false,done=false;let deadline;const fail=(code,dispatched=sent)=>{if(done)return;done=true;clearTimeout(deadline);const error=Object.assign(Error(code),{dispatched});reject(error);};
      const req=https.request(this.config.url,{method:'POST',headers,ca:this.config.certificatePem,rejectUnauthorized:true,minVersion:'TLSv1.3',maxVersion:'TLSv1.3',agent:false,checkServerIdentity:(_host,cert)=>cert.fingerprint256===this.config.fingerprint?undefined:Error('MCP_CERTIFICATE_MISMATCH')},response=>{
        const knownRejected=[400,401,403,404,405,406,413,415,429,503].includes(response.statusCode);
        if(response.statusCode!==200&&response.statusCode!==202){response.resume();fail('MCP_HTTP_'+response.statusCode,!knownRejected);return;}
        const chunks=[];let size=0;response.on('data',chunk=>{size+=chunk.length;if(size>524288){fail('MCP_RESPONSE_TOO_LARGE');req.destroy();}else chunks.push(chunk);});
        response.on('aborted',()=>fail('MCP_RESPONSE_INTERRUPTED'));
        response.on('error',()=>fail('MCP_RESPONSE_INTERRUPTED'));
        response.on('end',()=>{if(done)return;try{
          const bytes=Buffer.concat(chunks);if(response.statusCode===202){if(request.id!==undefined||bytes.length)throw Error('MCP_NOTIFICATION_RESPONSE_INVALID');done=true;clearTimeout(deadline);resolve(null);return;}
          if(!(response.headers['content-type']??'').startsWith('application/json'))throw Error('MCP_RESPONSE_TYPE_INVALID');
          const value=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));if(value.jsonrpc!=='2.0'||value.id!==request.id)throw Error('MCP_RESPONSE_CORRELATION_MISMATCH');
          if(request.method==='initialize'&&!value.error){const session=response.headers['mcp-session-id'];if(typeof session!=='string'||!session)throw Error('MCP_SESSION_MISSING');this.session=session;}
          done=true;clearTimeout(deadline);resolve(value);
        }catch(error){fail(error.message);}});
      });
      deadline=setTimeout(()=>{fail(sent?'TRANSPORT_TIMEOUT_OUTCOME_UNKNOWN':'MCP_CONNECTION_TIMEOUT');req.destroy();},75000);
      req.on('socket',socket=>{socket.once('secureConnect',()=>{sent=true;});});
      req.on('error',error=>fail(sent?'TRANSPORT_INTERRUPTED_OUTCOME_UNKNOWN':'MCP_CONNECTION_FAILED:'+error.code));req.end(body);
    });
  }
  async tool(name,args){
    const response=await this.rpc({jsonrpc:'2.0',id:'local-'+this.localPrefix+'-'+(++this.sequence),method:'tools/call',params:{name,arguments:args}});if(response.error)throw Object.assign(Error(response.error.message),{dispatched:false});
    const receipt=JSON.parse(response.result.content[0].text);return receipt;
  }
  async operation(method,args){const call=operationCall(method,args);return this.tool(call.name,call.arguments);}
  async importApks(args){
    command('application_import',args);const files=[];let dispatched=false;
    try{
      for(const [index,path] of args.apkPaths.entries()){
        if(!isAbsolute(path))throw Error('APK_ABSOLUTE_PATH_REQUIRED');const handle=await open(path,'r');files.push({handle,name:index===0?'base.apk':`split-${index}.apk`});const info=await handle.stat();
        if(!info.isFile()||info.size<1||info.size>1073741824)throw Error('APK_FILE_SIZE');const digest=createHash('sha256'),buffer=Buffer.alloc(12288);let offset=0;
        while(offset<info.size){const {bytesRead}=await handle.read(buffer,0,Math.min(buffer.length,info.size-offset),offset);if(!bytesRead)throw Error('APK_FILE_CHANGED');digest.update(buffer.subarray(0,bytesRead));offset+=bytesRead;}
        Object.assign(files.at(-1),{size:info.size,sha256:digest.digest('hex')});
      }
      const uploadId=args.operationId;let receipt=await this.operation('import.begin',{operationId:uploadId,space:args.space,expectedGeneration:args.expectedGeneration,files:files.map(({name,size,sha256})=>({name,size,sha256}))});dispatched=true;
      if(!receipt.ok||receipt.result?.state==='SUCCEEDED')return receipt;
      for(const [index,file] of files.entries()){
        let offset=receipt.result.offsets[index];const buffer=Buffer.alloc(12288);
        while(offset<file.size){const {bytesRead}=await file.handle.read(buffer,0,Math.min(buffer.length,file.size-offset),offset);if(!bytesRead)throw Error('APK_FILE_CHANGED');
          const chunk=await this.operation('import.write',{operationId:randomUUID(),uploadId,index,offset,data:buffer.subarray(0,bytesRead).toString('base64')});if(!chunk.ok)return {...chunk,operationId:uploadId};offset+=bytesRead;
        }
      }
      return await this.operation('import.commit',{operationId:uploadId});
    }catch(error){if(dispatched)error.dispatched=true;else if(error.dispatched===undefined)error.dispatched=false;throw error;}
    finally{await Promise.all(files.map(file=>file.handle.close()));}
  }
  async close(){
    if(this.closed)return;this.closed=true;if(!this.session)return;
    await new Promise(resolve=>{const timer=setTimeout(()=>req.destroy(),3000);const done=()=>{clearTimeout(timer);resolve();};const req=https.request(this.config.url,{method:'DELETE',ca:this.config.certificatePem,rejectUnauthorized:true,minVersion:'TLSv1.3',agent:false,headers:{authorization:'Bearer '+this.config.token,'mcp-session-id':this.session,'mcp-protocol-version':MCP_VERSION}},res=>{res.resume();res.on('end',done);});req.on('error',done);req.end();});this.session=null;
  }
}
export {localImportTool};
