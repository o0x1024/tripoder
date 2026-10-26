import {open,readFile} from 'node:fs/promises';
import {constants} from 'node:fs';
import {isAbsolute} from 'node:path';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {rpcTool} from './file-pull.mjs';

const hash=(value,kind='sha256')=>createHash(kind).update(value).digest('hex');
export function dexFormat(bytes){
 const issues=[];
 if(bytes.length<112)return {state:'FAILED',issues:['DEX_HEADER_TRUNCATED']};
 const u32=offset=>bytes.readUInt32LE(offset);
 if(!/^dex\n0(?:35|37|38|39|40)\0$/.test(bytes.subarray(0,8).toString('latin1')))issues.push('DEX_MAGIC_OR_VERSION_UNSUPPORTED');
 if(u32(32)!==bytes.length)issues.push('DEX_FILE_SIZE_MISMATCH');
 if(u32(36)!==112||u32(40)!==0x12345678)issues.push('DEX_HEADER_LAYOUT_INVALID');
 if(bytes.subarray(12,32).toString('hex')!==hash(bytes.subarray(32),'sha1'))issues.push('DEX_SIGNATURE_MISMATCH');
 let a=1,b=0;for(const value of bytes.subarray(12)){a=(a+value)%65521;b=(b+a)%65521;}
 if(u32(8)!==((b*65536+a)>>>0))issues.push('DEX_CHECKSUM_MISMATCH');
 for(const [countAt,offsetAt,width] of [[56,60,4],[64,68,4],[72,76,12],[80,84,8],[88,92,8],[96,100,32]]){
  const count=u32(countAt),offset=u32(offsetAt);
  if(count>0&&(offset<112||offset%4!==0||offset+count*width>bytes.length))issues.push('DEX_SECTION_OUT_OF_BOUNDS:'+countAt);
 }
 const map=u32(52);
 if(map<112||map%4!==0||map+4>bytes.length||map+4+u32(Math.min(map,bytes.length-4))*12>bytes.length)issues.push('DEX_MAP_INVALID');
 const dataSize=u32(104),dataOff=u32(108);
 if(dataOff<112||dataOff+dataSize!==bytes.length)issues.push('DEX_DATA_SECTION_INVALID');
 return {state:issues.length?'FAILED':'PASSED',issues,scope:'header hashes and bounded section layout; not method reconstruction'};
}
async function consumer(path,executable){
 if(!executable)return {state:'NOT_CHECKED',reason:'DEX_VALIDATOR_NOT_CONFIGURED'};
 if(!isAbsolute(executable))throw Error('DEX_VALIDATOR_ABSOLUTE_PATH_REQUIRED');
 const identity=hash(await readFile(executable));
 return new Promise(resolve=>{
  const process=spawn(executable,['-f',path],{stdio:['ignore','pipe','pipe'],shell:false});
  const output=createHash('sha256');let bytes=0,limited=false,timedOut=false;
  const timer=setTimeout(()=>{timedOut=true;process.kill('SIGKILL');},30000);
  const observe=chunk=>{bytes+=chunk.length;output.update(chunk);if(bytes>32*1024*1024){limited=true;process.kill('SIGKILL');}};
  process.stdout.on('data',observe);process.stderr.on('data',observe);
  process.once('error',()=>{clearTimeout(timer);resolve({state:'NOT_CHECKED',reason:'DEX_VALIDATOR_UNAVAILABLE',executableSha256:identity});});
  process.once('close',(code,signal)=>{clearTimeout(timer);resolve({state:timedOut||limited?'NOT_CHECKED':code===0?'PASSED':'FAILED',consumer:'dexdump',scope:'DEX consumer class parsing; not app/business execution',executableSha256:identity,exitCode:code,signal,outputBytes:bytes,outputSha256:output.digest('hex'),...(timedOut||limited?{reason:timedOut?'DEX_VALIDATOR_TIMEOUT':'DEX_VALIDATOR_OUTPUT_LIMIT'}:{})});});
 });
}

/** Recheck the exact original bytes; never fixes headers or substitutes a file. */
export async function verifyArtifact(args,{dexValidator,transport}={}){
 if(!isAbsolute(args.destination))throw Error('ARTIFACT_DESTINATION_ABSOLUTE_PATH_REQUIRED');
 const file=await open(args.destination,constants.O_RDONLY|constants.O_NOFOLLOW);
 let bytes;
 try{const info=await file.stat();if(!info.isFile()||info.size>67108864)throw Error('ARTIFACT_FILE_LIMIT');bytes=await file.readFile();}finally{await file.close();}
 const sha256=hash(bytes),manifestPath=args.destination+'.manifest.json';
 const manifest=JSON.parse(await readFile(manifestPath,'utf8'));
 if(manifest.exportId!==args.operationId||manifest.source?.kind!=='artifact'||manifest.entries?.length!==1||manifest.entries[0].type!=='file'||manifest.entries[0].path!=='')throw Error('ARTIFACT_DOWNLOAD_MANIFEST_REQUIRED');
 const entry=manifest.entries[0],download=entry.size===bytes.length&&entry.sha256===sha256&&manifest.totalBytes===bytes.length;
 const capture={state:'NOT_CHECKED',artifactId:manifest.source.artifactId,processGeneration:manifest.source.processGeneration,scope:'persisted engine capture receipt; not reconstructed code'};
 if(transport)try{
  let cursor=0,found;
  for(let page=0;page<128;page++){
   const result=await rpcTool(transport,'artifact.list',{processGeneration:manifest.source.processGeneration,cursor,limit:64});
   found=result.artifacts?.find(row=>row.artifactId===manifest.source.artifactId);
   if(found||result.nextCursor===-1)break;
   if(!Number.isInteger(result.nextCursor)||result.nextCursor<=cursor)throw Error('ARTIFACT_CAPTURE_PAGE_INVALID');
   cursor=result.nextCursor;
  }
  capture.state=found?.state==='COMPLETE'&&found.sha256===entry.sha256&&found.size===entry.size?'CAPTURED':found?'FAILED':'NOT_CHECKED';
  if(!found)capture.reason='CAPTURE_RECEIPT_UNAVAILABLE';
 }catch(error){capture.state='NOT_CHECKED';capture.reason='CAPTURE_RECEIPT_QUERY_FAILED';capture.errorCode=String(error.message).match(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/)?.[0]??'REMOTE_QUERY_FAILED';}
 else capture.reason='CAPTURE_RECEIPT_NOT_QUERIED';
 const format=dexFormat(bytes);
 const usability=download?await consumer(args.destination,dexValidator):{state:'NOT_CHECKED',reason:'DOWNLOAD_HASH_MISMATCH'};
 // The configured consumer must have read the same bytes we checked above.
 if(hash(await readFile(args.destination))!==sha256)throw Error('ARTIFACT_CHANGED_DURING_VERIFICATION');
 const taskComplete=capture.state==='CAPTURED'&&download&&format.state==='PASSED'&&usability.state==='PASSED';
 return {operationId:args.operationId,destination:args.destination,manifestPath,sha256,size:bytes.length,stages:{capture,download:{state:download?'VERIFIED':'FAILED',expectedSha256:entry.sha256},format,usability},state:taskComplete?'PASSED':capture.state==='FAILED'||!download||format.state==='FAILED'||usability.state==='FAILED'?'FAILED':'PARTIAL',taskComplete,completionScope:'this artifact only; does not establish whole-run coverage'};
}
