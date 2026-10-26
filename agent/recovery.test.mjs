import test from 'node:test';
import assert from 'node:assert/strict';
import {recoveryReceipt} from './recovery.mjs';
import {command} from './tools.mjs';

const target={space:'s',package:'owned.app',installGeneration:'g'};
test('UNKNOWN launch retains the original result and evidence and offers read-only exact recovery',()=>{
 const original={ok:false,state:'UNKNOWN',operationId:'original',error:'IllegalStateException:WORKER_DIED',result:'retained',evidence:'retained-proof'};
 const result=recoveryReceipt(original,'process_start',{target,operationId:'original'});
 assert.equal(result.result,'retained');assert.equal(result.evidence,'retained-proof');assert.equal(result.pendingOperationId,'original');assert.equal(result.errorCode,'WORKER_DIED');
 assert.equal(result.recovery.replayAllowed,false);assert.equal(result.recovery.newIdRetryAllowed,false);assert.equal(result.recovery.preserveOriginalEvidence,true);
 assert.ok(result.recovery.action.includes('entryIssued=false'));assert.ok(result.recovery.action.includes('targets=[] is not proof'));
 for(const step of result.recovery.steps)assert.doesNotThrow(()=>command(step.tool,step.arguments));
 assert.equal(original.recovery,undefined);
});
test('a scope rejection refers to the blocking operation rather than its newly rejected ID',()=>{
 const result=recoveryReceipt({ok:false,state:'REJECTED',operationId:'rejected-new',error:'IllegalStateException:RECOVERY_SCOPE_UNKNOWN: original'},'probes_control',{target});
 assert.equal(result.errorCode,'RECOVERY_SCOPE_UNKNOWN');assert.equal(result.pendingOperationId,'original');assert.equal(result.recovery.steps[0].arguments.operationId,'original');
});
test('operation queries never infer a target or turn UNKNOWN into failure or success',()=>{
 const original={ok:true,result:{operationId:'original',state:'UNKNOWN',detail:'LAUNCH_PREPARATION_UNCONFIRMED',evidence:'proof'}};
 const result=recoveryReceipt(original,'operation_get',{operationId:'original'});
 assert.deepEqual(result.result,original.result);assert.equal(result.recovery.requiresOriginalTarget,true);assert.equal(result.recovery.newIdRetryAllowed,false);
 assert.equal(result.recovery.steps.some(s=>s.tool==='process_start'||s.tool==='launch_manage'),false);
});
test('no-entry close error points back to launch recovery, not another close or launch',()=>{
 const result=recoveryReceipt({ok:false,state:'REJECTED',error:'SecurityException:LAUNCH_ENTRY_BINDING_MISMATCH'},'launch_manage',{target,launchOperationId:'original'});
 assert.deepEqual(result.recovery.steps,[{tool:'application_query',arguments:{action:'launch_recovery',operationId:'original',target}}]);
});
test('a current installation cannot be substituted for an original launch owner',()=>{
 const result=recoveryReceipt({ok:false,error:'SecurityException:LAUNCH_RECOVERY_BINDING_STALE'},'application_query',{action:'launch_recovery',operationId:'old-launch',target});
 assert.deepEqual(result.recovery.steps,[{tool:'operation_get',arguments:{operationId:'old-launch'}}]);
 assert.equal(result.recovery.newIdRetryAllowed,false);
});
test('scope-only no-entry proof does not advertise a verified installation target',()=>{
 const result=recoveryReceipt({ok:true,result:{state:'UNKNOWN',operation:'old-launch',entryIssued:false,targetVerified:false}},'application_query',{action:'launch_recovery',operationId:'old-launch',target});
 assert.equal(result.recovery.requiresOriginalTarget,true);assert.equal(result.recovery.steps.some(step=>step.arguments.target),false);assert.equal(result.recovery.pollingDoesNotResolve,true);
});
