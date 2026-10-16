#!/usr/bin/env python3
"""Verify the exact fixed ART central writer and original invalidation events."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import uuid
from verify_self_interpreter import verify as verify_contracts

ROOT=Path(__file__).resolve().parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def need(v,r):
    if not v:raise RuntimeError(r)
def load(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'android/scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def branch(pc,target):
    d=target-pc;need(d%4==0 and -0x8000000<=d<0x8000000,'BRANCH_RANGE');return 0x14000000|((d//4)&0x03ffffff)
def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True)
    p.add_argument('--expected',choices=['jit','aot'],required=True);a=p.parse_args();d=a.evidence
    b=json.loads((d/'build-receipt.json').read_text());path=d/'device/result.json';r=json.loads(path.read_text())
    need(r['passed'] is True and r['phase']=='H3k-fixed-art-writer' and r['runId']==str(uuid.UUID(a.run_id)) and r['artCompilation']==a.expected,'WRITER_RUN')
    need(r['package']=='dev.tripoder.selfhookprobe' and r['debuggable'] is False and r['uid']>=10000 and r['pid']>0,'OWNED_PROCESS')
    need(r['apkSha256']==b['apkSha256']==sha(Path(b['apk'])) and r['nativeSha256']==b['nativeSha256']
        and r['build']['sourceDigest']==b['sourceDigest'] and r['build']['buildId']==b['buildId'],'BUILD_IDENTITY')
    for name,h in b['sources'].items():need(sha(ROOT/name)==h,'SOURCE_CHANGED:'+name)
    need(hashlib.sha256(json.dumps(b['sources'],sort_keys=True).encode()).hexdigest()==b['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    need(r['platformGatePassed'] is True and all(r['platform'][k]==v for k,v in platform.items()),'PLATFORM');verify_contracts(d,platform)
    maps=(d/'device/maps.txt').read_text();smaps=(d/'device/smaps.txt').read_text()
    need(r['mapsSha256']==sha(d/'device/maps.txt') and r['smapsSha256']==sha(d/'device/smaps.txt')
        and not any(x in maps.lower() for x in ('frida','gadget','libgum')),'MAPS')
    w=r['writer'];s=w['status'];e=w['events'];state=w['active'];retired=w['disabled']
    need(w['requestedCompilation']==a.expected and w['threads']==4 and w['errors']==0 and w['calls']>=1001
        and w['bodyCalls']==w['calls']+1 and w['fullCallerCoverage'] is w['productAccepted'] is False,'WRITER_OWNED_CALLS')
    need(w['policyBefore']['owned'] is w['policyPrepared']['owned'] is w['prepared']['writerInterpositionInstalled'] is False,'NO_PREPARE_PUBLICATION')
    for item in (state,retired):
        need(item['writerInterpositionInstalled'] is item['writerOwnerRegistered'] is item['processInterpreterOwned'] is item['backupRooted'] is True
            and item['completeEntryWriterClosure'] is item['fullCallerCoverage'] is False and item['processPolicyClaims']==1
            and item['observedInstrumentationLevel']==2 and item['originals']==w['calls'],'CENTRAL_WRITER_AND_ROOT_LIFETIME')
    need(state['active'] is True and retired['active'] is False and state['targetQuick']==state['desiredEntry']==state['relay']
        and retired['targetQuick']==retired['desiredEntry']==retired['interpreterBridge'],'TARGET_ENTRIES')
    need(e['passed'] is e['originalWritersExecuted'] is True and e['reinitializeCalls']==e['updateCalls']==64
        and e['globalInvalidations']==4 and e['errors']==0 and e['before']==e['after']==e['desired']==state['relay']
        and e['requested']==state['interpreterBridge'] and e['fullEntryWriterClosure'] is False,'REAL_ART_WRITER_EVENTS')
    need(s['installed'] is s['processLifetimeLease'] is True and s['fullEntryWriterClosure'] is False
        and s['seals']==15 and s['fdeRanges']==1 and s['fdePcs']==23 and s['pageCommits']==1 and s['parkedThreads']>0,'ART_PAGE_AND_FDE_LEASE')
    compiled=[load('writer_compile','verify-self-art-compilation.py').entry(w[k],maps) for k in ('targetBefore','callerBefore')]
    need(all(c['kind']==a.expected for c in compiled),'ACTUAL_PRECOMPILED_CALLER')
    entry,leaf,base,registry=[int(s[k],16) for k in ('entry','leaf','leafBase','registry')]
    artbase=int(w['targetBefore']['base'],16);need(entry==artbase+0x514d8c and int(s['resume'],16)==entry+12,'FIXED_WRITER_ADDRESS')
    original=(d/'device/writer-original.bin').read_bytes();actual=(d/'device/writer-active.bin').read_bytes()
    contract=json.loads((d/'art-entry-writer-contract.json').read_text());page=next(x for x in contract['entries'] if x['name']=='page')
    need(len(original)==len(actual)==4096 and hashlib.sha256(original).hexdigest()==page['sha256'],'WHOLE_ART_PAGE_IDENTITY')
    patch=bytearray(original);struct.pack_into('<I',patch,0xd94,branch(entry+8,leaf));need(bytes(patch)==actual,'ONLY_FIXED_CALLEE_SAVE_REPLACED')
    audit=json.loads((d/'near-leaf-audit.json').read_text());m=audit['metadata'];template=bytearray((d/'libtripoder-entry-leaf-template.so').read_bytes())
    need(audit['passed'] is True and audit['instructions']==23 and audit['constantFdeSegments']==1 and audit['cfaOffset']==32
        and audit['templateSha256']==sha(d/'libtripoder-entry-leaf-template.so') and audit['auditSha256']==sha(d/'near-leaf-audit.txt')
        and audit['sourceSha256']==sha(ROOT/'android/self-hook/native/coverage/entry_leaf_template.S')
        and audit['checkerSha256']==sha(ROOT/'android/self-hook/tests/coverage/check_near_leaf.py') and leaf==base+m['TP_LEAF_CODE_RVA'],'NEAR_ELF_TEMPLATE')
    struct.pack_into('<I',template,m['TP_LEAF_BACK_FILE'],branch(base+m['TP_LEAF_BACK_RVA'],entry+12));struct.pack_into('<Q',template,m['TP_LEAF_REGISTRY_FILE'],registry)
    need(bytes(template)==(d/'device/writer-leaf.so').read_bytes(),'ENTIRE_SEALED_LEAF_IDENTITY')
    mapping=load('writer_maps','verify-self-hook-probe.py').mapping;source=mapping(maps,entry&~4095);target=mapping(maps,leaf&~4095)
    need(source['mode']=='r-xp' and source['inode']==0 and target['mode']=='r-xp' and target['inode']>0
        and 'memfd:tripoder-art-owner-leaf' in target['path'],'ACTUAL_ART_AND_ELF_MAPPINGS')
    def flags(text,address):
        selected=False
        for line in text.splitlines():
            header=re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s',line)
            if header:selected=int(header[1],16)<=address<int(header[2],16)
            elif selected and line.startswith('VmFlags:'):return set(line.split()[1:])
        raise RuntimeError('SMAPS_ADDRESS_MISSING')
    beforeMaps=(d/'device/writer-before-maps.txt').read_text();beforeSmaps=(d/'device/writer-before-smaps.txt').read_text()
    need(w['beforeMapsSha256']==sha(d/'device/writer-before-maps.txt') and w['beforeSmapsSha256']==sha(d/'device/writer-before-smaps.txt'),'BEFORE_PUBLICATION_MAPS')
    saved=int(s['savedOriginalView'],16);originalMap=mapping(beforeMaps,entry&~4095);savedMap=mapping(maps,saved)
    need(originalMap['path']==savedMap['path']=='/apex/com.android.art/lib64/libart.so'
        and originalMap['mode']==savedMap['mode']=='r-xp' and originalMap['inode']==savedMap['inode']>0
        and originalMap['device']==savedMap['device'] and originalMap['offset']==savedMap['offset']==0x514000,'SAVED_FILE_IDENTITY')
    oldFlags,newFlags,savedFlags,elfFlags=[flags(text,address) for text,address in ((beforeSmaps,entry),(smaps,entry),(smaps,saved),(smaps,leaf))]
    need(('bt' in oldFlags)==('bt' in newFlags)==('bt' in savedFlags)==s['sourceBti']
        and 'bt' in elfFlags and not any('mt' in f for f in (oldFlags,newFlags,savedFlags,elfFlags)),'PRESERVED_SOURCE_BTI_AND_GUARDED_ELF')
    if a.expected=='aot':need('actualCompilerFilter=speed' in (d/'aot-compile.txt').read_text() and 'Final Status: PERFORMED' in (d/'aot-compile.txt').read_text(),'AOT_RECEIPT')
    report=dict(passed=True,phase=r['phase'],runId=r['runId'],kind=a.expected,apkSha256=b['apkSha256'],buildId=b['buildId'],sourceDigest=b['sourceDigest'],
        calls=w['calls'],writerEvents=132,errors=0,resultSha256=sha(path),verifierSha256=sha(Path(__file__)),
        scope='Exact central ART writer and original reinitialize/update/cache invalidation; complete direct-store closure and product remain unproven')
    with (d/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
if __name__=='__main__':main()
