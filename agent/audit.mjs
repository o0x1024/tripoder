import { openSync, closeSync, fstatSync, writeSync, constants } from 'node:fs';
import { isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';

const sha=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
// Explicit acceptance-only metadata. No private key, pairing token, source or arbitrary output.
export function auditFile(path){
  if(!isAbsolute(path))throw Error('AUDIT_PATH_MUST_BE_ABSOLUTE');
  const fd=openSync(path,constants.O_WRONLY|constants.O_CREAT|constants.O_APPEND|constants.O_NOFOLLOW,0o600);
  const stat=fstatSync(fd);if(!stat.isFile()||stat.uid!==process.getuid()||(stat.mode&0o077)!==0){closeSync(fd);throw Error('AUDIT_FILE_PERMISSIONS');}
  const append=value=>{if(fstatSync(fd).size>4*1024*1024)throw Error('AUDIT_BUDGET_EXHAUSTED');writeSync(fd,JSON.stringify({time:new Date().toISOString(),pid:process.pid,ppid:process.ppid,...value})+'\n');};
  append({event:'start'});
  return {record(request,response){
    if(Array.isArray(request))return;
    const row={event:'rpc',method:request?.method,id:request?.id,requestSha256:sha(request),responseSha256:sha(response)};
    if(request?.method==='initialize')row.clientInfo={name:String(request.params?.clientInfo?.name??'').slice(0,100),version:String(request.params?.clientInfo?.version??'').slice(0,100)};
    if(request?.method==='tools/call'){
      row.tool=request.params?.name;row.operationId=request.params?.arguments?.operationId;
      const raw=response?.result?.content?.find(item=>item.type==='text')?.text;
      if(raw){const receipt=JSON.parse(raw);row.receiptSha256=createHash('sha256').update(raw).digest('hex');row.requestId=receipt.requestId;row.ok=receipt.ok;row.receiptOperationId=receipt.operationId;const result=receipt.result;
        if(result&&typeof result==='object'){row.result={};for(const key of ['state','package','project','processGeneration','pid','hits','errors','revision','sourceSha256','approvedSourceSha256','processClose','reason'])if(result[key]!==undefined)row.result[key]=result[key];if(result.evidence?.id)row.result.evidenceId=result.evidence.id;}
      }
    }
    append(row);
  },close(){append({event:'exit'});closeSync(fd);}};
}
