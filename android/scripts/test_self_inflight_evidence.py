#!/usr/bin/env python3
"""Mutation checks for the AOT counterexample/early-entry evidence boundary."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile

SCRIPT=Path(__file__).with_name('verify-self-inflight.py')
spec=importlib.util.spec_from_file_location('inflight_verifier',SCRIPT)
verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--late',type=Path,required=True)
    parser.add_argument('--early',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();checks=[]
    for early,source in ((False,args.late),(True,args.early)):
        run=json.loads((source/'run-plan.json').read_text())['runId']
        report=verifier.verify(source,run,early)
        assert report['counterexampleVerified']==(not early)
        assert report['preexistingAotMigrationAccepted'] is report['productAccepted'] is False
        checks.append(('early' if early else 'late')+'_baseline_scope')
        with tempfile.TemporaryDirectory(prefix='tripoder-inflight-evidence-') as temporary:
            directory=Path(temporary)/'evidence';shutil.copytree(source,directory)
            path=directory/'device/result.json';original=json.loads(path.read_text())
            mutations=[
                ('wrong_resumed_result',('resumedValue',),10 if early else 110,'FRAME_BOUNDARY_VALUES_AND_ORIGINALS'),
                ('wrong_original_count',('after','originals'),1 if early else 2,'FRAME_BOUNDARY_VALUES_AND_ORIGINALS'),
                ('claim_existing_frame_migration',('policyAfter','preexistingCompiledFramesCovered'),True,'POLICY_SCOPE'),
                ('claim_product_complete',('productAccepted',),True,'FRAME_BOUNDARY_VALUES_AND_ORIGINALS'),
                ('claim_full_coverage',('policyAfter','fullCallerCoverage'),True,'POLICY_SCOPE'),
                ('claim_policy_before_frame',('policyBeforeFrame','owned'),not early,'POLICY_PRECEDES_FRAME'),
            ]
            for label,keys,value,reason in mutations:
                changed=copy.deepcopy(original);node=changed['inflight']
                for key in keys[:-1]:node=node[key]
                node[keys[-1]]=value;path.write_text(json.dumps(changed))
                try:
                    verifier.verify(directory,run,early)
                except RuntimeError as error:
                    assert str(error)==reason,(label,str(error),reason)
                else:
                    raise AssertionError('INVALID_EVIDENCE_ACCEPTED:'+label)
                checks.append(('early_' if early else 'late_')+label)
    report=dict(passed=True,checks=len(checks),cases=checks,
                verifierSha256=verifier.sha(SCRIPT),testSha256=verifier.sha(Path(__file__)),
                lateRun=args.late.name,earlyRun=args.early.name,deviceExecuted=False)
    args.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':main()
