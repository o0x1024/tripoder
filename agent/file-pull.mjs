import {open,mkdir,lstat,readdir,readFile,writeFile,rename,link,unlink} from 'node:fs/promises';
import {constants} from 'node:fs';
import {resolve,dirname,join,isAbsolute} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {operationCall} from './tools.mjs';

const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
export async function rpcTool(transport,method,args){const call=operationCall(method,args);const receipt=await transport.tool(call.name,call.arguments);if(!receipt.ok)throw Error(receipt.errorCode??receipt.error??'FILE_PULL_REMOTE_ERROR');return receipt.result;}
async function exists(path){try{return await lstat(path);}catch(error){if(error.code==='ENOENT')return null;throw error;}}
async function safeParents(path){const parent=dirname(path);if(parent!==path)await safeParents(parent);const stat=await exists(path);if(stat){if(stat.isSymbolicLink()||!stat.isDirectory())throw Error('FILE_PULL_UNSAFE_PARENT');}else await mkdir(path,{mode:0o700});}
async function atomicJson(path,value){const temp=path+'.tmp';const out=await open(temp,constants.O_WRONLY|constants.O_CREAT|constants.O_TRUNC|constants.O_NOFOLLOW,0o600);try{await out.writeFile(JSON.stringify(value));await out.sync();}finally{await out.close();}await rename(temp,path);}
export function validateManifest(entries){
  if(!Array.isArray(entries)||entries.length<1||entries.length>10000)throw Error('FILE_PULL_MANIFEST_INVALID');
  const ids=new Set(),names=new Map(),directories=new Set();let total=0;
  for(const [index,e] of entries.entries()){
    if(!/^e[0-9]{1,5}$/.test(e.entryId)||ids.has(e.entryId)||!['file','directory'].includes(e.type)||typeof e.path!=='string'||e.path.length>4096||e.path.includes('\\')||e.path.includes('\0')||e.path.startsWith('/')||e.path.split('/').some(p=>p==='.'||p==='..'||(e.path!==''&&p==='')))throw Error('FILE_PULL_MANIFEST_INVALID');
    if(index===0?e.path!=='':e.path==='')throw Error('FILE_PULL_MANIFEST_ROOT');
    const key=e.path.normalize('NFC').toLowerCase();if(names.has(key))throw Error('FILE_PULL_NAME_COLLISION');
    if(e.path){const parent=e.path.includes('/')?e.path.slice(0,e.path.lastIndexOf('/')):'';if(!directories.has(parent))throw Error('FILE_PULL_MANIFEST_PARENT');}
    if(e.type==='file'){if(!Number.isSafeInteger(e.size)||e.size<0||e.size>1073741824||!/^[a-f0-9]{64}$/.test(e.sha256))throw Error('FILE_PULL_MANIFEST_FILE');total+=e.size;}
    ids.add(e.entryId);names.set(key,e.type);if(e.type==='directory')directories.add(e.path);
  }
  if(total>4294967296)throw Error('FILE_PULL_SIZE_LIMIT');return total;
}
async function hashFile(path){const file=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);try{const digest=createHash('sha256');for await(const part of file.createReadStream({autoClose:false}))digest.update(part);return digest.digest('hex');}finally{await file.close();}}

