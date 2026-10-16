#!/usr/bin/env python3
"""Build the relocation-free, sealed-file notifier relay and inspect its ABI/FDE."""
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
from entry_writer_audit import Elf
def run(args):return subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True).stdout
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.unlink(missing_ok=True)
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';source=ROOT/'android/self-hook/native/modules/notifier_template.S';library=a.output.parent/'libtripoder-notifier-template.so'
    run([tools/'aarch64-linux-android36-clang','-shared','-nostdlib','-fPIC',source,'-Wl,-Bsymbolic,--build-id=none,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--eh-frame-hdr','-o',library])
    data=library.read_bytes();elf=Elf(data);symbols={}
    for line in run([tools/'llvm-nm','-S','--defined-only',library]).splitlines():
        fields=line.split()
        if len(fields)==4:symbols[fields[3]]=(int(fields[0],16),int(fields[1],16))
    entry,size=symbols['tp_linker_notifier_relay'];callback,callback_size=symbols['tp_linker_notifier_callback'];assert callback_size==8
    def file(rva,section):s=elf.sections[section];return s[4]+rva-s[3]
    code_file=file(entry,'.text');callback_file=file(callback,'.rodata');assert data[callback_file:callback_file+8]==b'\0'*8
    body=run([tools/'llvm-objdump','-d',library]);fde=run([tools/'llvm-dwarfdump','--eh-frame',library]);dynamic=run([tools/'llvm-readelf','-d','-r','-l','-n',library])
    assert not any(word in dynamic for word in ['(NEEDED)','(TEXTREL)','(INIT)','(INIT_ARRAY)','(FINI)','R_AARCH64_'])
    assert 'BTI' in dynamic and 'GNU_EH_FRAME' in dynamic
    instructions=[m.groups() for m in re.finditer(r'^\s*([0-9a-f]+):\s+[0-9a-f]+\s+(\w+)\s*(.*)$',body,re.M)]
    assert len(instructions)*4==size and instructions[0][1:]==('bti','jc')
    assert sum(op=='blr' for _,op,_ in instructions)==1 and sum(op=='ret' for _,op,_ in instructions)==1
    assert not any(op in {'b','br','bl','paciasp','autiasp'} for _,op,_ in instructions)
    # One save and restore at each expected slot, including non-ABI scratch
    # GPRs, all 128 SIMD bits, and all three status registers.
    for prefix,pairs,offset in [('x',15,0),('q',16,256)]:
        for pair in range(pairs):
            registers=f'{prefix}{pair*2}, {prefix}{pair*2+1}'
            assert sum(op=='stp' and args.startswith(registers+', [sp') for _,op,args in instructions)==1
            assert sum(op=='ldp' and args.startswith(registers+', [sp') for _,op,args in instructions)==1
    for reg in ['nzcv','fpsr','fpcr']:
        assert sum(op=='mrs' and args.endswith(reg.upper()) for _,op,args in instructions)==1
        assert sum(op=='msr' and args.startswith(reg.upper()+',') for _,op,args in instructions)==1
    assert 'CFA=WSP+800' in fde and 'W30=[CFA-560]' in fde and fde.count(' FDE cie=')==1
    phoff=struct.unpack_from('<Q',data,32)[0];phsize,count=struct.unpack_from('<HH',data,54);assert phsize==56
    loads=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*56) for i in range(count)]
    loads=[x for x in loads if x[0]==1];assert min(x[3] for x in loads)==0 and all(x[7]==4096 and x[1]&3!=3 for x in loads)
    span=(max(x[3]+x[6] for x in loads)+4095)&~4095;assert span<=65536 and len(data)<=65536
    meta={'TP_NOTIFIER_CODE_RVA':entry,'TP_NOTIFIER_CODE_FILE':code_file,'TP_NOTIFIER_CODE_BYTES':size,
          'TP_NOTIFIER_CALLBACK_RVA':callback,'TP_NOTIFIER_CALLBACK_FILE':callback_file,'TP_NOTIFIER_LOAD_BYTES':span}
    header=['#ifndef TP_NOTIFIER_IMAGE_H','#define TP_NOTIFIER_IMAGE_H','#include <stdint.h>']+[f'#define {k} {v}u' for k,v in meta.items()]
    header+=['static const unsigned char tp_notifier_elf[]={']+['    '+','.join(f'0x{x:02x}' for x in data[i:i+16])+',' for i in range(0,len(data),16)]+['};','#endif']
    (a.output.parent/'notifier_image.h').write_text('\n'.join(header)+'\n');a.output.with_suffix('.txt').write_text(body+'\n'+fde+'\n'+dynamic)
    report=dict(passed=True,metadata=meta,instructions=len(instructions),gprPreserved=31,simdPreserved=32,frameBytes=800,statusRegisters=['NZCV','FPSR','FPCR'],relocations=0,dependencies=[],templateSha256=sha(data),sourceSha256=sha(source.read_bytes()),deviceExecuted=False)
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
