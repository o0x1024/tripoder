#!/usr/bin/env python3
"""Generate/check full fixed writer page and exact event function contracts."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from entry_writer_audit import Elf,branch

ROOT=Path(__file__).resolve().parents[4]
HEADER=ROOT/'android/self-hook/native/coverage/art_entry_writer_contract.h'
BODIES={'entry':(0x514d8c,0xa8),'reinitialize':(0x557240,0x234),'update':(0x3f55ec,0x9c),
        'cache_invalidate_all':(0x8f6874,0x2d0)}
def sha(data):return hashlib.sha256(data).hexdigest()
def inspect(art,output,write=False):
    output.unlink(missing_ok=True);image=art.read_bytes()
    expected=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())['binaries']['/apex/com.android.art/lib64/libart.so']
    if sha(image)!=expected:raise RuntimeError('WRITER_ART_IDENTITY')
    elf=Elf(image);page=elf.code(0x514000,4096)
    header=['#ifndef TP_ART_ENTRY_WRITER_CONTRACT_H','#define TP_ART_ENTRY_WRITER_CONTRACT_H','#include <stdint.h>',
            '#define TP_WRITER_PAGE_RVA UINT64_C(0x514000)']
    records=[]
    for name,(rva,size) in {'page':(0x514000,4096),**BODIES}.items():
        data=elf.code(rva,size)
        if name!='page':header.append(f'#define TP_WRITER_{name.upper()}_RVA UINT64_C(0x{rva:x})')
        header.append(f'static const uint8_t tp_writer_{name}_bytes[]={{')
        header += ['    '+','.join(f'0x{x:02x}' for x in data[i:i+16])+',' for i in range(0,size,16)]
        header.append('};');records.append(dict(name=name,rva=rva,size=size,sha256=sha(data)))
    header.append('#endif');content='\n'.join(header)+'\n'
    if write:HEADER.write_text(content)
    elif HEADER.read_text()!=content:raise RuntimeError('WRITER_COMPLETE_CONTRACT_MISMATCH')
    # No encoded direct B/BL enters the displaced instruction. This is a fixed
    # whole-text check, not a general proof about arbitrary generated pointers.
    text=elf.section('.text');start=elf.sections['.text'][3]
    middle=[]
    for offset in range(0,len(text)-3,4):
        decoded=branch(struct.unpack_from('<I',text,offset)[0],start+offset)
        if decoded and decoded[1]==0x514d94:middle.append(start+offset)
    if middle:raise RuntimeError('WRITER_MIDDLE_DIRECT_ENTRY:'+str(middle))
    report=dict(passed=True,artSha256=expected,entries=records,middleDirectBranches=middle,
        contractSha256=sha(HEADER.read_bytes()),checkerSha256=sha(Path(__file__).read_bytes()),
        scope='One fixed central entry writer; full direct-store and generated-code closure remains false')
    output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--art',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--write',action='store_true')
    a=p.parse_args();inspect(a.art,a.output,a.write)
