#!/usr/bin/env python3
"""Bind all interpreter-policy bodies to the pinned whole ART ELF."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(Path(__file__).parent/'coverage'))
from entry_writer_audit import Elf

def sha(data):return hashlib.sha256(data).hexdigest()
def inspect(art,output):
    output.unlink(missing_ok=True)
    image=art.read_bytes()
    expected=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())['binaries']['/apex/com.android.art/lib64/libart.so']
    if sha(image)!=expected:raise RuntimeError('INTERPRETER_ART_IDENTITY')
    header=ROOT/'android/self-hook/native/art_interpreter_contract.h';text=header.read_text();elf=Elf(image)
    bodies={'LEVEL':(0x8df858,12),'DEOPT_ALL':(0x8e09e4,16),'CONFIGURE':(0x8df864,160)}
    entries=[]
    for name,(rva,size) in bodies.items():
        address=re.search(r'#define TP_GLOBAL_'+name+r'_RVA UINT64_C\((0x[0-9a-f]+)\)',text)
        body=re.search(r'tp_global_'+name.lower()+r'_bytes\[\]\s*=\s*\{([^}]+)\}',text)
        if not address or not body or int(address[1],16)!=rva:raise RuntimeError('INTERPRETER_CONTRACT_ADDRESS:'+name)
        data=bytes(int(x.strip(),16) for x in body[1].split(',') if x.strip())
        if len(data)!=size or elf.code(rva,size)!=data:raise RuntimeError('INTERPRETER_COMPLETE_BODY:'+name)
        entries.append(dict(name=name,rva=rva,size=size,sha256=sha(data)))
    report=dict(passed=True,artSha256=expected,entries=entries,contractSha256=sha(header.read_bytes()),
        checkerSha256=sha(Path(__file__).read_bytes()),scope='Named process-lifetime interpreter policy; no executable page modification')
    output.write_text(json.dumps(report,indent=2)+'\n');return report

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--art',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();print(json.dumps(inspect(args.art,args.output)))
