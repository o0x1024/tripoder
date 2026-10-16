#!/usr/bin/env python3
"""Independent acceptance of an owned PAC entry routed through a sealed ELF."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct

ROOT=Path(__file__).resolve().parents[2]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(ok,reason):
    if not ok:raise RuntimeError(reason)
def branch(pc,target):
    delta=target-pc;require(delta%4==0 and -0x8000000<=delta<0x8000000,'BRANCH_RANGE');return 0x14000000|((delta//4)&0x03ffffff)
def verify(d,e,maps,smaps,art_installed):
    audit=json.loads((d/'near-leaf-audit.json').read_text());m=audit['metadata']
    require(audit['passed'] is True and audit['instructions']==audit['coveredInstructionPcs']==23
        and audit['constantFdeSegments']==1 and audit['cfaOffset']==32 and audit['relocations']==0 and audit['dependencies']==[] and audit['lifecycleCode'] is False
        and audit['auditSha256']==sha(d/'near-leaf-audit.txt') and audit['templateSha256']==sha(d/'libtripoder-entry-leaf-template.so')
        and audit['sourceSha256']==sha(ROOT/'android/self-hook/native/coverage/entry_leaf_template.S')
        and audit['checkerSha256']==sha(ROOT/'android/self-hook/tests/coverage/check_near_leaf.py'),'ELF_TEMPLATE_AUDIT')
    entry=int(e['entryAddress'],16);base=int(e['leafBase'],16);leaf=int(e['leafAddress'],16)
    require(e['passed'] is e['dynamicCodePublished'] is e['plainBaseline'] is e['filePageRestored'] is True
        and e['artInterpositionInstalled'] is art_installed and e['pageCommits']==2 and e['parkedThreads']>0
        and e['sealedElf']==15 and e['registeredFdeRanges']==1 and e['registeredFdePcs']==23 and e['cfaOffset']==32 and e['postOffset']==80
        and entry%4096==0 and leaf==base+m['TP_LEAF_CODE_RVA'] and int(e['resumeAddress'],16)==entry+12,'REAL_PUBLICATION_LIFETIME')
    before,active,restored=[(d/'device'/name).read_bytes() for name in ('entry-before.bin','entry-active.bin','entry-restored.bin')]
    require(len(before)==len(active)==len(restored)==4096 and before==restored
        and struct.unpack_from('<3I',before)==(0xd503233f,0xa9be7bfd,0xa9014ff4),'ORIGINAL_PAC_AND_FRAME')
    expected=bytearray(before);struct.pack_into('<I',expected,8,branch(entry+8,leaf));require(bytes(expected)==active,'EXACT_ONE_WORD_PUBLICATION')
    template=bytearray((d/'libtripoder-entry-leaf-template.so').read_bytes())
    struct.pack_into('<I',template,m['TP_LEAF_BACK_FILE'],branch(base+m['TP_LEAF_BACK_RVA'],entry+12))
    struct.pack_into('<Q',template,m['TP_LEAF_REGISTRY_FILE'],int(e['registryAddress'],16))
    require(bytes(template)==(d/'device/published-leaf.so').read_bytes(),'ENTIRE_SEALED_ELF_IDENTITY')
    spec=importlib.util.spec_from_file_location('entry_maps',ROOT/'android/scripts/verify-self-hook-probe.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    source=module.mapping(maps,entry);target=module.mapping(maps,leaf&~4095)
    require(source['mode']=='r-xp' and source['inode']>0 and 'libtripoderselfhook.so' in source['path']
        and target['mode']=='r-xp' and target['inode']>0 and 'memfd:tripoder-art-owner-leaf' in target['path'],'ACTUAL_FILE_AND_ELF_MAPPINGS')
    selected=guarded=False
    for line in smaps.splitlines():
        header=re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s',line)
        if header:selected=int(header[1],16)<=leaf<int(header[2],16)
        elif selected and line.startswith('VmFlags:'):guarded='bt' in line.split() and 'mt' not in line.split()
    require(guarded,'LOADED_ELF_BTI')
    require(e['calls']==400000 and e['threads']==4 and e['stateWrites']==100000 and e['errors']==0
        and e['activeSeen']>0 and e['disabledSeen']>0 and e['activeSeen']+e['disabledSeen']==200000
        and e['originalBookkeepingPreserved'] is e['originalSignedLrPreserved'] is True,'OWNED_CONCURRENT_ROUTING')
    require(e['asyncUnwindVerified'] is e['handlerRestored'] is True and e['asyncSamples']>=512
        and e['asyncFailures']==0 and e['asyncStateMask']==2 and e['controlledLeafOnly'] is True,'REAL_ASYNC_UNWIND')
    trace=(d/'device/entry-unwind.txt').read_text();seen=states=pcs=0
    for block in re.split(r'(?=^sample-state )',trace,flags=re.M)[1:]:
        pc=int(re.search(r'interrupted_pc=([0-9a-f]+)',block)[1],16);offset=pc-leaf
        frames=[int(x,16) for x in re.findall(r'^sample frame=\d+ pc=([0-9a-f]+)',block,re.M)]
        require(0<=offset<84 and offset%4==0 and pc in frames and frames[-1]==int(e['callerAddress'],16),'EXACT_DYNAMIC_ELF_UNWIND_RETURN')
        seen+=1;pcs|=1<<(offset//4);states=2
    require(seen==e['asyncSamples'] and states==2 and pcs==e['asyncLeafMask'],'SIGNAL_TRACE_ACCOUNTING')
    return dict(samples=seen,stateMask=states,pageCommits=e['pageCommits'])
