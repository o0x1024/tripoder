#!/usr/bin/env python3
"""Compile/audit one immutable ELF template and emit its embedded image."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
from entry_writer_audit import Elf

ROOT=Path(__file__).resolve().parents[4]
def run(args):return subprocess.run(list(map(str,args)),check=True,text=True,capture_output=True).stdout
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.unlink(missing_ok=True);a.output.parent.mkdir(parents=True,exist_ok=True)
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';source=ROOT/'android/self-hook/native/coverage/entry_leaf_template.S'
    library=a.output.parent/'libtripoder-entry-leaf-template.so'
    run([tools/'aarch64-linux-android36-clang','-shared','-nostdlib','-fPIC',source,
         '-Wl,-Bsymbolic,--build-id=none,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--eh-frame-hdr','-o',library])
    data=library.read_bytes();elf=Elf(data);assert len(data)<=65536
    symbols=run([tools/'llvm-nm','-S','--defined-only',library]);found={}
    for line in symbols.splitlines():
        fields=line.split()
        if len(fields)==4:found[fields[3]]=(int(fields[0],16),int(fields[1],16))
    leaf,size=found['tp_art_entry_leaf'];back=found['tp_art_leaf_resume'][0];registry=found['tp_art_leaf_registry'][0]
    assert size==92 and back-leaf==80
    section=elf.sections['.text'];code_file=section[4]+leaf-section[3]
    ro=elf.sections['.rodata'];reg_file=ro[4]+registry-ro[3]
    assert elf.span(reg_file,8)==b'\0'*8 and elf.code(back,4)==struct.pack('<I',0x14000000)
    body=run([tools/'llvm-objdump','-d',library]);unwind=run([tools/'llvm-dwarfdump','--eh-frame',library])
    instructions=[(m[1],m[2]) for m in re.finditer(r'^\s*[0-9a-f]+:\s+[0-9a-f]+\s+(\w+)\s*(.*)$',body,re.M)]
    assert len(instructions)==23 and instructions[0]==('bti','j')
    assert re.search(r'mov\s+w9, #0x1000\b',body)
    assert not re.search(r'\b(?:bl|blr|ret|autiasp|paciasp|msr)\b',body)
    for opcode,args in instructions:
        if opcode in {'adrp','add','mov','ldar','ldr','subs'}:assert args.split(',')[0].strip() in {'x1','w9','x10','x11','x12','x13','x14'}
        else:assert opcode in {'bti','cmp','tst','b','cbz','stp','brk'}
    assert sum(op=='ldar' for op,_ in instructions)==2 and sum(op=='stp' for op,_ in instructions)==1
    assert re.search(r'stp\s+x20, x19, \[sp, #0x10\]',body)
    fdes=[]
    for match in re.finditer(r'^\w+ [^\n]* FDE cie=\w+ pc=([0-9a-f]+)\.\.\.([0-9a-f]+)\n(.*?)(?=^\w+ [^\n]* (?:CIE|FDE)|\Z)',unwind,re.M|re.S):
        begin,end=int(match[1],16),int(match[2],16)
        fdes.append((begin-leaf,end-leaf,match[3]))
    assert sorted((s,e) for s,e,_ in fdes)==[(0,92)]
    for offset in range(0,92,4):
        selected=[s for begin,end,s in fdes if begin<=offset<end];assert len(selected)==1
        state=selected[0];assert 'DW_CFA_advance' not in state and 'reg34=1' in state
        assert 'CFA=WSP+32:' in state and 'W29=[CFA-32]' in state and 'W30=[CFA-24]' in state
        assert 'W19=same' in state and 'W20=same' in state
    dynamic=run([tools/'llvm-readelf','-d','-r','-l','-n',library])
    assert '(NEEDED)' not in dynamic and '(TEXTREL)' not in dynamic and 'R_AARCH64_' not in dynamic
    assert not any(tag in dynamic for tag in ('(INIT)','(INIT_ARRAY)','(FINI)','(FINI_ARRAY)'))
    assert 'BTI, PAC' in dynamic and 'GNU_EH_FRAME' in dynamic and '.eh_frame_hdr' in elf.sections
    phoff=struct.unpack_from('<Q',data,32)[0];phsize,phcount=struct.unpack_from('<HH',data,54);assert phsize==56
    programs=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*phsize) for i in range(phcount)]
    loads=[entry for entry in programs if entry[0]==1];assert loads and min(entry[3] for entry in loads)==0
    assert all(entry[7]==4096 and entry[1]&3!=3 for entry in loads)
    span=(max(entry[3]+entry[6] for entry in loads)+4095)&~4095;assert span<=65536
    metadata={'TP_LEAF_CODE_RVA':leaf,'TP_LEAF_CODE_FILE':code_file,'TP_LEAF_CODE_BYTES':size,'TP_LEAF_POST_OFFSET':80,
              'TP_LEAF_BACK_RVA':back,'TP_LEAF_BACK_FILE':code_file+80,'TP_LEAF_REGISTRY_RVA':registry,
              'TP_LEAF_REGISTRY_FILE':reg_file,'TP_LEAF_LOAD_BYTES':span}
    header=['#ifndef TP_ENTRY_LEAF_IMAGE_H','#define TP_ENTRY_LEAF_IMAGE_H','#include <stdint.h>']
    header += [f'#define {k} {v}u' for k,v in metadata.items()]
    header += [f'#define TP_LEAF_TEMPLATE_SHA256 "{sha(data)}"','static const unsigned char tp_leaf_elf[]={']
    header += ['    '+','.join(f'0x{x:02x}' for x in data[i:i+16])+',' for i in range(0,len(data),16)]
    header += ['};','#endif'];(a.output.parent/'entry_leaf_image.h').write_text('\n'.join(header)+'\n')
    audit=a.output.with_suffix('.txt');audit.write_text(body+'\n'+unwind+'\n'+dynamic)
    report=dict(passed=True,registrySlots=4096,instructions=23,constantFdeSegments=1,cfaOffset=32,coveredInstructionPcs=23,relocations=0,
        dependencies=[],lifecycleCode=False,metadata=metadata,sourceSha256=sha(source.read_bytes()),templateSha256=sha(data),
        imageBytes=len(data),auditSha256=sha(audit.read_bytes()),checkerSha256=sha(Path(__file__).read_bytes()),deviceExecuted=False)
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
