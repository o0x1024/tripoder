// Advice only: recovery never dispatches or replays a device command.
export function recoveryReceipt(receipt,name,args){
  if(receipt.ok&&name==='operation_get'&&receipt.result?.state==='UNKNOWN')return {...receipt,recovery:{action:'The journal still records UNKNOWN. Inspect actual target state and collect reconciliation evidence; do not replay the mutation or assume a later status query proves its success.',steps:[],replayAllowed:false}};
  if(receipt.ok)return receipt;
  const error=String(receipt.error??'');
  let errorCode=receipt.errorCode??error.match(/\b(?:HOOK|AGENT|TRANSPORT|TRIPODER|TOOL)_[A-Z0-9_]+/)?.[0]??'COMMAND_FAILED';
  let steps=[],action='Inspect the failure and tool schema before issuing another command.';
  if(errorCode==='HOOK_OUTCOME_QUERY_REQUIRED'){
    const pendingOperationId=error.match(/HOOK_OUTCOME_QUERY_REQUIRED:([A-Za-z0-9_-]{1,80})/)?.[1];
    if(pendingOperationId)steps.push({tool:'operation_get',arguments:{operationId:pendingOperationId}});
    steps.push({tool:'process_inspect',arguments:{action:'hook_status',target:args.target}});
    action='Query the original operation, then use process_inspect(action=hook_status) to read actual Hook status. Reading events does not clear this gate. Never replay an unknown mutation.';
    return {...receipt,errorCode,...(pendingOperationId?{pendingOperationId}:{}),recovery:{action,steps,replayAllowed:false}};
  }
  if(receipt.state==='UNKNOWN'||receipt.result?.state==='UNKNOWN'){
    const operationId=receipt.operationId??receipt.result?.operationId;
    if(operationId)steps.push({tool:'operation_get',arguments:{operationId}});
    action='Reconcile this exact operation id. Unknown means side effects may have occurred; do not retry with a new id.';
  }else if(errorCode==='AGENT_FIELDS_UNSUPPORTED'&&name==='operation_get'){
    action='Reconnect the Tripoder MCP server and refresh tools to load the current protocol. Query the same original operationId again; do not replay its mutation.';
  }else if(errorCode==='AGENT_REQUEST_ID_CONTENT_MISMATCH'){
    steps.push({tool:'operation_get',arguments:{operationId:args.operationId}});
    action='This id belongs to different command content. Query it first; do not reuse an observe id for discard or another command.';
  }else if(receipt.state==='REJECTED'||receipt.outcome==='REJECTED'){
    action=receipt.dispatched===false?'Correct the named argument using the published tool schema; no device command was dispatched.':'The command was rejected before execution. Correct its arguments and use a new operation id for changed content.';
  }
  return {...receipt,errorCode,recovery:{action,steps,replayAllowed:false}};
}
