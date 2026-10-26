// Advice only. Never dispatch, retry, change an outcome or retire evidence here.
export function recoveryReceipt(receipt,name,args={}){
 const value=receipt.result&&typeof receipt.result==='object'?receipt.result:{};
 const unknown=receipt.state==='UNKNOWN'||value.state==='UNKNOWN';
 const error=String(receipt.error??'');
 const blocked=error.match(/(?:RECOVERY_SCOPE_UNKNOWN|HOOK_OUTCOME_QUERY_REQUIRED):\s*([A-Za-z0-9_-]{1,80})/);
 if(receipt.ok&&!unknown)return receipt;
 const errorCode=receipt.errorCode??(unknown&&receipt.ok?'OPERATION_OUTCOME_UNKNOWN':undefined)??error.match(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/)?.[0]??'COMMAND_FAILED';
 const original=blocked?.[1]??receipt.operationId??value.operationId??args.operationId;
 const candidate=value.targetVerified===false?undefined:args.target??value.target;
 const target=candidate&&typeof candidate==='object'&&['space','package','installGeneration'].every(k=>typeof candidate[k]==='string')?Object.fromEntries(['space','package','installGeneration'].map(k=>[k,candidate[k]])):undefined;
 const steps=[];
 const add=(tool,arguments_)=>steps.push({tool,arguments:arguments_});
 let action='Inspect the exact published action schema and error before issuing a command.';
 if(unknown||blocked){
  if(original&&!['operation_get','operation.get'].includes(name))add('operation_get',{operationId:original});
  if(errorCode==='HOOK_OUTCOME_QUERY_REQUIRED'){
   if(candidate?.processGeneration)add('process_inspect',{action:'hook_status',target:candidate});
   action='Query the original operation and exact Hook status. Reading events does not clear the gate. Never replay this mutation, including under a new operationId.';
  }else{
   add('application_query',{action:'targets'});
   if(target){
    if(original&&name!=='launch.recovery'&&!(name==='application_query'&&args.action==='launch_recovery'))add('application_query',{action:'launch_recovery',operationId:original,target});
    add('application_query',{action:'process_history',target,cursor:0,limit:16});
   }
   action='Preserve the original UNKNOWN result and evidence. Query the original operation and exact launch recovery. targets=[] is not proof of failure. If entryIssued=false, do not call launch_manage(close). A deliberate application_stop with its own new operationId may establish stop/dead-session evidence; inspect launch_recovery again before planning a new launch. Changing the launch ID alone never authorizes retry.';
  }
 }else if(errorCode==='LAUNCH_RECOVERY_BINDING_STALE'){
  if(original)add('operation_get',{operationId:original});
  action='The supplied installation does not own the original launch. Keep its UNKNOWN result and recover its initiating identity; never substitute a newly installed target.';
 }else if(errorCode==='LAUNCH_ENTRY_BINDING_MISMATCH'){
  if(args.launchOperationId&&target)add('application_query',{action:'launch_recovery',operationId:args.launchOperationId,target});
  action='Read the original launch recovery. A preparation with entryIssued=false has no entry to close. Do not guess a ticket, change target identity or change launch IDs to retry.';
 }else if(errorCode==='AGENT_REQUEST_ID_CONTENT_MISMATCH'){
  if(args.operationId)add('operation_get',{operationId:args.operationId});
  action='This ID belongs to different content. Inspect its original receipt; do not overwrite, replay or infer failure.';
 }else if(receipt.dispatched===false){action='No command was dispatched. Correct the named fields using this action schema. This rejection does not authorize retrying any earlier UNKNOWN operation.';}
 return {...receipt,errorCode,...(original?{pendingOperationId:original}:{}),recovery:{action,steps,replayAllowed:false,newIdRetryAllowed:false,preserveOriginalEvidence:true,pollingDoesNotResolve:true,...(unknown||blocked?{requiresOriginalTarget:!target}:{} )}};
}
