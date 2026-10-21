#!/usr/bin/env python3
"""Differential checks for the real native maps scanner, with no Android I/O."""
import ctypes
import json
from pathlib import Path
import random
import subprocess
import tempfile

root=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='tripoder-mapping-rows-') as directory:
    library=Path(directory)/'mapping.dylib'
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-shared','-fPIC','-DTP_MAPPING_ROWS_HOST',str(root/'android/runtime/native/mapping_evidence.c'),'-o',str(library)],check=True)
    native=ctypes.CDLL(str(library)).tp_mapping_evidence_rows
    native.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.POINTER(ctypes.c_size_t),ctypes.POINTER(ctypes.c_uint)]
    native.restype=ctypes.c_int
    def scan(data):
        output=ctypes.create_string_buffer(max(1,len(data)));used=ctypes.c_size_t();rows=ctypes.c_uint()
        status=native(data,len(data),output,ctypes.byref(used),ctypes.byref(rows))
        return status,output.raw[:used.value],rows.value
    random.seed(7);lines=[];expected=[]
    paths=['[anon:helper]','/data/app/owned/libfixture.so','/data/app/removed/libfixture.so (deleted)','/memfd:FrIdA','/tmp/gadget-memory','/tmp/TRIPODERIO','/tmp/普通文件','']
    for index in range(18000):
        path=random.choice(paths)
        line=f'{0x100000+index*8192:x}-{0x101000+index*8192:x} r-xp 00000000 00:00 {index} {path}\n'
        lines.append(line)
        if path.endswith(('.so','.so (deleted)')) or any(token in path.lower() for token in ['frida','gadget','tripoderio']) or not path.isascii():expected.append(line)
    data=''.join(lines).encode();status,selected,count=scan(data)
    assert status==0 and selected==''.join(expected).encode() and count==18000
    checks=1
    good=b'1000-2000 r-xp 0 00:00 7 /data/app/libcore.so\n'
    for bad in [b'',good[:-1],b'bad\n',good.replace(b'r-xp',b'r-zp'),good.replace(b'00:00',b'00:'),good.replace(b' 7 ',b' -7 '),good.replace(b'/data/app',b'/bad\rpath'),good.replace(b'/data/app',b'/bad\xc0\x80'),good.replace(b'/data/app',b'/bad\xed\xa0\x80'),good.replace(b'/data/app',b'/bad\xf4\x90\x80\x80')]:
        assert scan(bad)[0]!=0,bad
        checks+=1
    for path in ['/tmp/\U0001f600','/tmp/fr\u0131da']:
        line=('1000-2000 r-xp 0 00:00 0 '+path+'\n').encode();assert scan(line)==(0,line,1);checks+=1
    print(json.dumps({'passed':True,'checks':checks,'rows':18000,'scope':'native-differential-mapping-scan'}))
