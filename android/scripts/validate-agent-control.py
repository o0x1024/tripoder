#!/usr/bin/env python3
"""Owned fixture MCP transport and phone UI acceptance. Does not claim Sentinel/model or P6 acceptance."""
import argparse, datetime, hashlib, json, re, shutil, subprocess, time, uuid, zipfile
from pathlib import Path

def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def self_hook_samples(log):
    return [{'pid': int(pid), 'bindings': int(bindings), 'durationNs': int(duration)}
            for pid, bindings, duration in re.findall(
                r'^\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+TripoderSelfHook\s*:\s+READY:READY:27/27:bindings=(\d+):ns=(\d+)$',
                log, re.MULTILINE)]

def verify_runtime_status(status, *, pid, uid, generation, core_path, native_sha256, native_size):
    """Independently validate the bounded worker observation against this run and APK."""
    def require(value, code):
        if not value:raise RuntimeError('RUNTIME_MAPPING_'+code)
    require(type(pid) is int and pid>0 and type(uid) is int and uid>0 and isinstance(generation,str) and bool(generation),'EXPECTED_IDENTITY')
    require(isinstance(status,dict) and isinstance(status.get('identity'),dict),'STATUS_MISSING')
    identity=status['identity'];value=status.get('runtimeNativeMappings')
    require(identity.get('pid')==pid and identity.get('processGeneration')==generation,'STATUS_IDENTITY')
    require(isinstance(value,dict),'MISSING')
    require(type(value.get('schema')) is int and value['schema']==1,'SCHEMA')
    require(type(value.get('pid')) is int and value['pid']==pid and type(value.get('uid')) is int and value['uid']==uid and value.get('processGeneration')==generation,'PROCESS_IDENTITY')
    physical=value.get('physicalProcess')
    require(isinstance(physical,str) and re.fullmatch(r'dev\.tripoder\.engine:slot[0-7]',physical) is not None,'PHYSICAL_PROCESS')
    require(value.get('coreSha256')==native_sha256 and re.fullmatch(r'[a-f0-9]{64}',native_sha256) is not None,'CORE_SHA256')
    require(type(value.get('coreSize')) is int and value['coreSize']==native_size and 0<native_size<=64*1024*1024,'CORE_SIZE')
    require(value.get('corePath')==core_path and re.fullmatch(r'/data/app/(?:[^/]+/)+lib/arm64/libtripoderselfhook\.so',core_path) is not None and all(part not in ('.','..') for part in core_path.split('/')),'CORE_PATH')
    require(value.get('foreignBackendRows')==[],'FOREIGN_BACKEND')
    inode=value.get('coreInode');rows=value.get('coreRows');paths_joined=value.get('nativePaths')
    require(isinstance(paths_joined,str) and len(paths_joined)<=32000,'NATIVE_PATHS');paths=paths_joined.split('\n')
    require(isinstance(inode,str) and re.fullmatch(r'[1-9][0-9]*',inode) is not None,'INODE')
    require(isinstance(rows,list) and 1<=len(rows)<=16 and all(isinstance(row,str) and '\n' not in row for row in rows),'ROWS')
    require(1<=len(paths)<=1024 and all(path.startswith('/') and '\n' not in path for path in paths),'NATIVE_PATHS')
    require(len(set(paths))==len(paths) and paths.count(core_path)==1 and len('\n'.join(paths))<=32000,'NATIVE_PATHS')
    require(not any(name in path.lower() for path in paths for name in ('frida','gadget','tripoderio')),'FOREIGN_NATIVE_PATH')
    core_names=[path for path in paths if path.endswith('/libtripoderselfhook.so') or path.endswith('/libtripoderselfhook.so (deleted)')]
    require(core_names==[core_path],'CORE_PATH_ALIAS')
    pattern=r'([0-9a-f]+)-([0-9a-f]+) ([r-][w-][x-][ps]) ([0-9a-f]+) ([0-9a-f]+):([0-9a-f]+) ([0-9]+) +(.+)'
    previous_end=0;device=None;executable=False
    for line in rows:
        row=re.fullmatch(pattern,line);require(row is not None,'ROW_FORMAT')
        begin,end=int(row[1],16),int(row[2],16);permissions=row[3];offset=int(row[4],16);row_device=(int(row[5],16),int(row[6],16))
        require(begin>=previous_end and end>begin and begin%4096==0 and end%4096==0 and offset%4096==0 and offset<native_size,'ROW_RANGE')
        require(row[8]==core_path and row[7]==inode and row_device!=(0,0),'ROW_IDENTITY')
        if device is None:device=row_device
        require(device==row_device,'ROW_DEVICE')
        require(not ('w' in permissions and 'x' in permissions),'ROW_WX')
        executable|='x' in permissions;previous_end=end
    require(executable,'EXECUTABLE_MISSING')
    require(type(value.get('mapsBytes')) is int and sum(len(row.encode())+1 for row in rows)<=value['mapsBytes']<=2*1024*1024,'MAPS_BYTES')
    require(type(value.get('mapsRowCount')) is int and value['mapsRowCount']>=len(rows),'MAPS_ROWS')
    require(all(isinstance(value.get(key),str) and re.fullmatch(r'[a-f0-9]{64}',value[key]) is not None for key in ('mapsSha256','nativeRowsSha256')),'MAPS_DIGEST')
    return {'passed':True,'pid':pid,'uid':uid,'processGeneration':generation,'physicalProcess':physical,'corePath':core_path,'coreSha256':native_sha256,'coreSize':native_size,'coreMappingRows':len(rows),'mapsSha256':value['mapsSha256'],'mapsRowCount':value['mapsRowCount'],'mapsBytes':value['mapsBytes'],'nativePaths':len(paths),'foreignBackendRows':0}


def product_native_identity(receipt):
    with zipfile.ZipFile(receipt['artifacts']['engine']['path']) as archive:
        member='lib/arm64-v8a/libtripoderselfhook.so'
        if sorted(name for name in archive.namelist() if name.startswith('lib/'))!=[member]:raise RuntimeError('PRODUCT_NATIVE_PACKAGING_MISMATCH')
        value=archive.read(member)
    digest=hashlib.sha256(value).hexdigest()
    if digest!=receipt['selfHookNative']['sha256']:raise RuntimeError('PRODUCT_NATIVE_RECEIPT_MISMATCH')
    return digest,len(value)


def agent_status_receipts(transcript):
    requests={};statuses=[]
    for entry in transcript:
        message=entry.get('message',{})
        if entry.get('direction')=='request' and message.get('method')=='tools/call':requests[message.get('id')]=message.get('params',{}).get('name')
        if entry.get('direction')!='response' or requests.get(message.get('id'))!='hook_status':continue
        result=message.get('result',{});content=result.get('content',[])
        if result.get('isError') is not False or len(content)!=1 or content[0].get('type')!='text':raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_REJECTED')
        receipt=json.loads(content[0]['text'])
        if receipt.get('ok') is not True or not isinstance(receipt.get('result'),dict):raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_REJECTED')
        statuses.append(receipt)
    if not statuses:raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_MISSING')
    return statuses


def main():
    # Preserve independently imported mapping verifiers; the current endpoint is
    # unsigned and its acceptance no longer creates pairing files.
    import runpy
    runpy.run_path(str(Path(__file__).with_name('validate-open-agent.py')), run_name='__main__')

if __name__=='__main__':main()
