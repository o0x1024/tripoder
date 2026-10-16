#!/usr/bin/env python3
"""Read only this acceptance run's Sentinel tool records; export metadata, never chat or credentials."""
import argparse, hashlib, json, sqlite3
from pathlib import Path

PREFIX = 'mcp__tripoder__'
REQUIRED = {'session_get', 'process_get', 'hook_observe', 'hook_prepare', 'hook_activate', 'hook_status', 'hook_disable'}

def decode_tool_result(raw):
    # Exact Sentinel StreamContent -> MCP adapter -> Tripoder receipt structure.
    outer = json.loads(raw)
    if not isinstance(outer, list) or len(outer) != 1 or outer[0].get('type') != 'text':
        raise ValueError('SENTINEL_RESULT_ENVELOPE_UNSUPPORTED')
    adapter = json.loads(outer[0]['text'])
    if set(adapter) != {'content', 'is_error'} or not isinstance(adapter['is_error'], bool):
        raise ValueError('SENTINEL_MCP_ADAPTER_SHAPE_UNSUPPORTED')
    content = adapter['content']
    if len(content) != 1 or content[0].get('type') != 'text':
        raise ValueError('MCP_RECEIPT_ENVELOPE_UNSUPPORTED')
    text = content[0]['text'];receipt = json.loads(text)
    if receipt.get('protocol') != 1 or not isinstance(receipt.get('ok'), bool):
        raise ValueError('TRIPODER_RECEIPT_UNSUPPORTED')
    if adapter['is_error'] != (not receipt['ok']):raise ValueError('MCP_BUSINESS_STATUS_MISMATCH')
    return receipt, hashlib.sha256(text.encode()).hexdigest()

def verify(database, evidence):
    observed=json.loads((evidence/'device-observation.json').read_text())
    if not observed.get('passed') or not observed.get('sentinelClientVerified'):raise ValueError('DEVICE_AND_SENTINEL_CLIENT_MUST_PASS_FIRST')
    run=observed['runId'];audit=[json.loads(line) for line in (evidence/'sentinel-mcp-audit.jsonl').read_text().splitlines()]
    connection=sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True)
    connection.execute('PRAGMA query_only=ON');connection.row_factory=sqlite3.Row
    # Unique operation identity is sufficient even when the user sent a path to the prompt.
    matches=connection.execute("""SELECT DISTINCT conversation_id,json_extract(metadata,'$.execution_id') execution_id
      FROM ai_messages WHERE role='tool' AND json_valid(metadata)
      AND json_extract(metadata,'$.tool_name')=? AND json_extract(metadata,'$.tool_args.operationId')=?""",(PREFIX+'hook_prepare',run+'-prepare')).fetchall()
    if len(matches)!=1 or not matches[0]['execution_id']:raise ValueError('AI_EXECUTION_NOT_UNIQUE')
    conversation,execution=matches[0]
    rows=connection.execute("""SELECT id,metadata FROM ai_messages WHERE conversation_id=? AND role='tool'
      AND json_valid(metadata) AND json_extract(metadata,'$.execution_id')=?
      AND substr(json_extract(metadata,'$.tool_name'),1,?)=?""",(conversation,execution,len(PREFIX),PREFIX)).fetchall()
    assistants=connection.execute("""SELECT id,tool_calls FROM ai_messages WHERE conversation_id=? AND role='assistant'
      AND json_valid(metadata) AND json_extract(metadata,'$.execution_id')=? AND json_valid(tool_calls)""",(conversation,execution)).fetchall()
    model_calls={}
    for row in assistants:
        for call in json.loads(row['tool_calls']):
            if call.get('name','').startswith(PREFIX):model_calls.setdefault(call['id'],[]).append((row['id'],call))
    exported=[]
    for row in rows:
        meta=json.loads(row['metadata']);name=meta['tool_name'][len(PREFIX):]
        candidates=model_calls.get(row['id'],[])
        if not candidates:raise ValueError('MODEL_TOOL_CALL_NOT_PERSISTED:'+name)
        receipt,digest=decode_tool_result(meta['tool_result'])
        equal=[(message,call) for message,call in candidates if call['name']==meta['tool_name'] and json.loads(call['arguments'])==meta['tool_args'] and call['result']==meta['tool_result']]
        if not equal:raise ValueError('MODEL_AND_TOOL_RESULT_DISAGREE:'+name)
        transport=[item for item in audit if item.get('tool')==name and item.get('receiptSha256')==digest and item.get('requestId')==receipt.get('requestId')]
        if not transport:raise ValueError('AI_RECEIPT_NOT_IN_SENTINEL_TRANSPORT:'+name)
        result=receipt.get('result',{})
        exported.append({'toolCallId':row['id'],'assistantMessageId':equal[0][0],'tool':name,'sequence':meta.get('sequence'),
          'operationId':meta['tool_args'].get('operationId'),'requestId':receipt.get('requestId'),'receiptSha256':digest,
          'ok':receipt['ok'],'state':result.get('state'),'hits':result.get('hits'),'errors':result.get('errors'),
          'revision':result.get('revision'),'sourceSha256':result.get('sourceSha256')})
    succeeded={item['tool'] for item in exported if item['ok']}
    if not REQUIRED.issubset(succeeded):raise ValueError('AI_TOOL_CHAIN_INCOMPLETE')
    if not any(item['tool']=='hook_status' and item['ok'] and item['hits'] is not None and item['hits']>=2 and item['errors']==0 for item in exported):raise ValueError('AI_HITS_NOT_VERIFIED')
    if not any(item['tool']=='hook_disable' and item['ok'] and item['state']=='DISABLED' for item in exported):raise ValueError('AI_DISABLE_NOT_VERIFIED')
    usage=connection.execute("""SELECT request_id,provider,model,input_tokens,output_tokens,request_started_at_ms,request_finished_at_ms
      FROM ai_request_usage WHERE conversation_id=? AND execution_id=? ORDER BY observed_at_ms,request_id""",(conversation,execution)).fetchall()
    connection.close()
    if not usage:raise ValueError('MODEL_REQUEST_USAGE_EVIDENCE_MISSING')
    output={'schema':1,'passed':True,'runId':run,'modelToolUseVerified':True,'conversationId':conversation,'executionId':execution,
      'sourceReadOnly':True,'scope':'exact run operation -> AI execution -> model tool calls -> byte-identical MCP receipts -> device checks',
      'calls':sorted(exported,key=lambda item:(item['sequence'] or 0,item['toolCallId'])),'modelRequests':[dict(row) for row in usage],
      'phoneApprovalByOwnedTestInstrumentation':True,'businessScenariosVerified':False}
    (evidence/'sentinel-ai-verdict.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':True,'runId':run,'modelToolUseVerified':True,'calls':len(exported)}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--database',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();verify(args.database,args.evidence)
