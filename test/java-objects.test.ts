import {JavaObjectFrame,isJavaObjectRef,isJavaReferenceType,assertSnapshotData,eventSnapshot} from "../src/java-objects.js";
import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareDraft} from '../src/draft.js';
import {HookSimulator} from '../src/simulator.js';
import type {EvidenceSnapshot} from '../src/evidence.js';

const evidence:EvidenceSnapshot={id:'objects',source:'fixture',target:{packageName:'owned.fixture',versionCode:'1',apkSha256:'a'.repeat(64)},processInstanceId:'process-a',abi:'arm64-v8a',capabilities:['java-hook'],symbols:[{id:'object',kind:'java',loaded:true,className:'owned.Fixture',methodName:'call',classLoaderId:'loader-a',argumentTypes:['java.lang.Throwable'],returnType:'java.lang.Throwable'}]};
test('restricted source preserves opaque object identity and captures only explicit fixture snapshots',()=>{
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'objects',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',['java.lang.Throwable']).before(({args})=>{if(args[0]!==null){ctx.debug.captureEvidence('exception',ctx.java.snapshotThrowable(args[0]));}return args;});}});`;
  const plan=prepareDraft(source,evidence),sim=new HookSimulator(evidence);sim.activate(plan);
  const fixture={className:'owned.Exception',assignableTo:['java.lang.Throwable','java.lang.Object'],snapshot:{causes:[{className:'owned.Exception',message:'owned'}]}};let calls=0;
  const result=sim.invokeJavaFixture('object',[fixture],args=>{calls++;assert.equal(args[0],fixture);return args[0];});assert.equal(result.value,fixture);assert.equal(calls,1);
  assert.equal(sim.events().filter(e=>e.tag==='exception').length,1);
  assert.equal(sim.invokeJavaFixture('object',[null],args=>args[0]).value,null);
});
test('ctx.java.readField reads a named fixture field and rejects an unknown field',()=>{
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'objects',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',['java.lang.Throwable']).before(({args})=>{ctx.debug.emitEvent('field',ctx.java.readField(args[0],'message'));return args;});}});`;
  const plan=prepareDraft(source,evidence),sim=new HookSimulator(evidence);sim.activate(plan);
  const fixture={className:'owned.Exception',assignableTo:['java.lang.Throwable','java.lang.Object'],snapshot:{message:'boom',code:7}};
  sim.invokeJavaFixture('object',[fixture],args=>args[0]);
  assert.equal(sim.events().filter(e=>e.tag==='field')[0]?.payload,'boom');
  const missing=`import {definePlan} from '@abox/hook';export default definePlan({id:'objects',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',['java.lang.Throwable']).before(({args})=>{ctx.java.readField(args[0],'nope');return args;});}});`;
  const sim2=new HookSimulator(evidence);sim2.activate(prepareDraft(missing,evidence));
  assert.throws(()=>sim2.invokeJavaFixture('object',[fixture],args=>args[0]),error=>(error as {code?:string}).code==='HOOK_FIELD_NOT_FOUND');
});
test('object references cannot be replaced with JSON or emitted as snapshots',()=>{
  for(const body of [`return [{className:'java.lang.Throwable'}];`,`ctx.debug.emitEvent('invalid',args[0]);return args;`,`return [String(args[0])];`]){
    const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'objects',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',['java.lang.Throwable']).before(({args})=>{${body}});}});`;
    const sim=new HookSimulator(evidence);sim.activate(prepareDraft(source,evidence));let calls=0;
    assert.throws(()=>sim.invokeJavaFixture('object',[{className:'owned.Exception',assignableTo:['java.lang.Throwable']}],()=>{calls++;return null;}));assert.equal(calls,0);
  }
});

test('expired references remain branded and cannot become empty JSON snapshots',()=>{
  const frame=new JavaObjectFrame();const ref=frame.wrap('a',{className:'a',assignableTo:[]})!;frame.close();
  assert.equal(isJavaObjectRef(ref),true);assert.throws(()=>assertSnapshotData(ref));assert.throws(()=>JavaObjectFrame.info(ref),error=>(error as {code?:string}).code==='HOOK_OBJECT_REFERENCE_EXPIRED');
  assert.equal(isJavaReferenceType('a'),true);assert.equal(isJavaReferenceType('[La;'),true);for(const primitive of ['int','void','boolean'])assert.equal(isJavaReferenceType(primitive),false);
});

test('events encode Java long values explicitly while immutable evidence remains JSON-only',()=>{
  assert.deepEqual(eventSnapshot({value:9223372036854775807n}),{value:'9223372036854775807'});assert.throws(()=>assertSnapshotData(1n));
  const cycle:Record<string,unknown>={};cycle.self=cycle;assert.throws(()=>eventSnapshot(cycle),/Cyclic/);
});
