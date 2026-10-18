#!/usr/bin/env python3
"""Current-build admission, late-call and JNI resource proofs, not a product verdict."""
import argparse,json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'native-core-upgrade');case=result['upgrade'];java=result['javaResources']
    need(case['passed'] is True and case['checks']==48 and case['failures']==0 and case['firstFailure']=='','UPGRADE_CASES')
    need(case['earlyAdmissionVerified'] is True and case['heldNs']>=31000000000 and case['resourceReclamation'] is False,'ADMISSION_LIFETIME')
    need(java['checks']==74 and java['failedPreparations']==64,'JNI_RESOURCE_CHECKS')
    state=java['resources'];need(state['inFlight']==state['prepared']==state['active']==0 and state['jniGlobalRoots']==1,'FINAL_RESOURCE_STATE')
    need(state['issued']==sum(state[k] for k in ['prepared','active','retired','failed']) and state['publishedResourcesRetained'] is True,'RESOURCE_CONSERVATION')
    need(result['ownedNativeSha256']==build['ownedNativeSha256'],'OWNED_FIXTURE_IDENTITY')
    audit=json.loads((directory/'inbound-audit.json').read_text())
    need(audit['passed'] is True and audit['admissionNoExternalCalls'] is True and audit['testGate'] is True,'ADMISSION_BINARY_AUDIT')
    report.update(checks=case['checks']+java['checks'],verifierSha256=sha(Path(__file__)),scope='Native admission, unchanged BUSY retirement, late original and real JNI resource accounting; broker not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as f:f.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
