#!/usr/bin/env python3
"""Independent fixed-platform E1 checks. Never promotes the experiment to product acceptance."""
import argparse
import importlib.util
import json
from pathlib import Path
from verify_self_owned_run import read, need, sha, ROOT

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    r,build,report=read(a.evidence,a.run_id,'P2-E1-owned-no-inline');maps=(a.evidence/'device/maps.txt').read_text()
    need(r['fixtureSha256']==build['fixtureSha256']==sha(build['observerLibrary']),'E1_OBSERVER_IDENTITY')
    need(r['backend']=='owned-int-entry' and r['genericManagedHook'] is False and r['productAccepted'] is False,'E1_SCOPE')
    need(all(r[k]==0 for k in ['levelBefore','levelPrepared','levelActive','levelDisabled']),'E1_NO_GLOBAL_INTERPRETER')
    need(r['knownCallersAtPrepare']==1 and r['lateCallersKnownAtPrepare']==0 and r['targetActiveBeforeLoaderCreation'] is True and r['distinctLateLoaders'] is True,'E1_LATE_LOADERS')
    need(r['primingCalls']==100000 and r['compiledCalls']==1000 and r['attemptedCalls']==r['totalIntercepted']==101002 and r['originalBodyCalls']==101003 and r['controlCalls']==100000,'E1_CALL_COUNTS')
    before,active,disabled=(r[k]['words'] for k in ['targetBefore','targetActive','targetDisabled'])
    need(before[1]&0x02000000==0 and active[1]==((before[1]&~0x00900000)|0x02000000) and disabled[1]==active[1],'E1_TARGET_FLAGS')
    state=r['activeState'];after=r['disabledState']
    need(state['ready'] is True and state['enabled'] is True and state['callbacks']==state['originals']==101002 and state['callerCount']==1,'E1_ACTIVE_CORE')
    need(after['enabled'] is False and after['callbacks']==after['originals']==101002 and after['inflight']==0 and after['transitions']==2 and r['disabledOriginalVerified'] is True,'E1_DISABLE')
    relay=int(state['relay'],16);need(active[6]|(active[7]<<32)==relay,'E1_RELAY_RETAINED')
    spec=importlib.util.spec_from_file_location('compilation',ROOT/'android/scripts/verify-self-art-compilation.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    caller=m.entry(r['callerJit'],maps);control=m.entry(r['controlJit'],maps)
    need(caller['kind']==control['kind']=='jit','E1_REAL_JIT_CALLER_AND_CONTROL')
    need((disabled[6]|(disabled[7]<<32))==int(r['targetDisabled']['base'],16)+0x2bc880,'E1_DISABLED_ENTRY')
    report.update(totalIntercepted=101002,caller=caller,control=control,globalInstrumentationLevel=0,genericManagedHook=False,productPolicyChanged=False,verifierSha256=sha(Path(__file__)))
    with (a.evidence/'verification.json').open('x') as f:f.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))

if __name__=='__main__':main()
