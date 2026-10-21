import { readFile, access, open, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import { join, delimiter } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import net from 'node:net';
import { randomUUID, createHash } from 'node:crypto';
import { isAbsolute } from 'node:path';
const exec=promisify(execFile);
export const DEVICE_PORT=29731;
export const DEVICE_PROTOCOL=2;
async function resolveAdb(command){
  if(command!=='adb')return command;
  const name=process.platform==='win32'?'adb.exe':'adb';
  const candidates=[process.env.ADB,...(process.env.PATH||'').split(delimiter).filter(Boolean).map(p=>join(p,name)),
    ...[process.env.ANDROID_HOME,process.env.ANDROID_SDK_ROOT].filter(Boolean).map(p=>join(p,'platform-tools',name)),
    join(homedir(),'Library/Android/sdk/platform-tools',name),join(homedir(),'Android/Sdk/platform-tools',name),
    ...(process.env.LOCALAPPDATA?[join(process.env.LOCALAPPDATA,'Android/Sdk/platform-tools',name)]:[]),'/opt/homebrew/bin/adb','/usr/local/bin/adb'];
  for(const path of candidates.filter(Boolean))try{await access(path,constants.X_OK);return path;}catch{}
  return command;
}
export function exact(value,fields){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).sort().join('\0')!==[...fields].sort().join('\0'))throw Error('FIELDS_UNSUPPORTED');}
export function id(value){if(typeof value!=='string'||!/^[-A-Za-z0-9_]{1,80}$/.test(value))throw Error('ID_INVALID');return value;}
export function decode(request,response){
  if(!response||response.protocol!==DEVICE_PROTOCOL||typeof response.ok!=='boolean')throw Error('PROTOCOL_UNSUPPORTED');
  if(response.requestId!==request.requestId)throw Error('RESPONSE_CORRELATION_MISMATCH');return response;
}
// Old MCP configurations may still contain --config. Only the optional serial
// is migrated; keys, permissions and pairing sessions have no role anymore.
export async function loadConfig(file){
  if(!file)return {};
  try{const value=JSON.parse(await readFile(file,'utf8'));return typeof value.serial==='string'?{serial:value.serial}:{};}
  catch(error){if(error.code==='ENOENT')return {};throw Error('LEGACY_CONFIG_INVALID_USE_SERIAL');}
}
export class Transport {
  constructor(config={}, {adb='adb'}={}){this.config=config;this.adb=adb;this.port=null;this.queue=Promise.resolve();this.closed=false;this.opening=null;}
  async open(){
    if(this.closed)throw Error('TRANSPORT_CLOSED');if(this.port!==null)return;
    if(this.opening)return this.opening;
    this.opening=this.connect();try{await this.opening;}finally{this.opening=null;}
  }
  async connect(){
    this.adb=await resolveAdb(this.adb);
    let serial=this.config.serial||process.env.ANDROID_SERIAL;
    if(!serial){const result=await exec(this.adb,['devices'],{timeout:10000,maxBuffer:8192});const devices=result.stdout.split('\n').map(s=>s.trim().match(/^([^\s]+)\s+device$/)?.[1]).filter(Boolean);if(devices.length!==1)throw Error(devices.length?'MULTIPLE_ADB_DEVICES_USE_SERIAL':'NO_ADB_DEVICE_CONNECT_PHONE');serial=devices[0];}
    if(!/^[-A-Za-z0-9_.:]{1,100}$/.test(serial))throw Error('DEVICE_SERIAL_INVALID');
    const result=await exec(this.adb,['-s',serial,'forward','tcp:0',`tcp:${this.config.devicePort??DEVICE_PORT}`],{timeout:10000,maxBuffer:4096});
    const value=result.stdout.trim();if(!/^\d+$/.test(value)||+value<1||+value>65535)throw Error('ADB_FORWARD_PORT_INVALID');this.serial=serial;this.port=+value;
  }
  async exchange(request){
    await this.open();const body=Buffer.from(JSON.stringify(request));if(body.length>65536)throw Error('FRAME_TOO_LARGE');const header=Buffer.alloc(4);header.writeUInt32BE(body.length);
    const frame=await new Promise((resolve,reject)=>{
      const socket=net.connect({host:'127.0.0.1',port:this.port});let chunks=Buffer.alloc(0),size=null,done=false;
      const finish=(error,data)=>{if(done)return;done=true;socket.destroy();error?reject(error):resolve(data);};
      // Cold guest startup includes verification, adapter publication and Application.onCreate.
      const timeoutMs=request.method==='process.start'?65000:22000;
      socket.setTimeout(timeoutMs,()=>finish(Error('TRANSPORT_TIMEOUT_OUTCOME_UNKNOWN')));
      socket.on('connect',()=>socket.write(Buffer.concat([header,body])));
      socket.on('error',()=>finish(Error('TRIPODER_ENDPOINT_UNAVAILABLE_OPEN_APP')));
      socket.on('end',()=>{if(!done)finish(Error('TRANSPORT_EOF_OUTCOME_UNKNOWN'));});
      socket.on('data',chunk=>{chunks=Buffer.concat([chunks,chunk]);if(chunks.length>131076)return finish(Error('RESPONSE_FRAME_TOO_LARGE'));if(size===null&&chunks.length>=4){size=chunks.readUInt32BE(0);if(size<2||size>131072)return finish(Error('RESPONSE_FRAME_SIZE'));}if(size!==null&&chunks.length>=size+4){if(chunks.length!==size+4)return finish(Error('RESPONSE_TRAILING_DATA'));finish(null,chunks.subarray(4));}});
    });return decode(request,JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(frame)));
  }
  request(method,params,requestId){return this.exchange({protocol:DEVICE_PROTOCOL,requestId:id(requestId),method,params});}
  call(method,params,requestId){const job=this.queue.then(()=>this.perform(method,params,requestId));this.queue=job.catch(()=>{});return job;}
  async perform(method,params,requestId){
    if(method==='application.import')return this.importApks(params,requestId);
    return this.request(method,params,requestId);
  }
  async importApks(params,requestId){
    const files=[];
    try{
      for(const [index,path] of params.apkPaths.entries()){
        if(!isAbsolute(path))throw Error('APK_ABSOLUTE_PATH_REQUIRED');
        const handle=await open(path,'r');files.push({handle,name:index===0?'base.apk':`split-${index}.apk`});
        const info=await handle.stat();if(!info.isFile()||info.size<1||info.size>1024*1024*1024)throw Error('APK_FILE_SIZE');
        const digest=createHash('sha256');const buffer=Buffer.alloc(12288);let offset=0;
        while(offset<info.size){const {bytesRead}=await handle.read(buffer,0,Math.min(buffer.length,info.size-offset),offset);if(!bytesRead)throw Error('APK_FILE_CHANGED');digest.update(buffer.subarray(0,bytesRead));offset+=bytesRead;}
        Object.assign(files.at(-1),{size:info.size,sha256:digest.digest('hex')});
      }
      let reply=await this.request('import.begin',{space:params.space,expectedGeneration:params.expectedGeneration,files:files.map(({name,size,sha256})=>({name,size,sha256}))},requestId);
      if(!reply.ok||reply.result?.state==='SUCCEEDED')return reply;
      for(const [index,file] of files.entries()){
        let offset=reply.result?.offsets?.[index]??0;const buffer=Buffer.alloc(12288);
        while(offset<file.size){const {bytesRead}=await file.handle.read(buffer,0,Math.min(buffer.length,file.size-offset),offset);if(!bytesRead)throw Error('APK_FILE_CHANGED');
          const chunk=await this.request('import.write',{uploadId:requestId,index,offset,data:buffer.subarray(0,bytesRead).toString('base64')},randomUUID());if(!chunk.ok)return {...chunk,requestId,operationId:requestId};offset+=bytesRead;
        }
      }
      return await this.request('import.commit',{},requestId);
    }finally{await Promise.all(files.map(file=>file.handle.close()));}
  }
  async close(){if(this.closed)return;this.closed=true;if(this.opening)await this.opening.catch(()=>{});if(this.port!==null)await exec(this.adb,['-s',this.serial,'forward','--remove',`tcp:${this.port}`],{timeout:5000,maxBuffer:4096});}
}
