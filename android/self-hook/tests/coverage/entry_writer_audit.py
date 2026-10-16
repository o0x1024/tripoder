#!/usr/bin/env python3
"""Pinned ELF writer-path evidence, never a claim of complete indirect-call closure."""
import argparse
import bisect
import hashlib
import json
import lzma
from pathlib import Path
import re
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[4]
FUNCTIONS = {
    'UpdateEntryPoints': (0x514d8c, 0xa8),
    'UpdateMethodsCodeImpl': (0x3f55ec, 0x9c),
    'ReinitializeMethodsCode': (0x557240, 0x234),
    'InvalidateAllCompiledCode': (0x8f6874, 0x2d0),
    'InvalidateCompiledCodeFor': (0x5570f8, 0x148),
    'Deoptimize': (0x8e0180, 0x44c),
    'Undeoptimize': (0x8e05cc, 0x2ac),
}
PAC = {0xd503233f: 'PACIASP', 0xd503237f: 'PACIBSP'}
DIRECT_WRITERS = {
    'SetEntryPointsForObsoleteMethod': (0x862ed8, 0x1c, 0x862eec,
        'STR x8,[x1,#0x18]; direct ArtMethod quick write outside UpdateEntryPoints'),
    'AddImageSpaceMethodVisitor': (0x60d158, 0x90, 0x60d1c8,
        'STR x10,[x9,w8,uxtw]; computed pointer-size ArtMethod quick write during image admission'),
}


