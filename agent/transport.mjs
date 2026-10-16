import { createPrivateKey, createPublicKey, createHash, sign, verify } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import net from 'node:net';

const exec = promisify(execFile);
export const DEVICE_PORT = 29731;
export function exact(value, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).sort().join('\0') !== [...fields].sort().join('\0')) throw Error('FIELDS_UNSUPPORTED');
}
export function id(value) { if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(value)) throw Error('ID_INVALID'); return value; }
export function publicKey(encoded) {
  if (typeof encoded !== 'string' || encoded.length > 256) throw Error('KEY_INVALID');
  const key=createPublicKey({key:Buffer.from(encoded,'base64'),format:'der',type:'spki'});
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1') throw Error('KEY_CURVE_UNSUPPORTED');
  if (key.export({format:'der',type:'spki'}).toString('base64') !== encoded) throw Error('KEY_ENCODING_INVALID');
  return key;
}
export function fingerprint(encoded) { return createHash('sha256').update(publicKey(encoded).export({format:'der',type:'spki'})).digest('hex'); }
export function message(direction, session, payload) { return Buffer.from(`tripoder-agent-v1\n${direction}\n${session}\n${payload}`); }
export function envelope(config, kind, session, payload) {
  const key=createPrivateKey(config.privateKey);
  if(key.asymmetricKeyType!=='ec'||key.asymmetricKeyDetails.namedCurve!=='prime256v1')throw Error('PRIVATE_KEY_CURVE_UNSUPPORTED');
  return {kind,id:id(session),payload,signature:sign('sha256',message(kind==='pair'?'pair':'request',session,payload),key).toString('base64')};
}
export function decode(config, request, response) {
  exact(response,['id','publicKey','payload','signature']);
  if(response.id!==request.id||response.publicKey!==config.devicePublicKey)throw Error('DEVICE_IDENTITY_MISMATCH');
  if(typeof response.payload!=='string'||typeof response.signature!=='string'||response.signature.length>256)throw Error('RESPONSE_INVALID');
  if(!verify('sha256',message('response',response.id,response.payload),publicKey(config.devicePublicKey),Buffer.from(response.signature,'base64')))throw Error('DEVICE_SIGNATURE_REJECTED');
  const result=JSON.parse(response.payload);
  if(result.protocol!==1||typeof result.ok!=='boolean')throw Error('PROTOCOL_UNSUPPORTED');
  const input=JSON.parse(request.payload);
  if(request.kind==='call'&&(result.requestId!==input.requestId||(result.ok&&result.session!==request.id)))throw Error('RESPONSE_CORRELATION_MISMATCH');
  if(request.kind==='pair'&&result.ok&&result.nonce!==request.id)throw Error('PAIR_CORRELATION_MISMATCH');
  return result;
}
export async function loadConfig(file, paired=true) {
  const info=await stat(file);if(!info.isFile()||(info.mode&0o077)!==0)throw Error('PRIVATE_CONFIG_REQUIRES_MODE_0600');
  const config=JSON.parse(await readFile(file,'utf8'));
  exact(config,['protocol','serial','name','privateKey','publicKey','devicePublicKey','session']);
  if(config.protocol!==1||!/^[-A-Za-z0-9_.:]{1,100}$/.test(config.serial)||!/^[-A-Za-z0-9 _.]{1,60}$/.test(config.name))throw Error('CONFIG_INVALID');
  publicKey(config.publicKey);publicKey(config.devicePublicKey);
  const derived=createPublicKey(createPrivateKey(config.privateKey)).export({format:'der',type:'spki'}).toString('base64');
  if(derived!==config.publicKey)throw Error('CLIENT_KEY_MISMATCH');
  if(paired)id(config.session);
  return config;
}
export class Transport {
  constructor(config,{adb='adb'}={}) {this.config=config;this.adb=adb;this.port=null;this.queue=Promise.resolve();this.closed=false;}
  async open(){
    if(this.port!==null||this.closed)throw Error('TRANSPORT_ALREADY_USED');
    const result=await exec(this.adb,['-s',this.config.serial,'forward','tcp:0',`tcp:${DEVICE_PORT}`],{timeout:10000,maxBuffer:4096});
    const value=result.stdout.trim();if(!/^\d+$/.test(value)||+value<1||+value>65535)throw Error('ADB_FORWARD_PORT_INVALID');this.port=+value;
  }
  send(request) {
    const job=this.queue.then(()=>this.exchange(request));this.queue=job.catch(()=>{});return job;
  }
  async exchange(request) {
    if(!this.port||this.closed)throw Error('TRANSPORT_CLOSED');
    const body=Buffer.from(JSON.stringify(request));if(body.length>65536)throw Error('FRAME_TOO_LARGE');
    const header=Buffer.alloc(4);header.writeUInt32BE(body.length);
    const frame=await new Promise((resolve,reject)=>{
      const socket=net.connect({host:'127.0.0.1',port:this.port});let chunks=Buffer.alloc(0),size=null,done=false;
      const finish=(error,data)=>{if(done)return;done=true;socket.destroy();error?reject(error):resolve(data);};
      socket.setTimeout(22000,()=>finish(Error('TRANSPORT_TIMEOUT_OUTCOME_UNKNOWN')));
      socket.on('connect',()=>socket.write(Buffer.concat([header,body])));
      socket.on('error',()=>finish(Error('TRANSPORT_ERROR_OUTCOME_UNKNOWN')));
      socket.on('end',()=>{if(!done)finish(Error('TRANSPORT_EOF_OUTCOME_UNKNOWN'));});
      socket.on('data',chunk=>{
        chunks=Buffer.concat([chunks,chunk]);if(chunks.length>131076)return finish(Error('RESPONSE_FRAME_TOO_LARGE'));
        if(size===null&&chunks.length>=4){size=chunks.readUInt32BE(0);if(size<2||size>131072)return finish(Error('RESPONSE_FRAME_SIZE'));}
        if(size!==null&&chunks.length>=size+4){if(chunks.length!==size+4)return finish(Error('RESPONSE_TRAILING_DATA'));finish(null,chunks.subarray(4));}
      });
    });
    return decode(this.config,request,JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(frame)));
  }
  async call(method,params,requestId){const payload=JSON.stringify({protocol:1,requestId:id(requestId),method,params});if(Buffer.byteLength(payload)>32000)throw Error('REQUEST_TOO_LARGE');return this.send(envelope(this.config,'call',this.config.session,payload));}
  async close(){if(this.closed)return;this.closed=true;if(this.port!==null)await exec(this.adb,['-s',this.config.serial,'forward','--remove',`tcp:${this.port}`],{timeout:5000,maxBuffer:4096});}
}
