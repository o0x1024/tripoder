#!/usr/bin/env python3
"""Independent NDK assembler words plus sanitized Native prefix/FDE planning."""
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
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.unlink(missing_ok=True);native=ROOT/'android/self-hook/native';tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    asm=a.output.parent/'native-prefix-oracle.S';obj=asm.with_suffix('.o')
    instructions=['stp x29,x30,[sp,#-32]!','stp x19,x20,[sp,#-32]!','stp x29,x30,[sp,#-64]!','stp x29,x30,[sp,#-512]!',
        'str d8,[sp,#-32]!','str d15,[sp,#-256]!','sub sp,sp,#112','sub sp,sp,#4080','paciasp','nop','add x0,x0,#1',
        'stp x29,x30,[sp,#16]!','stp x29,x30,[sp,#-24]!','str d8,[sp,#-8]!','stp x29,x30,[sp],#-32','pacibsp','add sp,sp,#16','sub sp,x0,#32','sub sp,sp,#0','str x30,[sp,#-32]!',
        'mov w0,#42','mov x0,#-1','movk x0,#0x1234,lsl #16','mov x0,x1','add w0,w0,w1','sub x0,x0,x1','eor x0,x0,x1',
        'fmov d0,#0.5','fadd d0,d0,d1','fmov x0,d0','ldr x0,[x1,#16]','str q0,[x1,#16]','movi v0.16b,#1','ret','pacm']
    asm.write_text('.text\n'+''.join('    '+x+'\n' for x in instructions))
    run([tools/'aarch64-linux-android36-clang','-c','-march=armv9.5-a',asm,'-o',obj]);code=Elf(obj.read_bytes()).section('.text')
    expected=[0xa9be7bfd,0xa9be53f3,0xa9bc7bfd,0xa9a07bfd,0xfc1e0fe8,0xfc100fef,0xd101c3ff,0xd13fc3ff,0xd503233f,0xd503201f,0x91000400,
        0xa9817bfd,0xa9befbfd,0xfc1f8fe8,0xa8be7bfd,0xd503237f,0x910043ff,0xd100801f,0xd10003ff,0xf81e0ffe,
        0x52800540,0x92800000,0xf2a24680,0xaa0103e0,0x0b010000,0xcb010000,0xca010000,0x1e6c1000,0x1e612800,0x9e660000,0xf9400820,0x3d800420,0x4f00e420,0xd65f03c0,0xd50324ff]
    assert list(struct.unpack('<35I',code))==expected
    host=a.output.parent/'native-prefix-host'
    run(['clang','-std=c11','-O2','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-I'+str(ROOT/'android/self-hook/include'),
        native/'a64_entry.c',native/'a64_relocate.c',native/'abi/native_prefix.c',Path(__file__).with_name('native_prefix_test.c'),'-o',host])
    result=json.loads(run([host]));assert result['passed'] is True and result['checks']==134
    result.update(assemblerCases=35,oracleSha256=sha(obj),sourceSha256=sha(native/'abi/native_prefix.c'),checkerSha256=sha(Path(__file__)),deviceExecuted=False)
    a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
