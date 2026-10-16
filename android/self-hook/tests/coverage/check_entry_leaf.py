#!/usr/bin/env python3
"""Audit the prelinked signed-LR-preserving leaf; does not install an ART hook."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
ROOT=Path(__file__).resolve().parents[4]
def run(args):return subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True).stdout
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.unlink(missing_ok=True)
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';source=ROOT/'android/self-hook/coverage-fixtures/entry_leaf_fixture.S'
    obj=a.output.parent/'entry_leaf_fixture.o';a.output.parent.mkdir(parents=True,exist_ok=True)
    run([tools/'aarch64-linux-android36-clang','-c','-g',source,'-o',obj])
    body=run([tools/'llvm-objdump','-d','--disassemble-symbols=tp_entry_fixture_leaf',obj])
    instructions=[(m[1],m[2]) for m in re.finditer(r'^\s*[0-9a-f]+:\s+[0-9a-f]+\s+(\w+)\s*(.*)$',body,re.M)]
    assert instructions and not re.search(r'\b(?:bl|blr|ret|autiasp|paciasp|msr)\b',body)
    writes={'x1','w9','x10','x11','x12','x13','x14'}
    for opcode,args in instructions:
        if opcode in {'adrp','add','mov','ldar','ldr','subs'}:assert args.split(',')[0].strip() in writes,(opcode,args)
        else:assert opcode in {'cmp','tst','b','cbz','stp','brk'},(opcode,args)
    assert sum(op=='stp' for op,_ in instructions)==1 and re.search(r'stp\s+x29, x30, \[sp, #-0x20\]!',body)
    assert sum(op=='ldar' for op,_ in instructions)==2 and re.search(r'mov\s+w9, #0x1000\b',body)
    unwind=run([tools/'llvm-dwarfdump','--eh-frame',obj]);assert 'DW_CFA_AARCH64_negate_ra_state' in unwind
    assert 'CFA=WSP+32' in unwind or 'CFA=SP+32' in unwind or 'DW_CFA_def_cfa_offset: +32' in unwind
    symbols=run([tools/'llvm-nm','-S',obj])
    symbol=next(l.split() for l in symbols.splitlines() if l.endswith(' tp_entry_fixture_leaf'))
    begin,size=int(symbol[0],16),int(symbol[1],16)
    fdes=[]
    for match in re.finditer(r'^\w+ [^\n]* FDE cie=\w+ pc=([0-9a-f]+)\.\.\.([0-9a-f]+)\n(.*?)(?=^\w+ [^\n]* (?:CIE|FDE)|\Z)',unwind,re.M|re.S):
        start,end=int(match[1],16),int(match[2],16)
        if start<begin+size and end>begin:fdes.append((start,end,match[3]))
    assert sorted((start-begin,end-begin) for start,end,_ in fdes)==[(0,76),(76,80),(80,88)]
    for offset in range(0,88,4):
        selected=[body for start,end,body in fdes if start<=begin+offset<end];assert len(selected)==1
        state=selected[0];assert 'DW_CFA_advance' not in state and 'reg34=1' in state
        if offset==76:assert 'CFA=WSP+32: W29=[CFA-32], W30=[CFA-24]' in state
        else:assert 'CFA=WSP: W29=same, W30=same' in state
    a.output.with_suffix('.txt').write_text(body+'\n'+unwind)
    report={'passed':True,'instructions':len(instructions),'registrySlots':4096,'acquireLoads':2,'leafCalls':0,
        'leafPacOrAut':0,'preservedOriginalSigningSite':True,'prelinkedFdeDecoded':True,'deviceExecuted':False,
        'constantFdeSegments':3,'coveredInstructionPcs':22,
        'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'objectSha256':hashlib.sha256(obj.read_bytes()).hexdigest(),
        'auditSha256':hashlib.sha256(a.output.with_suffix('.txt').read_bytes()).hexdigest(),'checkerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