/** Programmatic receiver; bulk bytes stay out of tool results and audit records. */
export async function pullFiles(transport,args,{signal,onProgress=()=>{}}={}){
  if(!/^[A-Za-z0-9_-]{1,80}$/.test(args.operationId)||!isAbsolute(args.destination))throw Error('FILE_PULL_ARGUMENTS');
  let destination=resolve(args.destination);
  if(process.platform==='darwin')for(const alias of ['/var/','/tmp/'])if(destination.startsWith(alias))destination='/private'+destination;
  const sidecar=destination+'.manifest.json',stage=destination+'.tripoder-'+args.operationId+'.part';
  await safeParents(dirname(destination));
  const identity=sha(JSON.stringify(canonical({server:sha(JSON.stringify(canonical(transport.config??{}))),source:args.source,destination,followSymlinks:args.followSymlinks})));
  const stageStat=await exists(stage);if(stageStat&&(stageStat.isSymbolicLink()||!stageStat.isDirectory()||process.platform!=='win32'&&(stageStat.uid!==process.getuid()||(stageStat.mode&0o077)!==0)))throw Error('FILE_PULL_UNSAFE_STAGE');
  if(!stageStat)await mkdir(stage,{mode:0o700});
  const lockPath=join(stage,'lock');
  let lock;
  try{lock=await open(lockPath,constants.O_CREAT|constants.O_EXCL|constants.O_WRONLY,0o600);}catch(error){
    if(error.code!=='EEXIST')throw error;const info=await lstat(lockPath);if(!info.isFile()||info.isSymbolicLink())throw Error('FILE_PULL_UNSAFE_LOCK');
    let pid;try{pid=Number(await readFile(lockPath,'utf8'));}catch{}if(!Number.isInteger(pid)||pid<1)throw Error('FILE_PULL_ALREADY_RUNNING');
    let alive=true;try{process.kill(pid,0);}catch(e){if(e.code==='ESRCH')alive=false;}if(alive)throw Error('FILE_PULL_ALREADY_RUNNING');
    await unlink(lockPath);lock=await open(lockPath,constants.O_CREAT|constants.O_EXCL|constants.O_WRONLY,0o600);
  }
  await lock.writeFile(String(process.pid));await lock.sync();
  let state;const statePath=join(stage,'state.json');
  const check=()=>{if(signal?.aborted)throw Error('FILE_PULL_PAUSED');};
  try{
    const prior=await exists(statePath);if(prior?.isSymbolicLink())throw Error('FILE_PULL_UNSAFE_STATE');
    state=prior?JSON.parse(await readFile(statePath,'utf8')):{identity,exportId:args.operationId,state:'PREPARING'};
    if(state.identity!==identity)throw Error('FILE_PULL_RESUME_IDENTITY_MISMATCH');
    if(prior&&await exists(destination)&&await exists(sidecar)&&await exists(join(stage,'manifest.json'))){
      if(!Buffer.from(await readFile(sidecar)).equals(await readFile(join(stage,'manifest.json'))))throw Error('FILE_PULL_COMMITTED_MANIFEST_CHANGED');
      const manifest=JSON.parse(await readFile(sidecar,'utf8'));validateManifest(manifest.entries);
      if(manifest.exportId!==state.exportId)throw Error('FILE_PULL_COMMITTED_MANIFEST_CHANGED');
      for(const e of manifest.entries){const path=manifest.entries[0].type==='file'?destination:join(destination,e.path);if(e.type==='file'){if(await hashFile(path)!==e.sha256)throw Error('FILE_PULL_COMMITTED_FILE_CHANGED');}else{const stat=await lstat(path);if(!stat.isDirectory()||stat.isSymbolicLink())throw Error('FILE_PULL_COMMITTED_DIRECTORY_CHANGED');}}
      state={...state,state:'COMPLETE',destination,manifestPath:sidecar,totalBytes:manifest.totalBytes,files:manifest.entries.filter(e=>e.type==='file').length};await atomicJson(statePath,state);return state;
    }
    if(prior&&!await exists(destination)&&await exists(sidecar)&&await exists(join(stage,'manifest.json'))){if(!Buffer.from(await readFile(sidecar)).equals(await readFile(join(stage,'manifest.json'))))throw Error('FILE_PULL_DESTINATION_EXISTS');await unlink(sidecar);}
    if(await exists(destination)||await exists(sidecar))throw Error('FILE_PULL_DESTINATION_EXISTS');
    await atomicJson(statePath,state);check();
    // Query a previously accepted export; only a first start is allowed to prepare it.
    let remote;
    if(prior&&state.remoteBeginAttempted)remote=await rpcTool(transport,'file.exportStatus',{exportId:state.exportId});
    else {state.remoteBeginAttempted=true;await atomicJson(statePath,state);remote=await rpcTool(transport,'file.exportBegin',{operationId:args.operationId,source:args.source,followSymlinks:args.followSymlinks??false});}
    while(remote.state==='PREPARING'){check();onProgress({state:'PREPARING',exportId:state.exportId,bytes:remote.bytes});await new Promise(r=>setTimeout(r,150));remote=await rpcTool(transport,'file.exportStatus',{exportId:state.exportId});}
    if(remote.state!=='READY')throw Error(remote.errorCode??'FILE_PULL_EXPORT_'+remote.state);
    const entries=[];let cursor=0;
    do{check();const page=await rpcTool(transport,'file.exportManifest',{exportId:state.exportId,cursor,limit:128});if(page.exportId!==state.exportId||!Array.isArray(page.entries)||!Number.isInteger(page.nextCursor)||page.nextCursor!==-1&&page.nextCursor<=cursor)throw Error('FILE_PULL_MANIFEST_PAGE');entries.push(...page.entries);if(entries.length>10000)throw Error('FILE_PULL_MANIFEST_LIMIT');cursor=page.nextCursor;}while(cursor!==-1);
    const total=validateManifest(entries),manifestSha=sha(JSON.stringify(entries));
    if(state.manifestSha&&state.manifestSha!==manifestSha)throw Error('FILE_PULL_MANIFEST_CHANGED');state.manifestSha=manifestSha;state.state='DOWNLOADING';await atomicJson(statePath,state);
    const content=join(stage,'content');await safeParents(content);let completed=0;
    for(const e of entries){check();const target=e.path?join(content,...e.path.split('/')):content;
      if(e.type==='directory'){await safeParents(target);continue;}
      // A single-file root lives under an internal name until the final commit.
      const filePath=e.path?target:join(content,'root-file');await safeParents(dirname(filePath));
      const handle=await open(filePath,constants.O_RDWR|constants.O_CREAT|constants.O_NOFOLLOW,0o600);
      try{const info=await handle.stat();if(!info.isFile()||info.size>e.size)throw Error('FILE_PULL_PART_INVALID');let offset=info.size;
        while(offset<e.size){check();const chunk=await rpcTool(transport,'file.exportRead',{exportId:state.exportId,entryId:e.entryId,offset,length:262144});
          const bytes=Buffer.from(chunk.data??'','base64');if(chunk.exportId!==state.exportId||chunk.entryId!==e.entryId||chunk.offset!==offset||chunk.size!==e.size||chunk.sha256!==e.sha256||chunk.encoding!=='base64'||bytes.toString('base64')!==chunk.data||bytes.length<1||bytes.length>262144||chunk.nextOffset!==offset+bytes.length||chunk.nextOffset>e.size||chunk.eof!==(chunk.nextOffset===e.size))throw Error('FILE_PULL_CHUNK_INVALID');
          let written=0;while(written<bytes.length){const result=await handle.write(bytes,written,bytes.length-written,offset+written);if(!result.bytesWritten)throw Error('FILE_PULL_WRITE_FAILED');written+=result.bytesWritten;}offset+=bytes.length;onProgress({state:'DOWNLOADING',exportId:state.exportId,bytes:completed+offset,totalBytes:total});
        }await handle.sync();
      }finally{await handle.close();}
      if(await hashFile(filePath)!==e.sha256)throw Error('FILE_PULL_HASH_MISMATCH:'+e.entryId);completed+=e.size;
    }
    check();const manifest={version:1,exportId:state.exportId,source:args.source,entries,totalBytes:total};
    // Reserve the manifest without replacing an existing user file.
    const manifestTemp=join(stage,'manifest.json');await atomicJson(manifestTemp,manifest);await link(manifestTemp,sidecar);
    try{if(entries[0].type==='file'){await link(join(content,'root-file'),destination);await unlink(join(content,'root-file'));}else{
        // mkdir acts as an exclusive reservation; directory rename cannot replace a populated destination.
        await mkdir(destination,{mode:0o700});await rename(content,destination);
      }}catch(error){await unlink(sidecar);throw error;}
    state={...state,state:'COMPLETE',destination,manifestPath:sidecar,totalBytes:total,files:entries.filter(e=>e.type==='file').length};await atomicJson(statePath,state);onProgress(state);return state;
  }catch(error){if(state){state.state=signal?.aborted?'PAUSED':'FAILED';state.error=error.message;await atomicJson(statePath,state);}throw error;}
  finally{await lock.close();await unlink(join(stage,'lock'));}
}

