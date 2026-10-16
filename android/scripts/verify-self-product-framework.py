#!/usr/bin/env python3
"""Fixed F2/F4/F5 members, own broadcast and constructed value evidence."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run):
    result,build,report=read(directory,run,'H4-product-framework');case=result['framework'];registry=result['registry']
    need(case['checks']==46 and case['managerFacades']==10 and case['ownedBroadcast'] is case['nativeLongPrecision'] is case['providerHandleIdentity'] is True
         and case['hookInstalled'] is True and case['productCallbacksInstalled'] is case['productAccepted'] is False,'PRODUCT_FRAMEWORK_VALUES')
    need(registry['ready'] is True and registry['stage']=='READY' and registry['jniIdType']==0 and registry['calls']<=8192,'PRODUCT_FIXED_REGISTRY')
    installed=case['componentInstall']
    need(installed['fixedMethods']==652 and installed['remainingGroups']==[5,10,10,514,62,1,1,4] and installed['groups']==[14,28,3] and installed['checks']==17,'FIXED_COMPONENT_INSTALLATION_CASES')
    need(installed['actualFixedInstaller'] is True and installed['productCallbacksInstalled'] is installed['engineConnected'] is installed['productAccepted'] is False,'FIXED_COMPONENT_SCOPE')
    roots=installed['roots'];need(roots['published']==roots['rootedOutsideDeclaredTables']==roots['bootAllocations']==652,'FIXED_COMPONENT_ROOTS')
    report.update(checks=46,componentChecks=17,fixedComponentMethods=652,managerFacades=10,ownedBroadcast=True,verifierSha256=sha(Path(__file__)),
                  scope='Fixed accessors46 plus actual652 boot bindings/17 checks, owned physical passthrough and exact platform exceptions; product routing callbacks and Engine remain separate')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
