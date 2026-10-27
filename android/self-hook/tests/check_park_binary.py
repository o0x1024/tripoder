#!/usr/bin/env python3
"""Reject unexpected calls in the fixed AArch64 pause critical path of the built ELF."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def erratum_return(disassembly, target, resume):
    """A linker-moved memory instruction may return only to its original successor."""
    if not re.fullmatch(r'__CortexA53843419_[0-9a-f]+', target):
        return False
    body = re.search(r'^[0-9a-f]+ <' + re.escape(target) + r'>:\n(.*?)(?=^[0-9a-f]+ <|\Z)', disassembly, re.M | re.S)
    if not body:
        return False
    instructions = re.findall(r'^\s*[0-9a-f]+:\s+(\S+)\s*(.*)$', body[1], re.M)
    if len(instructions) != 2 or instructions[0][0] not in ('ldr', 'ldur', 'str', 'stur'):
        return False
    returned = re.match(r'0x([0-9a-f]+)\s+<', instructions[1][1])
    return instructions[1][0] == 'b' and returned is not None and int(returned[1], 16) == resume


def audit(library, objdump, output, product=False):
    output.unlink(missing_ok=True)
    disassembly = subprocess.check_output([str(objdump), '-d', '--no-show-raw-insn', str(library)], text=True)
    allowed = {
        'park_handler': set(), 'release_all': {'tp_park_now'}, 'tp_park_now': set(),
        'tp_park_begin': {'sigaction@plt', 'tp_park_now', 'release_all', 'tp_park_blocked_mask'},
        'tp_park_blocked_mask': set(),
        'tp_park_end': {'release_all'}, 'tp_park_context': set(),
        'one_cycle': {'tp_park_begin', 'tp_park_end', 'tp_park_context', 'tp_park_now', 'workers_progress'},
        'workers_progress': {'tp_park_now'},
        'tp_code_page_commit': {'cache_sync', 'tp_code_page_validate'},
        'tp_code_page_validate': set(),
        'cache_sync': set(),
        'commit_and_resume': {'tp_code_page_commit', 'tp_park_end'},
        'fixed_commit_batch': {'tp_code_page_validate', 'tp_code_page_commit', 'tp_park_end', 'tp_park_context'},
        'tp_entry_branch_commit_parked': {'tp_code_page_commit', 'tp_park_end'},
        'tp_linker_notify': {'tp_linker_scan_consistent','tp_linker_fail_stop'},
        'tp_linker_scan_consistent': {'tp_module_snapshot','tp_module_reconcile','tp_linker_fail_stop','tp_linker_record','tp_signal_chain_module_added'},
        'tp_linker_record':{'tp_linker_fail_stop'},
        'tp_signal_chain_module_added':{'signal_slot'},'signal_slot':set(),
        'tp_linker_install_parked': {'tp_linker_scan_consistent','tp_code_page_commit','tp_park_end','tp_linker_fail_stop'},
        'tp_linker_fail_stop':set(),
        'tp_module_snapshot':set(),'tp_module_reconcile':set(),
        'tp_module_acquire':set(),'tp_module_release':set(),
        'tp_native_commit_parked':{'tp_code_page_validate','tp_code_page_commit','tp_native_stop','tp_park_end','tp_linker_publication_ready','tp_park_context','tp_scalar_inbound_pending_pc'},
        'tp_scalar_inbound_pending_pc':set(),
        'tp_native_stop':set(),
        'tp_linker_snapshot':{'tp_module_snapshot'},
        'tp_linker_publication_ready':{'tp_linker_snapshot','tp_module_snapshot'},
    }
    if product:
        # These three routines live only in probe_noncooperative.c / probe_thread_park.c.
        # Product critical sections below remain mandatory and unchanged.
        del allowed['one_cycle']
        del allowed['workers_progress']
        del allowed['commit_and_resume']
    name, seen, calls, lines, syscall_seen = '', set(), [], [], False
    for line in disassembly.splitlines():
        label = re.match(r'[0-9a-f]+ <([^>]+)>:', line)
        if label:
            name = label[1]
            syscall_seen = False
        if name not in allowed:
            continue
        seen.add(name)
        lines.append(line)
        instruction = re.match(r'\s*[0-9a-f]+:\s+(\S+)\s*(.*)', line)
        if not instruction:
            continue
        opcode, operands = instruction.groups()
        if opcode == 'svc':
            syscall_seen = True
        if opcode in ('blr', 'br', 'blraa', 'blrab', 'braa', 'brab'):
            raise RuntimeError('INDIRECT_CRITICAL_PATH_BRANCH:' + line)
        if opcode not in ('bl', 'b'):
            continue
        target = re.search(r'<([^>+]+)(?:\+[^>]*)?>', operands)
        if not target:
            raise RuntimeError('UNRESOLVED_CRITICAL_PATH_BRANCH:' + line)
        target = target[1]
        if opcode == 'b' and target == name:
            continue
        address = int(re.match(r'\s*([0-9a-f]+):', line)[1], 16)
        if opcode == 'b' and erratum_return(disassembly, target, address + 4):
            calls.append({'function': name, 'target': target, 'instruction': line.strip(), 'linkerMemoryInstruction': True})
            continue
        if target not in allowed[name]:
            raise RuntimeError('UNEXPECTED_CRITICAL_PATH_CALL:' + name + ':' + line)
        if target == 'sigaction@plt' and syscall_seen:
            raise RuntimeError('LIBC_CALL_AFTER_PAUSE_ENTRY:' + line)
        calls.append({'function': name, 'target': target, 'instruction': line.strip()})
    if seen != set(allowed):
        raise RuntimeError('CRITICAL_PATH_FUNCTION_MISSING:' + repr(set(allowed) - seen))
    text = '\n'.join(lines) + '\n'
    output.with_suffix('.txt').write_text(text)
    report = {'passed': True, 'nativeSha256': hashlib.sha256(library.read_bytes()).hexdigest(),
              'functions': sorted(seen), 'calls': calls,
              'disassemblySha256': hashlib.sha256(text.encode()).hexdigest(),
              'scope': 'Binary call allowlist only; source review still owns control flow and signal lifetime proof'}
    output.write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--objdump', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--product', action='store_true', help='Audit the product library without probe worker routines')
    args = parser.parse_args()
    print(json.dumps(audit(args.library, args.objdump, args.output, args.product)))
