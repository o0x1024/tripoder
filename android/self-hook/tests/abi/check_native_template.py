#!/usr/bin/env python3
"""Emit a compiled Native relay/original ELF with bounded file-only FDE fields."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'android/self-hook/tests/coverage'))
from entry_writer_audit import Elf
def run(args):return subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True).stdout
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.unlink(missing_ok=True);tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    source=ROOT/'android/self-hook/native/abi/native_entry_template.S';library=a.output.parent/'libtripoder-native-entry-template.so'
    run([tools/'aarch64-linux-android36-clang','-shared','-nostdlib','-fPIC',source,
        '-Wl,-Bsymbolic,--build-id=none,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--eh-frame-hdr','-o',library])
    data=library.read_bytes();elf=Elf(data);symbols={}
    for line in run([tools/'llvm-nm','-S','--defined-only',library]).splitlines():
        fields=line.split()
        if len(fields)==4:symbols[fields[3]]=(int(fields[0],16),int(fields[1],16))
    metadata={}
    for name,section,width in [('relay','.text',16),('original','.text',16),('displaced','.text',4),('back','.text',4),
        ('auth','.text',4),('prefix_instruction','.text',4),('relay_ra_start','.eh_frame',1),('relay_ra_end','.eh_frame',1),('early_ra_state','.eh_frame',1),
        ('callback','.rodata',8),('ra_state','.eh_frame',1),('frame','.eh_frame',2),('saved1','.eh_frame',3),('saved2','.eh_frame',3)]:
        rva,size=symbols['tp_native_'+name];assert size==width;s=elf.sections[section]
        metadata['TP_NATIVE_'+name.upper()+'_RVA']=rva;metadata['TP_NATIVE_'+name.upper()+'_FILE']=s[4]+rva-s[3]
    assert elf.code(metadata['TP_NATIVE_ORIGINAL_RVA'],16)==struct.pack('<4I',0xd503245f,0xd503201f,0xd503201f,0x14000000)
    assert elf.span(metadata['TP_NATIVE_CALLBACK_FILE'],8)==b'\0'*8
    dynamic=run([tools/'llvm-readelf','-d','-r','-l','-n',library]);fde=run([tools/'llvm-dwarfdump','--eh-frame',library])
    assert not any(s in dynamic for s in ['(NEEDED)','(TEXTREL)','(INIT)','(INIT_ARRAY)','(FINI)','R_AARCH64_'])
    assert 'BTI, PAC' in dynamic and 'GNU_EH_FRAME' in dynamic and fde.count(' FDE cie=')==2
    phoff=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54);assert size==56
    loads=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*56) for i in range(count)];loads=[x for x in loads if x[0]==1]
    assert min(x[3] for x in loads)==0 and all(x[7]==4096 and x[1]&3!=3 for x in loads)
    span=(max(x[3]+x[6] for x in loads)+4095)&~4095;assert span<=65536 and len(data)<=65536;metadata['TP_NATIVE_LOAD_BYTES']=span
    header=['#ifndef TP_NATIVE_ENTRY_IMAGE_H','#define TP_NATIVE_ENTRY_IMAGE_H','#include <stdint.h>']+[f'#define {k} {v}u' for k,v in metadata.items()]
    header+=['static const unsigned char tp_native_entry_elf[]={']+['    '+','.join(f'0x{x:02x}' for x in data[i:i+16])+',' for i in range(0,len(data),16)]+['};','#endif']
    (a.output.parent/'native_entry_image.h').write_text('\n'.join(header)+'\n')
    a.output.with_suffix('.txt').write_text(run([tools/'llvm-objdump','-d',library])+'\n'+fde+'\n'+dynamic)
    report=dict(passed=True,metadata=metadata,relocations=0,dependencies=[],fdeRanges=2,templateSha256=hashlib.sha256(data).hexdigest(),deviceExecuted=False)
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
