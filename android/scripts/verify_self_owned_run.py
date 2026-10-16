"""Identity gate shared by the new fixed-inventory and capacity verifiers."""
import hashlib
import json
from pathlib import Path
import uuid

ROOT=Path(__file__).resolve().parents[2]

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def need(value,reason):
    if not value:raise RuntimeError(reason)

def read(directory,run_id,phase):
    directory=Path(directory);result_path=directory/'device/result.json'
    result=json.loads(result_path.read_text());build=json.loads((directory/'build-receipt.json').read_text())
    plan=json.loads((directory/'run-plan.json').read_text())
    need(result['passed'] is True,'OWNED_RESULT_PASSED')
    report=identity(directory,run_id,phase,result,build,plan)
    maps=(directory/'device/maps.txt').read_text()
    need(result['mapsSha256']==sha(directory/'device/maps.txt') and not any(s in maps.lower() for s in ('frida','gadget','libgum')),'OWNED_MAPS')
    report.update(resultSha256=sha(result_path))
    return result,build,report

def identity(directory,run_id,phase,result,build,plan):
    need(result['phase']==phase and result['runId']==str(uuid.UUID(run_id))==plan['runId'],'OWNED_RUN_IDENTITY')
    need(result['package']==plan['package']=='dev.tripoder.selfhookprobe' and result['debuggable'] is False
         and result['uid']>=10000 and result['pid']>0,'OWNED_PROCESS')
    need(result['apkSha256']==build['apkSha256']==plan['apkSha256']==sha(build['apk'])
         and result['nativeSha256']==build['nativeSha256'] and result['build']['sourceDigest']==build['sourceDigest']
         and result['build']['buildId']==build['buildId'],'OWNED_BUILD')
    for name,digest in build['sources'].items():need(sha(ROOT/name)==digest,'SOURCE_CHANGED:'+name)
    need(hashlib.sha256(json.dumps(build['sources'],sort_keys=True).encode()).hexdigest()==build['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    need(result['platformGatePassed'] is True and all(result['platform'][k]==v for k,v in platform.items()),'FIXED_PLATFORM')
    need('Status: ok' in (directory/'launch.txt').read_text() and 'LaunchState: COLD' in (directory/'launch.txt').read_text(),'COLD_LAUNCH')
    return dict(passed=True,phase=phase,runId=run_id,apkSha256=build['apkSha256'],buildId=build['buildId'],
        sourceDigest=build['sourceDigest'],identityVerifierSha256=sha(Path(__file__)),productAccepted=False)
