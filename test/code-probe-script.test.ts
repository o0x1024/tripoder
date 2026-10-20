import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareDraft } from '../src/draft.js';
import type { EvidenceSnapshot } from '../src/evidence.js';
const evidence: EvidenceSnapshot={id:'code-evidence',source:'fixture',target:{packageName:'owned.fixture',versionCode:'1',apkSha256:'a'.repeat(64)},processInstanceId:'owned:1',abi:'arm64-v8a',capabilities:['java-hook'],symbols:[{id:'method',kind:'java',loaded:true,className:'owned.Fixture',methodName:'value',classLoaderId:'loader-1',argumentTypes:[],returnType:'java.lang.String'}]};
const source="import {definePlan} from '@abox/hook';export default definePlan({id:'code',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','value',[]).replace(()=>{const capture=ctx.code.snapshotDex('dex-1');return capture.artifactId;});}});";
test('restricted source defers code capture until callback and requires a backend',()=>{
 const plan=prepareDraft(source,evidence);let requests=0;
 assert.throws(()=>plan.rules[0]!.handlers.replace!({args:[],params:{}}),{code:'CODE_SNAPSHOT_UNSUPPORTED'});
 assert.ok(plan.emitSink);plan.emitSink.snapshotDex=id=>{assert.equal(id,'dex-1');requests++;return {artifactId:'artifact-1',state:'QUEUED'};};
 assert.equal(requests,0);assert.equal(plan.rules[0]!.handlers.replace!({args:[],params:{}}),'artifact-1');assert.equal(requests,1);
 assert.throws(()=>prepareDraft(source.replace("setup(ctx){","setup(ctx){ctx.code.snapshotDex('dex-1');"),evidence),{code:'CODE_CAPTURE_OUTSIDE_INVOCATION'});
});
