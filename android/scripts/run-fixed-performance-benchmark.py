#!/usr/bin/env python3
"""Explicit temporary Android fixed-performance experiment; always requests OFF on exit.

Requires user authorization for enabling this global development mode and leaving
it disabled afterwards: Android exposes no corresponding state getter here.
This changes no production runtime behavior and uses no root or sysfs writes.
"""
import argparse
import datetime
import json
from pathlib import Path
import signal
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--baseline-root',required=True,type=Path)
    parser.add_argument('--evidence',required=True,type=Path)
    parser.add_argument('--authorized-enable-and-disable',action='store_true',required=True)
    args=parser.parse_args();out=args.evidence.resolve();out.mkdir(parents=True,exist_ok=False)
    record={'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'rootUsed':False,'priorModeQueryable':False,'authorizedFinalMode':'disabled','commands':[]}
    child=None
    def adb(*command):
        result=subprocess.run(['adb','-s',args.serial,*command],capture_output=True,text=True,timeout=30)
        record['commands'].append({'command':list(command),'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
        return result
    def interrupted(signum,frame):
        raise InterruptedError('FIXED_PERFORMANCE_RUN_INTERRUPTED:'+str(signum))
    for signum in (signal.SIGTERM,signal.SIGINT):signal.signal(signum,interrupted)
    try:
        adb('shell','dumpsys','thermalservice').check_returncode()
        adb('shell','cmd','power','set-fixed-performance-mode-enabled','true').check_returncode()
        child=subprocess.Popen([sys.executable,str(Path(__file__).with_name('benchmark-native-core.py')),'--serial',args.serial,'--baseline-root',str(args.baseline_root.resolve()),'--evidence',str(out/'benchmark'),'--rounds','5','--precondition-rounds','1','--cpu','5'])
        record['benchmarkExitCode']=child.wait()
        if child.returncode:raise RuntimeError('FIXED_PERFORMANCE_BENCHMARK_FAILED')
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:child.wait(timeout=20)
            except subprocess.TimeoutExpired:child.kill();child.wait()
        # Do not leave a development-only system mode enabled after failure.
        try:
            disabled=adb('shell','cmd','power','set-fixed-performance-mode-enabled','false')
            record['disableCommandAcknowledged']=disabled.returncode==0
            disabled.check_returncode()
            adb('shell','dumpsys','thermalservice')
        finally:
            record['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            (out/'mode-control-receipt.json').write_text(json.dumps(record,indent=2)+'\n')


if __name__=='__main__':main()
