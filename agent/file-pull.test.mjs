import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,symlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {loadConfig,Transport,MCP_VERSION} from './transport.mjs';
import {pullFiles,validateManifest} from './file-pull.mjs';
async function connect(){const t=new Transport(await loadConfig(process.env.TRIPODER_MCP_TEST_CONNECTION));await t.rpc({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION}});await t.rpc({jsonrpc:'2.0',method:'notifications/initialized'});return t;}
test('HTTPS directory pull preserves bytes, Unicode, empty files/dirs and sidecar without adb',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-test-')),source=join(root,'phone'),dest=join(root,'computer'),t=await connect();
 try{await mkdir(source);await mkdir(join(source,'empty'));const data=Buffer.alloc(600003,113);await writeFile(join(source,'数据.bin'),data);await writeFile(join(source,'zero'),'');
  const result=await pullFiles(t,{operationId:randomUUID(),source:{kind:'path',path:source},destination:dest,followSymlinks:false});assert.equal(result.state,'COMPLETE');assert.deepEqual(await readFile(join(dest,'数据.bin')),data);assert.equal((await readFile(join(dest,'zero'))).length,0);assert.equal(JSON.parse(await readFile(result.manifestPath,'utf8')).entries.length,4);
 }finally{await t.close();await rm(root,{recursive:true,force:true});}
});
test('interrupted download resumes the same snapshot after original source is gone',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-resume-')),source=join(root,'file'),dest=join(root,'result'),t=await connect(),args={operationId:randomUUID(),source:{kind:'path',path:source},destination:dest,followSymlinks:false};
 try{await writeFile(source,Buffer.alloc(700001,73));const controller=new AbortController();await assert.rejects(pullFiles(t,args,{signal:controller.signal,onProgress:p=>{if(p.state==='DOWNLOADING')controller.abort();}}),/PAUSED/);await rm(source);
 const result=await pullFiles(t,args);assert.equal(result.state,'COMPLETE');assert.equal((await readFile(dest)).length,700001);
 }finally{await t.close();await rm(root,{recursive:true,force:true});}
});
test('refuses destination overwrite and malicious manifests',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-collision-')),dest=join(root,'exists'),t=await connect();try{await writeFile(dest,'keep');await assert.rejects(pullFiles(t,{operationId:randomUUID(),source:{kind:'path',path:dest},destination:dest,followSymlinks:false}),/DESTINATION_EXISTS/);assert.equal(await readFile(dest,'utf8'),'keep');}finally{await t.close();await rm(root,{recursive:true,force:true});}
 const directory={entryId:'e0',path:'',type:'directory'};for(const path of ['../escape','/absolute','a/../../escape','a\\b'])assert.throws(()=>validateManifest([directory,{entryId:'e1',path,type:'directory'}]));
 assert.throws(()=>validateManifest([directory,{entryId:'e1',path:'A',type:'directory'},{entryId:'e2',path:'a',type:'directory'}]),/COLLISION/);
});
