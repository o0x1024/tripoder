#!/usr/bin/env python3
"""Verify a real Sentinel MCP download from persisted model calls and local bytes.

Reads the user database in read-only mode. Exports receipt metadata only; never
exports chat, tool arguments, binary payloads or connection configuration.
"""
import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def receipt(raw):
    outer = json.loads(raw)
    assert len(outer) == 1 and outer[0]['type'] == 'text', 'STREAM_ENVELOPE'
    adapter = json.loads(outer[0]['text'])
    assert len(adapter['content']) == 1, 'MCP_ENVELOPE'
    text = adapter['content'][0]['text']
    value = json.loads(text)
    assert isinstance(value['ok'], bool), 'MCP_STATUS'
    assert adapter['is_error'] == (not value['ok']), 'MCP_STATUS_DISAGREEMENT'
    assert len(raw) < 8192 and 'base64' not in value['result'], 'BINARY_IN_MODEL_CONTEXT'
    return value, digest(text.encode())


def verify(args):
    prefix = 'mcp__' + args.server_name + '__'
    with sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        matches = db.execute("""SELECT DISTINCT conversation_id,
          json_extract(metadata,'$.execution_id') execution_id
          FROM ai_messages WHERE role='tool' AND json_valid(metadata)
          AND json_extract(metadata,'$.tool_name')=?
          AND json_extract(metadata,'$.tool_args.operationId')=?
          AND json_extract(metadata,'$.tool_args.action')='start'""",
          (prefix + 'file_pull', args.operation_id)).fetchall()
        assert len(matches) == 1, 'EXECUTION_NOT_UNIQUE'
        conversation, execution = matches[0]
        run = db.execute('SELECT state FROM agent_harness_runs WHERE id=? AND conversation_id=?',
                         (execution, conversation)).fetchone()
        assert run and run['state'] == 'succeeded', 'RUN_NOT_SUCCEEDED'
        calls = {}
        for row in db.execute("SELECT id,tool_calls FROM ai_messages WHERE conversation_id=? AND role='assistant' AND json_valid(tool_calls)", (conversation,)):
            for call in json.loads(row['tool_calls']):
                calls.setdefault(call['id'], []).append((row['id'], call))
        exported, final = [], None
        for row in db.execute("""SELECT id,metadata FROM ai_messages WHERE conversation_id=?
          AND role='tool' AND json_extract(metadata,'$.execution_id')=?""", (conversation, execution)):
            meta = json.loads(row['metadata'])
            assert meta['tool_name'] in [prefix + 'file_pull', prefix + 'file_pull_status'], 'UNEXPECTED_TOOL'
            assert meta['tool_args']['operationId'] == args.operation_id, 'OPERATION_CHANGED'
            equal = [(message, call) for message, call in calls.get(row['id'], [])
                     if call['name'] == meta['tool_name']
                     and json.loads(call['arguments']) == meta['tool_args']
                     and call['result'] == meta['tool_result'] and call['success']]
            assert equal and meta['success'], 'MODEL_TOOL_RECEIPT_DISAGREEMENT'
            value, sha = receipt(meta['tool_result'])
            assert value['ok'], 'DOWNLOAD_CALL_FAILED'
            result = value['result']
            if result['state'] == 'COMPLETE':
                assert meta['tool_name'] == prefix + 'file_pull_status', 'FINAL_STATUS_NOT_OBSERVED'
                final = result
            exported.append({'toolCallId': row['id'], 'assistantMessageId': equal[0][0],
                             'tool': meta['tool_name'], 'sequence': meta['sequence'],
                             'state': result['state'], 'bytes': result.get('bytes'),
                             'receiptSha256': sha, 'resultChars': len(meta['tool_result'])})
        assert final and len(exported) >= 2, 'COMPLETE_RECEIPT_MISSING'
        requests = db.execute("SELECT provider,model,input_tokens,output_tokens FROM ai_request_usage WHERE conversation_id=? AND execution_id=?",
                              (conversation, execution)).fetchall()
        assert requests, 'REAL_MODEL_USAGE_MISSING'
    path, manifest_path = Path(final['destination']), Path(final['manifestPath'])
    actual_sha = digest(path.read_bytes())
    assert path.stat().st_size == final['bytes'] == final['totalBytes'] == args.expected_size, 'SIZE_MISMATCH'
    assert actual_sha == final['sha256'] == args.expected_sha256, 'HASH_MISMATCH'
    manifest_bytes = manifest_path.read_bytes()
    assert digest(manifest_bytes) == final['manifestSha256'], 'MANIFEST_HASH_MISMATCH'
    manifest = json.loads(manifest_bytes)
    assert manifest['exportId'] == args.operation_id and len(manifest['entries']) == final['files'] == 1, 'MANIFEST_IDENTITY'
    entry = manifest['entries'][0]
    assert entry['size'] == args.expected_size and entry['sha256'] == actual_sha, 'MANIFEST_FILE_MISMATCH'
    output = {'schema': 1, 'passed': True, 'sourceReadOnly': True,
              'conversationId': conversation, 'executionId': execution,
              'operationId': args.operation_id, 'server': args.server_name,
              'modelToolUseVerified': True, 'localBytesVerified': True,
              'state': final['state'], 'destination': str(path), 'manifestPath': str(manifest_path),
              'size': path.stat().st_size, 'sha256': actual_sha,
              'manifestSha256': final['manifestSha256'],
              'calls': sorted(exported, key=lambda call: call['sequence']),
              'modelRequests': len(requests),
              'models': sorted({row['provider'] + '/' + row['model'] for row in requests})}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: output[key] for key in ['passed', 'conversationId', 'modelRequests', 'size', 'sha256']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True, type=Path)
    parser.add_argument('--server-name', required=True)
    parser.add_argument('--operation-id', required=True)
    parser.add_argument('--expected-size', required=True, type=int)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--output', required=True, type=Path)
    verify(parser.parse_args())
