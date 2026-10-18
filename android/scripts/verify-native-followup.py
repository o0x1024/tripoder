#!/usr/bin/env python3
"""Independently check current-build managed pressure and affinity observations."""
import argparse,json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run,case):
    phase='managed-admission-gc' if case=='managed-admission' else 'native-core-benchmark'
    result,build,report=read(directory,run,phase)
    if case=='managed-admission':
        data=result['admission'];rounds=data['rounds']
        need(data['passed'] is True and data['checks']==739 and data['prefilledEntries']==2304,'MANAGED_COUNTS')
        need(len(rounds)==32 and data['transitions']==640 and data['gcRequests']>0,'MANAGED_PRESSURE')
        for index,item in enumerate(rounds):
            need(item['round']==index and item['mode']==index%3,'MANAGED_ROUND_IDENTITY')
            before=item['prepared'];after=item['retired']
            need(before['stage']=='PREPARED' and before['transitions']==0 and before['originals']==0,'MANAGED_PREPARED')
            need(after['stage']=='DISABLED' and after['transitions']==16 and after['originals']==8,'MANAGED_RETIRED')
            for state in [before,after]:
                need(state['ready'] and not state['active'] and state['backupRooted'] and state['shorty']=='ZL','MANAGED_ROOTS')
                need(state['observedInstrumentationLevel']==2 and state['processPolicyClaims']==1 and state['processInterpreterOwned'],'MANAGED_PROCESS_POLICY')
                need(state['targetQuick']==state['desiredEntry'] and state['targetFlags']&0x100,'MANAGED_NATIVE_ENTRY')
            need(after['writerOwnerRegistered'] is True,'MANAGED_WRITER_OWNER')
        progress=json.loads((directory/'device/progress.json').read_text())
        need(progress['stage']=='COMPLETE' and progress['round']==33 and progress['detail']==739,'MANAGED_COMPLETION')
        named=data['named'];after=named['retired'];before=named['prepared']
        need(named['warmCalls']==10000 and before['transitions']==0 and before['backupRooted'] and before['targetQuick']==before['desiredEntry'],'NAMED_PREIMAGE')
        need(after['transitions']==128 and after['originals']==64 and after['backupRooted'] and not after['active'] and after['targetQuick']==after['desiredEntry'],'NAMED_RETIREMENT')
        rootsPath=directory/'device/storage-roots.json';roots=json.loads(rootsPath.read_text())
        need(data['actualGcCount']>0 and sha(rootsPath)==data['storageRootsSha256'],'ACTUAL_GC_IDENTITY')
        need(roots['published']==roots['rootedOutsideDeclaredTables']==2337 and roots['observedContainerRootMoves']>0,'ACTUAL_MOVING_ROOTS')
        need(len(roots['rootSnapshots'])==2337 and all(x['rooted'] and x['ownerAfter']==x['targetOwner']==x['backupOwner'] for x in roots['rootSnapshots']),'ALL_ROOT_SNAPSHOTS')
        nativeRoots=[x for x in roots['rootSnapshots'] if x['slot']>=2304]
        nativeMoved=sum(x['ownerBefore']!=x['ownerAfter'] or x['containersBefore']!=x['containersAfter'] for x in nativeRoots)
        need(len(nativeRoots)==33 and nativeMoved>0,'NATIVE_ROOT_MOVEMENT')
        report.update(checks=739,transitions=640,prefilledEntries=2304,gcRequests=data['gcRequests'],actualGcCount=data['actualGcCount'],nativeRootMapsMoved=nativeMoved,observedContainerRootMoves=roots['observedContainerRootMoves'],historicalAbortRootCauseProven=False)
    else:
        data=result['affinity'];experiments=data['experiments'];initial=int(data['initialMask'],16)
        need(data['getResult']==0 and data['restoreResult']==0 and data['finalGetResult']==0 and int(data['finalMask'],16)==initial,'AFFINITY_RESTORED')
        cpus=[i for i in range(64) if initial&(1<<i)];need(len(experiments)==len(cpus)*2,'AFFINITY_EXPERIMENTS')
        supported=[];expanded=[]
        for cpu in cpus:
            pair=[e for e in experiments if e['cpu']==cpu];need(len(pair)==2 and {e['explicitTid'] for e in pair}=={True,False},'AFFINITY_TID_MODES')
            for e in pair:need(e['setResult']==e['getResult']==e['lateResult']==0 and e['rawBytes']>0 and e['mask']==e['rawMask'],'AFFINITY_KERNEL_OBSERVATION')
            exact=all(int(e['mask'],16)==int(e['lateMask'],16)==1<<cpu and e['count']==1 and e['actualCpu']==e['lateCpu']==cpu for e in pair)
            (supported if exact else expanded).append(cpu)
        report.update(singletonCpus=supported,nonSingletonOrMigratedCpus=expanded,performanceAccepted=False)
    report.update(verifierSha256=sha(Path(__file__)),scope='Owned probe diagnosis; no universal ART or performance verdict')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);p.add_argument('--case',choices=['managed-admission','affinity'],required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id,a.case)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
