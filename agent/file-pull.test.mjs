import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,symlink,realpath} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {loadConfig,Transport,MCP_VERSION} from './transport.mjs';
import {pullFiles,validateManifest,PullManager} from './file-pull.mjs';
import {McpServer} from './server.mjs';
import {createHash} from 'node:crypto';
async function connect(){const t=new Transport(await loadConfig(process.env.TRIPODER_MCP_TEST_CONNECTION));await t.rpc({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION}});await t.rpc({jsonrpc:'2.0',method:'notifications/initialized'});return t;}
test('HTTP directory pull preserves bytes, Unicode, empty files/dirs and sidecar without adb',async()=>{
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

const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function finished(manager,id){
 const deadline=Date.now()+15000;
 while(Date.now()<deadline){const result=manager.status(id);if(['COMPLETE','FAILED','PAUSED'].includes(result.state))return result;await new Promise(r=>setTimeout(r,10));}
 throw Error('TEST_PULL_DID_NOT_FINISH');
}
test('MCP start/status returns paths and hashes, never binary, and restart verifies committed bytes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-mcp-')),source=join(root,'phone'),destination=join(root,'download'),transport=await connect();
 const server=new McpServer(transport);server.ready=true;
 const data=Buffer.alloc(2000003,79),operationId=randomUUID();
 const args={action:'start',operationId,source:{kind:'path',path:source},destination,followSymlinks:false};
 const call=(name,arguments_)=>server.handle({jsonrpc:'2.0',id:randomUUID(),method:'tools/call',params:{name,arguments:arguments_}});
 try{
  await writeFile(source,data);
  const start=await call('file_pull',args);assert.equal(start.result.isError,false);
  assert.equal((await finished(server.pulls,operationId)).state,'COMPLETE');
  const status=await call('file_pull_status',{operationId}),receipt=JSON.parse(status.result.content[0].text);
  assert.equal(receipt.ok,true);assert.equal(receipt.result.sha256,hash(data));assert.equal(receipt.result.bytes,data.length);
  assert.equal(receipt.result.destination,await realpath(destination));assert.equal(receipt.result.manifestSha256,hash(await readFile(destination+'.manifest.json')));
  assert.equal(JSON.stringify(status).includes('base64'),false);assert.ok(JSON.stringify(status).length<2000);
  assert.equal(JSON.parse((await call('file_pull',args)).result.content[0].text).result.state,'COMPLETE');
  const restarted=new PullManager(transport);assert.throws(()=>restarted.status(operationId),/original operationId/);
  restarted.start({...args,action:undefined});assert.equal((await finished(restarted,operationId)).state,'COMPLETE');
  await writeFile(destination,'changed');const recheck=new PullManager(transport);recheck.start({...args,action:undefined});
  assert.match((await finished(recheck,operationId)).error,/COMMITTED_FILE_CHANGED/);
 }finally{await server.pulls.close();await transport.close();await rm(root,{recursive:true,force:true});}
});
test('MCP reports terminal download failure as isError and rejects conflicting operations',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-error-')),destination=join(root,'exists'),transport=await connect(),server=new McpServer(transport);server.ready=true;
 const operationId=randomUUID(),args={operationId,source:{kind:'path',path:destination},destination,followSymlinks:false};
 try{
  await writeFile(destination,'keep');server.pulls.start(args);assert.equal((await finished(server.pulls,operationId)).state,'FAILED');
  const response=await server.handle({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'file_pull_status',arguments:{operationId}}});
  assert.equal(response.result.isError,true);assert.equal(JSON.parse(response.result.content[0].text).ok,false);
  assert.throws(()=>server.pulls.start({...args,destination:destination+'other'}),/OPERATION_CONTENT_MISMATCH/);
  assert.equal(await readFile(destination,'utf8'),'keep');
 }finally{await server.pulls.close();await transport.close();await rm(root,{recursive:true,force:true});}
});
test('relative destinations are rejected before starting a local or remote job',async()=>{
 const server=new McpServer({rpc(){throw Error('must not dispatch');}});server.ready=true;
 const response=await server.handle({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'file_pull',arguments:{action:'start',operationId:'relative',source:{kind:'path',path:'/phone/file'},destination:'relative/file',followSymlinks:false}}});
 const value=JSON.parse(response.result.content[0].text);
 assert.equal(response.result.isError,true);assert.equal(value.dispatched,false);assert.equal(value.issues[0].path,'/destination');
 assert.equal(value.issues[0].code,'ABSOLUTE_PATH_REQUIRED');assert.equal(server.pulls.jobs.size,0);
});
test('restart rejects changed server/source and does not corrupt the original resume record',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-identity-')),source=join(root,'phone'),destination=join(root,'download'),transport=await connect();
 const args={operationId:randomUUID(),source:{kind:'path',path:source},destination,followSymlinks:false};
 try{
  await writeFile(source,Buffer.alloc(700001,71));const controller=new AbortController();
  await assert.rejects(pullFiles(transport,args,{signal:controller.signal,onProgress:p=>{if(p.state==='DOWNLOADING')controller.abort();}}),/PAUSED/);
  const statePath=destination+'.tripoder-'+args.operationId+'.part/state.json',before=await readFile(statePath,'utf8');
  await assert.rejects(pullFiles(transport,{...args,source:{kind:'path',path:source+'changed'}}),/IDENTITY_MISMATCH/);
  assert.equal(await readFile(statePath,'utf8'),before);
  const changed=Object.create(transport);changed.config={...transport.config,url:'http://different.invalid/mcp'};
  await assert.rejects(pullFiles(changed,args),/IDENTITY_MISMATCH/);assert.equal(await readFile(statePath,'utf8'),before);
  assert.equal((await pullFiles(transport,args)).state,'COMPLETE');
 }finally{await transport.close();await rm(root,{recursive:true,force:true});}
});
test('MCP receiver bounds concurrent jobs and cancellation pauses only active jobs',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-pull-budget-'));
 const manager=new PullManager({config:{},async tool(){return {ok:true,result:{state:'PREPARING',bytes:0}};}});
 const args=i=>({operationId:'budget-'+i,source:{kind:'path',path:'/phone/file'},destination:join(root,'file-'+i),followSymlinks:false});
 try{
  manager.start(args(1));manager.start(args(2));assert.throws(()=>manager.start(args(3)),/LOCAL_BUSY/);
  assert.equal(manager.cancel('budget-1').cancelRequested,true);assert.equal((await finished(manager,'budget-1')).state,'PAUSED');
  assert.equal(manager.cancel('budget-1').state,'PAUSED');assert.equal(manager.cancel('budget-1').cancelRequested,undefined);
 }finally{await manager.close();await rm(root,{recursive:true,force:true});}
});
