#!/usr/bin/env python3
"""Run paired, independent cold starts; retain every failed sample, never retry it."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--receipt',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--serial',required=True)
    p.add_argument('--runs',type=int,default=5)
    args=p.parse_args()
    if not 1<=args.runs<=30:raise ValueError('runs must be 1..30')
    receipt=json.loads(args.receipt.read_text());out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    def adb(*argv,check=True):
        r=subprocess.run(['adb','-s',args.serial,*argv],capture_output=True,text=True,timeout=60)
        if check and r.returncode:raise RuntimeError(r.stderr+r.stdout)
        return r
    (out/'build-receipt.json').write_text(json.dumps(receipt,indent=2))
    (out/'thermal-before.txt').write_text(adb('shell','dumpsys','thermalservice').stdout)
    for item in receipt['artifacts'].values():
        if hashlib.sha256(Path(item['path']).read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('APK_CHANGED')
        adb('install','-r',item['path'])
    rows=[]
    for index in range(args.runs):
        for kind in (('fixture','host') if index%2==0 else ('host','fixture')):
            item=receipt['artifacts'][kind];package=item['package'];run=f'{kind}-{index}-{time.time_ns()}'
            adb('shell','am','force-stop',package)
            start=adb('shell','am','start','-W','-n',package+'/dev.tripoder.baseline.BaselineActivity','--es','run',run,check=False)
            (out/(run+'-am.txt')).write_text(start.stdout+start.stderr)
            until=time.monotonic()+20;row=None
            while time.monotonic()<until:
                result=adb('shell','run-as',package,'cat','files/'+run+'.json',check=False)
                if result.returncode==0:
                    try:row=json.loads(result.stdout);break
                    except json.JSONDecodeError:pass
                time.sleep(.25)
            if row is None:row={'run':run,'failure':'RECEIPT_TIMEOUT','hosted':kind=='host'}
            row['amReturnCode']=start.returncode
            row['artifactSha256']=item['sha256']
            if row.get('payloadSha256')!=receipt['artifacts']['fixture']['sha256']:row['failure']=row.get('failure') or 'PAYLOAD_DIGEST_MISMATCH'
            rows.append(row);(out/(run+'.json')).write_text(json.dumps(row,indent=2))
            adb('shell','am','force-stop',package)
            print(json.dumps({k:row.get(k) for k in ('run','pid','failure','frames','firstDrawFromActivityNs','deadlineJank')}),flush=True)
    summary={'schema':1,'level':0,'serial':args.serial,'runsPerArm':args.runs,'samples':rows,'passed':all(r.get('failure') is None and r.get('firstDrawFromActivityNs',0)>0 and r.get('frames',0)>=180 for r in rows),'commercialApplicationsAccepted':False,'performanceReleaseAccepted':False}
    summary['observed']={}
    for kind in ('fixture','host'):
        samples=[r for r in rows if r['hosted']==(kind=='host') and not r.get('failure')]
        if samples:summary['observed'][kind]={'firstDrawP50Ms':statistics.median(r['firstDrawFromActivityNs']/1e6 for r in samples),'frames':sum(r['frames'] for r in samples),'deadlineJank':sum(r['deadlineJank'] for r in samples),'refreshRates':sorted(set(r['refreshRate'] for r in samples))}
    (out/'thermal-after.txt').write_text(adb('shell','dumpsys','thermalservice').stdout)
    (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    if not summary['passed']:raise SystemExit('Level 0 sample failures retained in '+str(out))


if __name__=='__main__':main()
