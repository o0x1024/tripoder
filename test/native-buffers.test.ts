import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareDraft} from '../src/draft.js';
import type {EvidenceSnapshot} from '../src/evidence.js';

const evidence:EvidenceSnapshot={id:'native-buffer-evidence',source:'fixture',target:{packageName:'owned.fixture',versionCode:'1',apkSha256:'a'.repeat(64)},processInstanceId:'owned:1',abi:'arm64-v8a',capabilities:['native-hook'],symbols:[{id:'read',kind:'native',loaded:true,moduleName:'libowned.so',symbol:'owned_read',buildId:'aabb',argumentTypes:['uint64','uint64','int32'],returnType:'int32',signatureSource:'confirmed'}]};
const source="import {definePlan} from '@abox/hook';export default definePlan({id:'native-buffer',target,params:{},setup(ctx){ctx.native.function('libowned.so','owned_read',{argumentTypes:['uint64','uint64','int32'],returnType:'int32',buildId:'aabb'}).after(({result})=>{ctx.debug.emitEvent('bytes',ctx.native.captureBuffer(1,2,32));return result;});}});";
test('native buffer draft requires an explicit invocation provider and preserves original result',()=>{
 const plan=prepareDraft(source,evidence),call={args:[1n,2n,128],result:3,params:{}};
 assert.throws(()=>plan.rules[0]!.handlers.after!(call),{code:'NATIVE_BUFFER_UNSUPPORTED'});
 assert.ok(plan.emitSink);let calls=0,observed:unknown;
 plan.emitSink.nativeBuffer=(pointer,capacity,maximum)=>{assert.deepEqual([pointer,capacity,maximum],[1,2,32]);calls++;return {state:'COMPLETE',hex:'010203',capturedBytes:3,truncated:false};};
 plan.emitSink.fn=(_tag,payload)=>{observed=payload;};
 assert.equal(plan.rules[0]!.handlers.after!(call),3);assert.equal(calls,1);
 assert.deepEqual({...observed as object},{state:'COMPLETE',hex:'010203',capturedBytes:3,truncated:false});
 assert.throws(()=>prepareDraft(source.replace('setup(ctx){','setup(ctx){ctx.native.captureBuffer(1,2,32);'),evidence),{code:'NATIVE_BUFFER_OUTSIDE_INVOCATION'});
 for(const invalid of ['1,2,1025','1,1,32','-1,2,32','1,2,0']){
   const rejected=prepareDraft(source.replace('1,2,32',invalid),evidence);
   assert.throws(()=>rejected.rules[0]!.handlers.after!(call),{code:'NATIVE_BUFFER_ARGUMENT'});
 }
});
