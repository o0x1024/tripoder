"""Collect a deliberate exit_group case without confusing no ACK with success."""
import json
import re
import time

def exit_record(text,pid,uid):
    matches=[block for block in text.split('ApplicationExitInfo #')[1:] if re.search(r'\bpid='+str(pid)+r'\s',block)]
    if len(matches)!=1:return None
    block=matches[0]
    if not re.search(r'\brealUid='+str(uid)+r'\s',block):return None
    status=re.search(r'process=dev\.tripoder\.selfhookprobe reason=(\d+) \([^\n]+?\).*? status=(\d+)\b',block)
    if not status:return None
    return {'pid':pid,'uid':uid,'reason':int(status[1]),'status':int(status[2])}

def collect(command,directory,remote,package,expected_status):
    deadline=time.monotonic()+120
    while command(None,'shell','pidof',package,check=False).returncode==0:
        if command(None,'shell','test','-f',remote+'/result.json',check=False).returncode==0:
            raise RuntimeError('ACTIVE_UNLOAD_RETURNED_INSTEAD_OF_EXIT')
        if time.monotonic()>deadline:raise RuntimeError('ACTIVE_UNLOAD_DID_NOT_EXIT')
        time.sleep(0.2)
    command('pull','pull',remote,str(directory/'device'))
    before=json.loads((directory/'device/before-active-unload.json').read_text())
    ready=json.loads((directory/'device/active-unload-ready.json').read_text())
    if before['pid']!=ready['pid']:raise RuntimeError('DEATH_WITNESS_PID')
    deadline=time.monotonic()+15
    while True:
        dump=command('exit-info','shell','dumpsys','activity','exit-info',package).stdout
        record=exit_record(dump,before['pid'],before['uid'])
        if record:break
        if time.monotonic()>deadline:raise RuntimeError('DEATH_EXIT_RECORD_MISSING')
        time.sleep(0.2)
    if record['reason']!=1 or record['status']!=expected_status:raise RuntimeError('UNEXPECTED_DEATH:'+json.dumps(record))
    (directory/'exit-observation.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps({'runId':before['runId'],'status':'EXPECTED_EXIT_COLLECTED','pid':record['pid'],'exitStatus':record['status']}),flush=True)
