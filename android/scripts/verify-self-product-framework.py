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
    need(installed['fixedMethods']==702 and installed['remainingGroups']==[6,11,10,517,62,1,2,2,4,5,1,1] and installed['groups']==[18,28,6,28] and installed['checks']==22
         and installed['entryOnlyBootRejected'] is installed['unselectedIntentServiceRejected'] is True,'FIXED_COMPONENT_INSTALLATION_CASES')
    need(installed['actualFixedInstaller'] is True and installed['productCallbacksInstalled'] is installed['engineConnected'] is installed['productAccepted'] is False,'FIXED_COMPONENT_SCOPE')
    need(installed['serviceTokenScope']['passed'] is True and installed['serviceTokenScope']['checks']==8 and installed['serviceTokenScope']['physicalAmsCalled'] is False,'SERVICE_TOKEN_SCOPE')
    need(installed['systemReceiverScope']['passed'] is True and installed['systemReceiverScope']['checks']==33 and installed['systemReceiverScope']['physicalRegistrationCalled'] is False,'SYSTEM_RECEIVER_ROLES')
    roots=installed['roots'];need(roots['published']==roots['rootedOutsideDeclaredTables']==roots['bootAllocations']==702,'FIXED_COMPONENT_ROOTS')
    report.update(checks=46,componentChecks=22,fixedComponentMethods=702,managerFacades=10,ownedBroadcast=True,verifierSha256=sha(Path(__file__)),
                  scope='Fixed accessors46 plus actual702 boot bindings/22 checks, owned physical passthrough and exact platform exceptions; product routing callbacks and Engine remain separate')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
