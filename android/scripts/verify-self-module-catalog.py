#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run,empty):
    result,build,report=read(directory,run,'H5-module-catalog');case=result['catalog'];paths=0 if empty else 4096;checks=8 if empty else 10
    need(case['paths']==paths and case['checks']==checks and case['realNotifier'] is case['ownedSyntheticCatalog'] is True
        and case['productAccepted'] is False and case['initializationNs']>0,'CATALOG_BOUNDARY')
    report.update(checks=checks,paths=paths,verifierSha256=sha(Path(__file__)),scope='Real notifier with an empty or capacity-sized owned path catalog; initialization measurement is not product performance acceptance')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);p.add_argument('--empty',action='store_true');a=p.parse_args();report=verify(a.evidence,a.run_id,a.empty)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
