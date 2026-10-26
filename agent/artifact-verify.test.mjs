import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {dexFormat,verifyArtifact} from './artifact-verify.mjs';

const hash=b=>createHash('sha256').update(b).digest('hex');
function dex(){
 const b=Buffer.alloc(140);b.write('dex\n035\0',0,'latin1');
 for(const [offset,value] of [[32,140],[36,112],[40,0x12345678],[52,112],[104,28],[108,112],[112,1]])b.writeUInt32LE(value,offset);
 createHash('sha1').update(b.subarray(32)).digest().copy(b,12);
 let a=1,c=0;for(const n of b.subarray(12)){a=(a+n)%65521;c=(c+a)%65521;}b.writeUInt32LE((c*65536+a)>>>0,8);return b;
}
test('DEX validation distinguishes transport integrity from internal checksum/signature validity',()=>{
 assert.equal(dexFormat(dex()).state,'PASSED');
 const damaged=dex();damaged[139]=1;const result=dexFormat(damaged);
 assert.equal(result.state,'FAILED');assert.ok(result.issues.includes('DEX_SIGNATURE_MISMATCH'));assert.ok(result.issues.includes('DEX_CHECKSUM_MISMATCH'));
 assert.equal(dexFormat(Buffer.alloc(5)).issues[0],'DEX_HEADER_TRUNCATED');
});
test('capture, download, format and usability are separate; absent consumer cannot complete acceptance',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-artifact-')),destination=join(root,'fixture.dex'),bytes=dex(),sha256=hash(bytes);
 const manifest={exportId:'owned-artifact',source:{kind:'artifact',artifactId:'a',processGeneration:'p'},entries:[{entryId:'e0',path:'',type:'file',size:bytes.length,sha256}],totalBytes:bytes.length};
 const transport={async tool(name,args){assert.equal(name,'artifact_query');assert.equal(args.action,'list');return {ok:true,result:{artifacts:[{artifactId:'a',state:'COMPLETE',size:bytes.length,sha256}],nextCursor:-1}};}};
 try{
  await writeFile(destination,bytes);await writeFile(destination+'.manifest.json',JSON.stringify(manifest));
  const result=await verifyArtifact({operationId:'owned-artifact',destination},{transport});
  assert.equal(result.stages.capture.state,'CAPTURED');assert.equal(result.stages.download.state,'VERIFIED');assert.equal(result.stages.format.state,'PASSED');assert.equal(result.stages.usability.state,'NOT_CHECKED');assert.equal(result.taskComplete,false);assert.equal(result.state,'PARTIAL');
  const noCapture=await verifyArtifact({operationId:'owned-artifact',destination});assert.equal(noCapture.stages.capture.state,'NOT_CHECKED');assert.equal(noCapture.taskComplete,false);
  const broken=Buffer.from(bytes);broken[139]=1;await writeFile(destination,broken);manifest.entries[0].sha256=hash(broken);await writeFile(destination+'.manifest.json',JSON.stringify(manifest));
  const failed=await verifyArtifact({operationId:'owned-artifact',destination});assert.equal(failed.stages.download.state,'VERIFIED');assert.equal(failed.stages.format.state,'FAILED');assert.equal(failed.taskComplete,false);assert.deepEqual(await readFile(destination),broken);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('whole-artifact completion requires an actual configured consumer result for the same bytes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'tripoder-consumer-')),destination=join(root,'fixture.dex'),validator=join(root,'consumer-fixture'),bytes=dex(),sha256=hash(bytes);
 const args={operationId:'owned-consumer',destination},manifest={exportId:args.operationId,source:{kind:'artifact',artifactId:'a',processGeneration:'p'},entries:[{entryId:'e0',path:'',type:'file',size:bytes.length,sha256}],totalBytes:bytes.length};
 const transport={async tool(){return {ok:true,result:{artifacts:[{artifactId:'a',state:'COMPLETE',size:bytes.length,sha256}],nextCursor:-1}};}};
 try{
  await writeFile(destination,bytes);await writeFile(destination+'.manifest.json',JSON.stringify(manifest));
  // Explicit process fixture, not evidence that real dexdump accepts this DEX.
  await writeFile(validator,'#!'+process.execPath+'\nprocess.stdout.write("private-consumer-output");process.exit(0);\n',{mode:0o700});
  const passed=await verifyArtifact(args,{transport,dexValidator:validator});assert.equal(passed.taskComplete,true);assert.equal(passed.state,'PASSED');assert.equal(passed.stages.usability.exitCode,0);assert.equal(JSON.stringify(passed).includes('private-consumer-output'),false);
  await writeFile(validator,'#!'+process.execPath+'\nprocess.exit(1);\n',{mode:0o700});
  const failed=await verifyArtifact(args,{transport,dexValidator:validator});assert.equal(failed.stages.format.state,'PASSED');assert.equal(failed.stages.usability.state,'FAILED');assert.equal(failed.taskComplete,false);
 }finally{await rm(root,{recursive:true,force:true});}
});
