#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-native-inbound-abi');case=result['inbound']
    need(case['passed'] is True and case['checks']==78 and case['failures']==0 and case['typedCases']==13,'INBOUND_TYPED_ABI')
    need(case['concurrentCalls']==case['nestedCalls']==4000 and case['originalCalls']==4001 and case['concurrentErrors']==0,'NESTED_TLS_ORIGINAL_CALLS')
    need(case['entryCapacity']==1024 and case['finalSlot']==1023 and case['unwindCases']==13,'ENTRY_CAPACITY_AND_ACTUAL_UNWIND')
    need(case['hookInstalled'] is case['productAccepted'] is False,'INBOUND_SCOPE')
    audit=json.loads((directory/'inbound-audit.json').read_text())
    need(audit['passed'] is True and audit['entries']==1024 and audit['frameBytes']==240 and audit['fdeRanges']==4 and audit['pac'] is True and audit['admissionNoExternalCalls'] is True,'INBOUND_ELF_AUDIT')
    need(audit['auditSha256']==sha(directory/'inbound-audit.txt'),'INBOUND_AUDIT_IDENTITY')
    report.update(checks=78,typedCases=13,concurrentCalls=4000,unwindCases=13,verifierSha256=sha(Path(__file__)),
        scope='Real compiled caller → immutable Native entry → scalar dispatch → nested entry/original → ABI return/errno; no export patch or product JNI yet')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
