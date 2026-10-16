#!/usr/bin/env python3
"""Independent LLVM assembler oracle for the owned managed-to-JNI relay."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    source = args.output.with_suffix('.S')
    source.write_text('.text\n.arch armv8.5-a\nbti c\nldr x0, helper\nldr x16, bridge\nbr x16\nnop\nnop\n'
                      'helper: .quad 0x0102030405060708\nbridge: .quad 0x1112131415161718\n')
    tools = args.ndk.resolve() / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    obj, binary = source.with_suffix('.o'), source.with_suffix('.bin')
    subprocess.run([str(tools / 'aarch64-linux-android36-clang'), '-c', str(source), '-o', str(obj)], check=True)
    subprocess.run([str(tools / 'llvm-objcopy'), '--dump-section', '.text=' + str(binary), str(obj)], check=True)
    words = [0xd503245f, 0x580000a0, 0x580000d0, 0xd61f0200, 0xd503201f, 0xd503201f]
    expected = struct.pack('<6I2Q', *words, 0x0102030405060708, 0x1112131415161718)
    if binary.read_bytes() != expected:
        raise RuntimeError('OWNED_INT_RELAY_ASSEMBLER_MISMATCH')
    report = {'passed': True, 'codeWords': words, 'helperOffset': 24, 'bridgeOffset': 32,
              'oracleSha256': hashlib.sha256(expected).hexdigest(),
              'checkerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
