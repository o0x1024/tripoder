"""Independent fixed-ART interpreter contract receipt acceptance."""
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def verify(directory,platform):
    report=json.loads((directory/'art-interpreter-contract.json').read_text())
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    entries={e['name']:(e['rva'],e['size']) for e in report['entries']}
    if not(report['passed'] is True and report['artSha256']==platform['binaries']['/apex/com.android.art/lib64/libart.so']
        and entries=={'LEVEL':(0x8df858,12),'DEOPT_ALL':(0x8e09e4,16),'CONFIGURE':(0x8df864,160)}
        and report['contractSha256']==sha(ROOT/'android/self-hook/native/art_interpreter_contract.h')
        and report['checkerSha256']==sha(ROOT/'android/self-hook/tests/check_art_interpreter_contract.py')):
        raise RuntimeError('FIXED_INTERPRETER_POLICY_CONTRACT')
    writer=json.loads((directory/'art-entry-writer-contract.json').read_text())
    if not(writer['passed'] is True and writer['artSha256']==report['artSha256'] and writer['middleDirectBranches']==[]
        and {e['name']:(e['rva'],e['size']) for e in writer['entries']}=={
            'page':(0x514000,4096),'entry':(0x514d8c,168),'reinitialize':(0x557240,564),
            'update':(0x3f55ec,156),'cache_invalidate_all':(0x8f6874,720)}
        and writer['contractSha256']==sha(ROOT/'android/self-hook/native/coverage/art_entry_writer_contract.h')
        and writer['checkerSha256']==sha(ROOT/'android/self-hook/tests/coverage/check_art_entry_writer_contract.py')):
        raise RuntimeError('FIXED_ENTRY_WRITER_CONTRACT')
    return report
