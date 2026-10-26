import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {admitApkSize,MAX_APK_BYTES,MAX_APK_SET_BYTES} from './import-limits.mjs';
import {command,operationCall} from './tools.mjs';

test('APK upload admission agrees across transport, MCP schema and Android authority',()=>{
  assert.equal(admitApkSize(1504*1024*1024,0),1504*1024*1024);
  assert.equal(admitApkSize(MAX_APK_BYTES,MAX_APK_BYTES),MAX_APK_SET_BYTES);
  for(const size of [0,-1,1.5,NaN,MAX_APK_BYTES+1])assert.throws(()=>admitApkSize(size,0),/APK_FILE_SIZE/);
  assert.throws(()=>admitApkSize(1,MAX_APK_SET_BYTES),/APK_SET_SIZE/);
  const begin=size=>operationCall('import.begin',{operationId:'large-apk',space:'qa',expectedGeneration:'',files:[{name:'base.apk',size,sha256:'a'.repeat(64)}]});
  const valid=begin(MAX_APK_BYTES);assert.doesNotThrow(()=>command(valid.name,valid.arguments));
  assert.throws(()=>{const call=begin(MAX_APK_BYTES+1);command(call.name,call.arguments);});
  const write=operationCall('import.write',{operationId:'large-chunk',uploadId:'large-apk',index:0,offset:1504*1024*1024,data:'AA=='});
  assert.doesNotThrow(()=>command(write.name,write.arguments));
  const source=readFileSync(new URL('../android/runtime/src/dev/tripoder/runtime/ArtifactSet.java',import.meta.url),'utf8');
  assert.match(source,/MAX_APK_BYTES=2L\*1024\*1024\*1024,MAX_SET_BYTES=4L\*1024\*1024\*1024/);
});
