#!/usr/bin/env python3
"""Independently bind every fixed ART body to the measured whole ELF."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / 'android/self-hook/native/art_framework_contract.h'
ROOT_CONTRACT = ROOT / 'android/self-hook/native/art_storage_contract.h'


def inspect(path, output):
    output.unlink(missing_ok=True)
    image = path.read_bytes()
    sha = lambda data: hashlib.sha256(data).hexdigest()
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())['binaries'][
        '/apex/com.android.art/lib64/libart.so']
    if sha(image) != expected or image[:7] != b'\x7fELF\x02\x01\x01' or struct.unpack_from('<H', image, 18)[0] != 183:
        raise RuntimeError('FRAMEWORK_ART_ELF_IDENTITY')
    phoff = struct.unpack_from('<Q', image, 32)[0]
    phsize, phcount = struct.unpack_from('<HH', image, 54)
    if phsize != 56:
        raise RuntimeError('FRAMEWORK_ART_ELF_LAYOUT')
    programs = [struct.unpack_from('<IIQQQQQQ', image, phoff + i * phsize) for i in range(phcount)]
    entries = []
    for header, prefix, selected in [(CONTRACT, 'FRAMEWORK', None), (ROOT_CONTRACT, 'STORAGE', {'READ_ROOT', 'MARK_ROOT'})]:
        text = header.read_text()
        for name, address in re.findall(r'#define TP_' + prefix + r'_([A-Z_]+)_RVA UINT64_C\((0x[0-9a-f]+)\)', text):
            if name == 'RUNTIME' or selected is not None and name not in selected:
                continue
            body = re.search(r'tp_' + prefix.lower() + '_' + name.lower() + r'_bytes\[\]\s*=\s*\{([^}]+)\}', text)[1]
            data = bytes(int(value.strip(), 16) for value in body.split(',') if value.strip())
            rva = int(address, 16)
            offsets = [p[2] + rva - p[3] for p in programs if p[0] == 1 and p[1] & 7 == 5
                       and p[3] <= rva and rva + len(data) <= p[3] + p[5]]
            if len(offsets) != 1 or image[offsets[0]:offsets[0] + len(data)] != data:
                raise RuntimeError('FRAMEWORK_ART_COMPLETE_BODY:' + name)
            entries.append({'name': name, 'rva': rva, 'size': len(data), 'sha256': sha(data)})
    if len(entries) != 10 or not {'READ_ROOT', 'MARK_ROOT'} <= {e['name'] for e in entries}:
        raise RuntimeError('FRAMEWORK_ART_BODY_COUNT')
    report = {'passed': True, 'artSha256': expected, 'entries': entries,
              'contractSha256': sha(CONTRACT.read_bytes()), 'checkerSha256': sha(Path(__file__).read_bytes()),
              'rootBarrierContractSha256': sha(ROOT_CONTRACT.read_bytes()),
              'scope': 'Fixed metadata, declaring-class GC root barriers and JNI IDs; no platform discovery'}
    output.write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--art', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(inspect(args.art, args.output)))
