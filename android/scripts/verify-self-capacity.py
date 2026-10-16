#!/usr/bin/env python3
"""Check every real original/root and the last slot of the compiled ART writer."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run_id):
    result,build,report=read(directory,run_id,'H4-process-capacity');case=result['capacity'];limit=4096
    need(case['batchHelpers'] is case['batchPublication'] is True,'CAPACITY_ACTUAL_BATCH_PATH')
    need(case['entries']==limit and case['declaringClasses']==32 and case['enabledCalls']==2*limit
         and case['disabledCalls']==limit and case['bodyCalls']==3*limit
         and case['overflowRejected'] is True and case['remainingAfterDisable']==0 and case['productAccepted'] is False,'CAPACITY_CALLS_AND_LIMIT')
    need([x['slot'] for x in case['checkpoints']]==[63,64,1270,4095],'CAPACITY_BOUNDARY_SLOTS')
    for item in case['checkpoints']:
        state=item['state'];need(state['active'] is state['writerInterpositionInstalled'] is state['writerOwnerRegistered'] is True
            and state['targetQuick']==state['relay']==state['desiredEntry'] and state['originals']==1
            and state['fullCallerCoverage'] is state['completeEntryWriterClosure'] is False,'CAPACITY_ENTRY_OWNERSHIP')
    for key in ('beforeGc','afterGc'):
        storage=case[key];need(storage['allocations']==storage['published']==storage['rootedOutsideDeclaredTables']==limit
            and storage['retainedSlotRoots']==6*limit and storage['bootAllocations']==0,'CAPACITY_ROOTS')
        snapshots=storage['rootSnapshots'];need(len(snapshots)==limit and [x['slot'] for x in snapshots]==list(range(limit)),'CAPACITY_COMPLETE_SNAPSHOTS')
        need(len({x['target'] for x in snapshots})==len({x['backup'] for x in snapshots})==limit,'CAPACITY_DISTINCT_METHODS')
        need(len({x['ownerAfter'] for x in snapshots})==32,'CAPACITY_CLASS_GROUPS')
        for snapshot in snapshots:
            need(snapshot['rooted'] is True and snapshot['ownerAfter']==snapshot['targetOwner']==snapshot['backupOwner']
                 and int(snapshot['target'],16)!=int(snapshot['backup'],16) and snapshot['bootAllocator'] is False,'CAPACITY_ROOT_IDENTITY')
    events=case['writerEvents'];last=case['checkpoints'][-1]['state']['relay']
    need(events['passed'] is events['originalWritersExecuted'] is True and events['reinitializeCalls']==events['updateCalls']==64
         and events['globalInvalidations']==4 and events['errors']==0 and events['before']==events['after']==events['desired']==last,'CAPACITY_LAST_SLOT_WRITER')
    audit=json.loads((directory/'near-leaf-audit.json').read_text())
    need(audit['passed'] is True and audit['registrySlots']==limit and audit['instructions']==23 and audit['constantFdeSegments']==1,'CAPACITY_COMPILED_LEAF')
    report.update(entries=limit,enabledCalls=2*limit,disabledCalls=limit,originalCalls=2*limit,writerEvents=132,
        durationMs=case['durationMs'],observedOwnerMoves=case['afterGc']['observedOwnerMoves'],
        verifierSha256=sha(Path(__file__)),scope='4096 owned managed entries and roots; product fixed installers remain separate')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
