import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, chmodSync, writeFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { auditFile } from './audit.mjs';

test('acceptance audit excludes source, preparation tokens and arbitrary errors',()=>{
  const dir=mkdtempSync(join(tmpdir(),'tripoder-audit-'));
  try{
    const path=join(dir,'audit.jsonl'),audit=auditFile(path);
    audit.record({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'hook_prepare',arguments:{operationId:'owned-op',source:'private-source-marker'}}},{result:{content:[{type:'text',text:JSON.stringify({ok:false,error:'private-error-marker',result:{state:'PREPARED',token:'private-token-marker',sourceSha256:'a'.repeat(64)}})}]}});
    audit.close();const content=readFileSync(path,'utf8');
    for(const secret of ['private-source-marker','private-error-marker','private-token-marker'])assert.ok(!content.includes(secret));
    const record=JSON.parse(content.split('\n')[1]);assert.equal(record.tool,'hook_prepare');assert.equal(record.ok,false);assert.equal(record.result.state,'PREPARED');assert.equal(record.ppid,process.ppid);
  }finally{rmSync(dir,{recursive:true});}
});
test('audit rejects readable-by-others files and symlinks',()=>{
  const dir=mkdtempSync(join(tmpdir(),'tripoder-audit-permissions-'));
  try{const path=join(dir,'audit');writeFileSync(path,'');chmodSync(path,0o644);assert.throws(()=>auditFile(path),/PERMISSIONS/);const link=join(dir,'link');symlinkSync(path,link);assert.throws(()=>auditFile(link));}
  finally{rmSync(dir,{recursive:true});}
});
