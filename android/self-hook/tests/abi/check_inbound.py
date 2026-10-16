#!/usr/bin/env python3
"""Inspect every immutable entry slot and the common PAC/CFI stack frame."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'android/self-hook/tests/coverage'))
from entry_writer_audit import Elf,branch
def run(args):return subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True).stdout
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.unlink(missing_ok=True)
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';source=ROOT/'android/self-hook/native/abi/scalar_inbound_a64.S';obj=a.output.parent/'scalar_inbound.o'
    run([tools/'aarch64-linux-android36-clang','-c','-g','-fPIC',source,'-o',obj]);data=obj.read_bytes();elf=Elf(data)
    symbols={}
    for line in run([tools/'llvm-nm','-S','--defined-only',obj]).splitlines():
        fields=line.split()
        if len(fields)==4:symbols[fields[3]]=(int(fields[0],16),int(fields[1],16))
    begin,size=symbols['tp_scalar_inbound_entries'];common,common_size=symbols['tp_scalar_inbound_common']
    assert begin==0 and size==1024*16 and common==size and common_size==108
    code=elf.section('.text')
    # A local label resolves every slot branch within this immutable object.
    # Only the dispatcher call crosses an object boundary.
    rel=run([tools/'llvm-readelf','-r',obj]);body=run([tools/'llvm-objdump','-d',obj]);fde=run([tools/'llvm-dwarfdump','--eh-frame',obj]);notes=run([tools/'llvm-readelf','-n',obj])
    assert 'BTI, PAC' in notes and fde.count(' FDE cie=')==4
    for slot in range(1024):
        words=struct.unpack_from('<4I',code,slot*16)
        assert words[0]==0xd50324df and words[1]==0x52800010|(slot<<5) and words[3]==0xd503201f
        decoded=branch(words[2],slot*16+8)
        assert decoded and decoded[0]=='B'
        # The assembler resolves this same-section B locally on this compiler.
        assert decoded[1]==common
    assert 'R_AARCH64_CALL26' in rel and 'tp_scalar_inbound_dispatch' in rel
    assert fde.count('DW_CFA_AARCH64_negate_ra_state:')==2 and 'CFA=WSP+240' in fde and 'W30=[CFA-232]' in fde
    assert re.search(r'stp\s+x29, x30, \[sp, #-0xf0\]!',body) and re.search(r'ldp\s+x29, x30, \[sp\], #0xf0',body)
    assert re.search(r'add\s+x2, sp, #0xf0',body) and re.search(r'add\s+x3, sp, #0xd8',body)
    assert not re.search(r'\b(?:mov|ldr|ldp|add|sub)\s+x18(?:,|\b)',body)
    a.output.with_suffix('.txt').write_text(body+'\n'+fde+'\n'+notes+'\n'+rel)
    report=dict(passed=True,entries=1024,entryBytes=16,commonBytes=108,frameBytes=240,pac=True,fdeRanges=4,
        objectSha256=sha(obj),sourceSha256=sha(source),auditSha256=sha(a.output.with_suffix('.txt')),assemblyExecuted=False)
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