export class PullManager {
  constructor(transport){this.transport=transport;this.jobs=new Map();}
  start(args){const identity=sha(JSON.stringify(canonical(args))),prior=this.jobs.get(args.operationId);if(prior&&prior.identity!==identity)throw Error('FILE_PULL_OPERATION_CONTENT_MISMATCH');if(prior&&!['FAILED','PAUSED'].includes(prior.result.state))return prior.result;
    const controller=new AbortController(),job={controller,identity,result:{operationId:args.operationId,exportId:args.operationId,state:'STARTED'}};this.jobs.set(args.operationId,job);
    job.promise=pullFiles(this.transport,args,{signal:controller.signal,onProgress:r=>{job.result={...job.result,...r};}}).then(r=>{job.result={...job.result,...r};},e=>{job.result={...job.result,state:controller.signal.aborted?'PAUSED':'FAILED',error:e.message};});return job.result;
  }
  status(id){const job=this.jobs.get(id);if(!job)throw Error('FILE_PULL_JOB_NOT_FOUND');return job.result;}
  cancel(id){const job=this.jobs.get(id);if(!job)throw Error('FILE_PULL_JOB_NOT_FOUND');job.controller.abort();return {...job.result,cancelRequested:true};}
  async close(){for(const job of this.jobs.values())job.controller.abort();await Promise.all([...this.jobs.values()].map(j=>j.promise));}
}
