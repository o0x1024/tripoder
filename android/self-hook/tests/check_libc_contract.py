#!/usr/bin/env python3
"""Check the measured libc symbol/body contract and extract complete source pages."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from libc_entry_audit import audit

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / 'android/self-hook/native/libc_fixed_contract.h'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inspect(libc, output):
    output.unlink(missing_ok=True)
    image = libc.read_bytes()
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())['binaries'][
        '/apex/com.android.runtime/lib64/bionic/libc.so']
    if digest(image) != expected or image[:7] != b'\x7fELF\x02\x01\x01' or struct.unpack_from('<H', image, 18)[0] != 183:
        raise RuntimeError('LIBC_PLATFORM_ELF_IDENTITY')
    phoff, shoff = struct.unpack_from('<QQ', image, 32)
    phsize, phcount, shsize, shcount, _ = struct.unpack_from('<HHHHH', image, 54)
    if phsize != 56 or shsize != 64:
        raise RuntimeError('LIBC_ELF_HEADER_LAYOUT')
    programs = [struct.unpack_from('<IIQQQQQQ', image, phoff + i * phsize) for i in range(phcount)]
    sections = [struct.unpack_from('<IIQQQQIIQQ', image, shoff + i * shsize) for i in range(shcount)]
    symbols = {}
    for section in sections:
        if section[1] not in (2, 11):
            continue
        strings = sections[section[6]]
        names = image[strings[4]:strings[4] + strings[5]]
        if section[9] != 24:
            raise RuntimeError('LIBC_SYMBOL_LAYOUT')
        for offset in range(section[4], section[4] + section[5], 24):
            name, info, _, index, value, _ = struct.unpack_from('<IBBHQQ', image, offset)
            if index and info & 15 == 2:
                text = names[name:names.index(0, name)].decode('ascii')
                symbols.setdefault(text, set()).add(value)

    def file_offset(address, size):
        matches = [p[2] + address - p[3] for p in programs if p[0] == 1 and p[1] & 7 == 5
                   and p[3] <= address and address + size <= p[3] + p[5]]
        if len(matches) != 1:
            raise RuntimeError('LIBC_EXECUTABLE_RANGE')
        return matches[0]

    header = CONTRACT.read_text()
    entries = []
    for name, offset, body in re.findall(r'\{"([^"]+)",\s*(0x[0-9a-f]+),\s*\{([^}]+)\}\}', header):
        address = int(offset, 16)
        words = [int(value, 16) for value in body.split(',')]
        if len(words) != 6 or symbols.get(name) != {address}:
            raise RuntimeError('LIBC_EXACT_SYMBOL:' + name)
        location = file_offset(address, 24)
        if image[location:location + 24] != struct.pack('<6I', *words):
            raise RuntimeError('LIBC_COMPLETE_FUNCTION_BODY:' + name)
        entries.append({'name': name, 'rva': address, 'words': words})
    if len(entries) != 10:
        raise RuntimeError('LIBC_ENTRY_COUNT')
    absolute_audit=audit(image,sections,entries)
    raw_id = re.search(r'tp_libc_build_id\[16\]\s*=\s*\{([^}]+)\}', header)[1]
    build_id = bytes(int(value.strip(), 16) for value in raw_id.split(',') if value.strip())
    measured_ids = []
    for p in programs:
        if p[0] != 4:
            continue
        at, end = p[2], p[2] + p[5]
        while at + 12 <= end:
            names, desc, kind = struct.unpack_from('<III', image, at)
            name = image[at + 12:at + 12 + names]
            data_at = at + 12 + ((names + 3) & ~3)
            data = image[data_at:data_at + desc]
            if kind == 3 and name == b'GNU\0':
                measured_ids.append(data)
            at = data_at + ((desc + 3) & ~3)
    if measured_ids != [build_id]:
        raise RuntimeError('LIBC_BUILD_ID')
    pages, source = [], bytearray()
    for address in (0xe0000, 0xe1000):
        offset = file_offset(address, 4096)
        data = image[offset:offset + 4096]
        pages.append({'rva': address, 'fileOffset': offset, 'sha256': digest(data)})
        source.extend(data)
    output.with_name('libc-source-pages.bin').write_bytes(source)
    report = {'passed': True, 'sourceLibcSha256': expected, 'buildId': build_id.hex(),
              'entries': entries, 'pages': pages, 'sourcePagesSha256': digest(source),
              'absoluteEntryAudit':absolute_audit,
              'contractSha256': digest(CONTRACT.read_bytes()), 'checkerSha256': digest(Path(__file__).read_bytes()),
              'scope': 'Exact measured ELF and two shared pages; no arbitrary libc/platform discovery'}
    output.write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--libc', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(inspect(args.libc, args.output)))
