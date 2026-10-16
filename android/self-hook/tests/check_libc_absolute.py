#!/usr/bin/env python3
"""Independent NDK assembler oracle for the fixed BTI leaf tail dispatch."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--ndk',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.unlink(missing_ok=True)
    tools=args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    source=args.output.with_suffix('.S');obj=args.output.with_suffix('.o');binary=args.output.with_suffix('.bin')
    source.write_text('.text\n.p2align 2\nbti c\nldr x16, 1f\nbr x16\n1: .quad 0x0123456789abcdec\n')
    subprocess.run([str(tools/'aarch64-linux-android36-clang'),'-c',str(source),'-o',str(obj)],check=True)
    subprocess.run([str(tools/'llvm-objcopy'),'-O','binary','--only-section=.text',str(obj),str(binary)],check=True)
    body=binary.read_bytes();expected=struct.pack('<IIIQ',0xd503245f,0x58000050,0xd61f0200,0x0123456789abcdec)
    if body!=expected:raise RuntimeError('LIBC_ABSOLUTE_ASSEMBLER_MISMATCH')
    text=subprocess.check_output([str(tools/'llvm-objdump'),'-d',str(obj)],text=True)
    args.output.with_suffix('.txt').write_text(text)
    args.output.write_text(json.dumps({'passed':True,'bytes':body.hex(),'preservedBtiBytes':4,'patchBytes':16,
        'branchRegister':'x16','stackBytes':0,'linkRegisterChanged':False,'deviceExecuted':False,
        'checkerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},indent=2)+'\n')

if __name__=='__main__':main()