def need(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def signed(value, width):
    return value - (1 << width) if value & (1 << (width - 1)) else value


def branch(word, pc):
    if word & 0x7c000000 != 0x14000000:
        return None
    return ('BL' if word & 0x80000000 else 'B', pc + signed(word & 0x3ffffff, 26) * 4)


class Elf:
    def __init__(self, data):
        self.data = data
        need(data[:7] == b'\x7fELF\x02\x01\x01' and struct.unpack_from('<H', data, 18)[0] == 183, 'A64_ELF_REQUIRED')
        offset = struct.unpack_from('<Q', data, 40)[0]
        size, count, names = struct.unpack_from('<HHH', data, 58)
        need(size == 64 and 0 < count < 65535 and names < count, 'ELF_SECTION_HEADER')
        entries = [struct.unpack_from('<IIQQQQIIQQ', data, offset + i * size) for i in range(count)]
        strings = self.span(entries[names][4], entries[names][5])
        self.sections = {}
        self.entries = entries
        for entry in entries:
            end = strings.find(b'\0', entry[0])
            need(end >= entry[0], 'ELF_SECTION_NAME')
            name = strings[entry[0]:end].decode('ascii')
            self.sections[name] = entry

    def span(self, start, size):
        need(start >= 0 and size >= 0 and start + size <= len(self.data), 'ELF_BOUNDS')
        return self.data[start:start + size]

    def section(self, name):
        item = self.sections[name]
        return self.span(item[4], item[5])

    def code(self, rva, size):
        text = self.sections['.text']
        need(text[3] <= rva and rva + size <= text[3] + text[5], 'FUNCTION_OUTSIDE_TEXT')
        return self.span(text[4] + rva - text[3], size)

    def plt_targets(self):
        dynsym = self.sections['.dynsym']
        strings_entry = self.entries[dynsym[6]]
        strings = self.span(strings_entry[4], strings_entry[5])
        symbols = self.section('.dynsym')
        relocs = self.section('.rela.plt')
        need(dynsym[9] == 24 and len(relocs) % 24 == 0, 'PLT_RELOCATION_LAYOUT')
        got = {}
        for at in range(0, len(relocs), 24):
            offset, info, addend = struct.unpack_from('<QQq', relocs, at)
            if info & 0xffffffff != 1026:  # R_AARCH64_JUMP_SLOT
                continue
            sym = struct.unpack_from('<IBBHQQ', symbols, (info >> 32) * 24)
            end = strings.find(b'\0', sym[0])
            need(end >= sym[0] and addend == 0, 'PLT_SYMBOL')
            got[offset] = (sym[4], strings[sym[0]:end].decode('ascii'))
        result = {}
        plt = self.sections['.plt']
        code = self.section('.plt')
        for at in range(0, len(code) - 15, 16):
            pc = plt[3] + at
            adrp, ldr, add, br = struct.unpack_from('<4I', code, at)
            if adrp & 0x9f00001f != 0x90000010 or ldr & 0xffc003ff != 0xf9400211:
                continue
            need(add & 0xffc003ff == 0x91000210 and br == 0xd61f0220, 'PLT_STUB_NOT_FIXED_FORMAT')
            page = (pc & ~4095) + signed(((adrp >> 29) & 3) | (((adrp >> 5) & 0x7ffff) << 2), 21) * 4096
            slot = page + ((ldr >> 10) & 4095) * 8
            need(((add >> 10) & 4095) == ((ldr >> 10) & 4095) * 8, 'PLT_ADD_LDR_DISAGREE')
            need(slot in got, 'PLT_GOT_RELOCATION_MISSING')
            result[pc] = got[slot]
        return result


def symbols(nm, art, debug):
    result = []
    for flags, path in (('-DSC', art), ('-SC', debug)):
        output = subprocess.run([str(nm), flags, str(path)], capture_output=True, text=True, check=True).stdout
        for line in output.splitlines():
            match = re.fullmatch(r'([0-9a-f]+) ([0-9a-f]+) [TtWw] (.+)', line)
            if match:
                begin, size = int(match[1], 16), int(match[2], 16)
                if size:
                    result.append((begin, begin + size, match[3]))
    return sorted(set(result))


def audit(art, debug, nm):
    image = art.read_bytes()
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())['binaries']['/apex/com.android.art/lib64/libart.so']
    need(sha(image) == expected, 'PINNED_ART_SHA_MISMATCH')
    elf = Elf(image)
    need(lzma.decompress(elf.section('.gnu_debugdata')) == debug.read_bytes(), 'MINI_DEBUG_NOT_FROM_PINNED_ART')
    symbol_list = symbols(nm, art, debug)
    starts = [item[0] for item in symbol_list]
    def owner(pc):
        index = bisect.bisect_right(starts, pc) - 1
        candidates = []
        while index >= 0 and pc - symbol_list[index][0] < 0x40000:
            begin, end, name = symbol_list[index]
            if begin <= pc < end:
                candidates.append((end - begin, name, begin))
            index -= 1
        if not candidates:
            return {'symbol': None, 'attribution': 'unresolved'}
        _, name, begin = min(candidates)
        return {'symbol': name, 'functionRva': hex(begin), 'offset': hex(pc - begin)}
    plt = elf.plt_targets()
    targets = {rva: name for name, (rva, _) in FUNCTIONS.items()}
    sites = []
    text = elf.section('.text')
    start = elf.sections['.text'][3]
    for offset in range(0, len(text) - 3, 4):
        pc = start + offset
        decoded = branch(struct.unpack_from('<I', text, offset)[0], pc)
        if decoded is None:
            continue
        kind, destination = decoded
        resolved = plt.get(destination, (destination, None))[0]
        if resolved not in targets:
            continue
        sites.append({'rva': hex(pc), 'instruction': kind, 'destination': hex(destination),
                      'viaPlt': destination in plt, 'target': targets[resolved], **owner(pc)})
    functions = []
    for name, (rva, size) in FUNCTIONS.items():
        body = elf.code(rva, size)
        first = struct.unpack_from('<I', body)[0]
        functions.append({'name': name, 'rva': hex(rva), 'size': size, 'sha256': sha(body),
                          'firstWord': hex(first), 'pacPrologue': PAC.get(first),
                          'entryPatchAdmission': 'reject_pac' if first in PAC else 'unproven',
                          'indirectCallsOrBranches': [hex(rva + offset) for offset in range(0, len(body), 4)
                              if struct.unpack_from('<I', body, offset)[0] & 0xfffffc1f in (0xd63f0000, 0xd61f0000)]})
    direct_writers = []
    for name, (rva, size, write, effect) in DIRECT_WRITERS.items():
        direct_writers.append({'name': name, 'rva': hex(rva), 'size': size,
            'sha256': sha(elf.code(rva, size)), 'writeRva': hex(write),
            'writeWord': hex(struct.unpack('<I', elf.code(write, 4))[0]), 'effect': effect,
            'entryPatchAdmission': 'unproven_not_requested'})
    return {'artSha256': expected, 'functions': functions, 'directBranchCandidates': sites,
            'directWriterCounterexamples': direct_writers,
            'directBranchCandidateCount': len(sites), 'pltResolvedEntries': len(plt),
            'completeEntryWriterClosure': False, 'installedInterposition': False,
            'unproven': ['indirect callers', 'inlined/direct ArtMethod stores', 'future generated code',
                         'writer lock context and GC lifetime', 'PAC-safe entry relocation'],
            'scope': 'Linear decoding of pinned .text B/BL candidates plus verified PLT relocations; data words may be candidates; not a full control-flow proof'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--art', type=Path, required=True)
    parser.add_argument('--debug', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    nm = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm'
    report = audit(args.art, args.debug, nm)
    report['auditorSha256'] = sha(Path(__file__).read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'directBranchCandidateCount': report['directBranchCandidateCount'],
                      'entryPatchAdmissions': {f['name']: f['entryPatchAdmission'] for f in report['functions']},
                      'completeEntryWriterClosure': False, 'report': str(args.output)}))


if __name__ == '__main__':
    main()
