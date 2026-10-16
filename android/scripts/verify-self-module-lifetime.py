#!/usr/bin/env python3
"""Real fixed-linker notifier evidence, separate from Native Hook publication."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-native-module-lifetime');case=result['lifetime']
    need(case['passed'] is True and case['failures']==0 and case['checks']==48,'MODULE_CASES')
    need(case['instanceChecks']==13 and case['instanceFailures']==0,'DISTINCT_LOADER_INSTANCES')
    need(case['concurrentCycles']==400 and case['concurrentErrors']==0,'MODULE_CONCURRENT_CALLS')
    need(case['installed'] is True and case['nativeHookInstalled'] is case['productAccepted'] is False,'MODULE_SCOPE')
    base=int(case['base'],16)
    need(int(case['debug'],16)==base+0x1bcbc0 and int(case['notifier'],16)==base+0x55d80,'FIXED_LINKER_IDENTITY')
    need(case['loaderName']=='/system/bin/linker64' and case['identityFlags']==15,'LOADED_LINKER_IDENTITY')
    need(case['seals']==15 and case['fdePcs']==83 and case['parked']>0,'NOTIFIER_RELAY')
    need(case['notifications']>=8 and case['consistent']>=4 and case['added']>=2 and case['removed']>=2,'NOTIFICATION_LIFETIME')
    need(result['ownedLibrarySha256']==build['fixtureSha256'] and result['lateLibrarySha256']==build['linkerFailureSha256'],'OWNED_NATIVE_INPUTS')
    host=json.loads((directory/'module-lifetime-host.json').read_text());need(host['passed'] is True and host['races']==2000,'LIFETIME_CAS_RACES')
    template=json.loads((directory/'notifier-template.json').read_text());need(template['passed'] is True and template['instructions']==83 and template['gprPreserved']==31 and template['simdPreserved']==32,'NOTIFIER_TEMPLATE')
    report.update(checks=case['checks'],notifications=case['notifications'],consistent=case['consistent'],added=case['added'],removed=case['removed'],verifierSha256=sha(Path(__file__)),
        scope='Synchronous fixed-linker presence/generation and lease protocol; Native Hook publication and product authority not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
