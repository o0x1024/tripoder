#!/usr/bin/env python3
"""Pin actual linker bytes; emit/verify the native contract, not a portable guess."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'android/self-hook/tests/coverage'))
from entry_writer_audit import Elf
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--linker',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--write',action='store_true');a=p.parse_args()
    data=a.linker.read_bytes();expected=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())['binaries']['/apex/com.android.runtime/bin/linker64']
    assert sha(data)==expected
    elf=Elf(data);sym=elf.sections['.symtab'];strings=elf.entries[sym[6]];names=elf.span(strings[4],strings[5]);found={}
    for offset in range(sym[4],sym[4]+sym[5],24):
        name,info,other,section,value,size=struct.unpack_from('<IBBHQQ',data,offset)
        text=names[name:names.index(0,name)].decode()
        if text in {'__dl_rtld_db_dlactivity','__dl__r_debug'}:found[text]=(value,size)
    assert found=={'__dl_rtld_db_dlactivity':(0x55d80,8),'__dl__r_debug':(0x1bcbc0,40)}
    code=elf.code(0x55d80,8);assert code==bytes.fromhex('5f2403d5c0035fd6')
    page=elf.code(0x55000,4096)
    build=bytes.fromhex('436b6338be03b657e1fc48074ab87b59')
    assert build in elf.section('.note.gnu.build-id')
    lines=['#ifndef TP_LINKER_CONTRACT_H','#define TP_LINKER_CONTRACT_H','#include <stdint.h>',
        '#define TP_LINKER_PATH "/apex/com.android.runtime/bin/linker64"',
        '#define TP_LINKER_LOADED_NAME "/system/bin/linker64"',
        '#define TP_LINKER_NOTIFIER_RVA 0x55d80u','#define TP_LINKER_DEBUG_RVA 0x1bcbc0u',
        '#define TP_LINKER_PAGE_RVA 0x55000u',f'#define TP_LINKER_SHA256 "{expected}"',
        'static const unsigned char tp_linker_build_id[]={'+','.join(f'0x{x:02x}' for x in build)+'};',
        'static const uint32_t tp_linker_page[1024]={']
    words=struct.unpack('<1024I',page)
    lines+=['    '+','.join(f'0x{x:08x}u' for x in words[i:i+8])+',' for i in range(0,1024,8)]
    lines+=['};','#endif'];header='\n'.join(lines)+'\n';path=ROOT/'android/self-hook/native/modules/linker_contract.h'
    if a.write:path.write_text(header)
    assert path.read_text()==header,'LINKER_CONTRACT_DIFFERS'
    report=dict(passed=True,linkerSha256=expected,notifierRva='55d80',debugRva='1bcbc0',codeSha256=sha(code),pageSha256=sha(page),buildId=build.hex(),headerSha256=sha(header.encode()),checkerSha256=sha(Path(__file__).read_bytes()))
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
