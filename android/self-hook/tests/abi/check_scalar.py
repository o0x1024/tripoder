#!/usr/bin/env python3
"""Pure-C sanitizers and independent LLVM ABI/BTI/CFI audits; device tests are separate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
ROOT=Path(__file__).resolve().parents[4]
def run(args):return subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True).stdout
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.unlink(missing_ok=True);a.output.parent.mkdir(parents=True,exist_ok=True)
    core=ROOT/'android/self-hook';native=core/'native/abi';fixture=ROOT/'android/self-hook-probe/abi-fixtures/native'
    tests=Path(__file__).parent;build=core/'tests/abi/build';build.mkdir(exist_ok=True)
    run(['python3',tests/'generate_cases.py','--check'])
    host=build/'scalar-host';run(['clang','-std=c11','-O2','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-I',native,native/'scalar_abi.c',tests/'scalar_host_test.c','-o',host])
    output=run([host]);m=re.search(r'SCALAR_ABI_HOST_PASS checks=(\d+) assemblyExecuted=false',output);assert m and int(m[1])==2103
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';compiler=tools/'aarch64-linux-android36-clang'
    decoded=[]
    for source,name in [(native/'scalar_call_a64.S','scalar_call'),(fixture/'scalar_capture_a64.S','scalar_capture')]:
        obj=a.output.parent/(name+'.o');run([compiler,'-c','-g','-fPIC',source,'-o',obj])
        body=run([tools/'llvm-objdump','-d',obj]);unwind=run([tools/'llvm-dwarfdump','--eh-frame',obj]);notes=run([tools/'llvm-readelf','-n',obj])
        assert 'bti\tc' in body or re.search(r'\bbti\s+c\b',body)
        assert 'aarch64 feature: bti' in notes.lower() and 'FDE' in unwind
        if name=='scalar_call':
            assert re.search(r'blr\s+x20',body) and 'DW_CFA_def_cfa: W29 +48' in unwind
            assert re.search(r'sub\s+sp, sp, x22',body) and re.search(r'mov\s+sp, x29',body)
            assert not re.search(r'\b(?:mov|ldr|ldp|add|sub)\s+x18(?:,|\b)',body)
        (a.output.parent/(name+'-audit.txt')).write_text(body+'\n'+unwind+'\n'+notes)
        decoded.append({'name':name,'objectSha256':sha(obj),'auditSha256':sha(a.output.parent/(name+'-audit.txt'))})
    inputs=[native/'scalar_abi.c',native/'scalar_abi.h',native/'register_abi.c',native/'register_abi.h',native/'scalar_call_a64.S',fixture/'scalar_capture_a64.S',
        fixture/'scalar_fixture.c',fixture/'scalar_cases.c',fixture/'scalar_cases.h',fixture/'scalar_capture.h',
        tests/'scalar_host_test.c',tests/'generate_cases.py',Path(__file__)]
    report={'passed':True,'hostChecks':2103,'assemblyExecuted':False,'typedCases':13,'decoded':decoded,
        'sourceHashes':{str(p.relative_to(ROOT)):sha(p) for p in inputs},'scope':'Host layout checks and LLVM audits only; owned-device execution is separate'}
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'hostChecks':2103,'typedCases':13}))
if __name__=='__main__':main()
