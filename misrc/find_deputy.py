"""在 dex 中定位：哪些类引用了特权 binder 描述符（潜在的混淆代理入口）"""
import struct, sys, zipfile, re

def uleb(d,o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if not b&0x80: return r,o

def dex_strings(d):
    n,off = struct.unpack_from('<II', d, 0x38)
    out=[]
    for i in range(n):
        o = struct.unpack_from('<I', d, off+i*4)[0]
        _,o = uleb(d,o)
        e = d.index(b'\x00', o)
        out.append(d[o:e].decode('utf-8','replace'))
    return out

apk = sys.argv[1]
# 目标描述符 / 敏感字符串
needles = sys.argv[2:]
z = zipfile.ZipFile(apk)
for name in z.namelist():
    if not re.match(r'classes\d*\.dex$', name): continue
    d = z.read(name)
    ss = dex_strings(d)
    for nd in needles:
        hits = [s for s in ss if nd in s]
        if hits:
            print("[%s] 命中 '%s' -> %s" % (name, nd, hits[:6]))
