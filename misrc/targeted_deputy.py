"""有界提取：找出既 exported 又引用了敏感 binder/Provider 的类"""
import struct, sys, zipfile, re

def uleb(d,o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if not b&0x80: return r,o

class Dex:
    def __init__(s,d):
        s.d=d
        s.sn,s.so=struct.unpack_from('<II',d,0x38)
        s.tn,s.to=struct.unpack_from('<II',d,0x40)
        s.mn,s.mo=struct.unpack_from('<II',d,0x58)
        s.cn,s.co=struct.unpack_from('<II',d,0x60)
        s._s={};s._t={}
    def st(s,i):
        if i in s._s: return s._s[i]
        o=struct.unpack_from('<I',s.d,s.so+i*4)[0]
        _,o=uleb(s.d,o); e=s.d.index(b'\x00',o)
        v=s.d[o:e].decode('utf-8','replace'); s._s[i]=v; return v
    def ty(s,i):
        if i in s._t: return s._t[i]
        v=s.st(struct.unpack_from('<I',s.d,s.to+i*4)[0]); s._t[i]=v; return v
    def mth(s,i):
        c,p,n=struct.unpack_from('<HHI',s.d,s.mo+i*8)
        return s.ty(c), s.st(n)
    def cls_list(s):
        out=[]
        for i in range(s.cn):
            ci,acc,sup,ifo,src,ann,cd,sd=struct.unpack_from('<IIIIIIII',s.d,s.co+i*32)
            out.append((s.ty(ci), acc, cd))
        return out

def class_method_refs(dx, cdata_off):
    """返回该类直接定义的方法索引集合（用于判断它引用了哪些外部方法）"""
    d=dx.d; p=cdata_off
    if p==0: return set()
    sf,p=uleb(d,p); inf,p=uleb(d,p); dm,p=uleb(d,p); vm,p=uleb(d,p)
    for _ in range(sf): _,p=uleb(d,p); _,p=uleb(d,p)
    for _ in range(inf): _,p=uleb(d,p); _,p=uleb(d,p)
    idx=0; methods=set()
    for _ in range(dm):
        diff,p=uleb(d,p); idx+=diff
        _,p=uleb(d,p); _,p=uleb(d,p)
        methods.add(idx)
    return methods

apk=sys.argv[1]
# 敏感符号
SENS = sys.argv[2].split(',')

z=zipfile.ZipFile(apk)
for name in sorted(n for n in z.namelist() if re.match(r'classes\d*\.dex$',n)):
    dx=Dex(z.read(name))
    for cname, acc, cd in dx.cls_list():
        # 只关心该类的字符串表里是否出现敏感符号（类级别的粗筛）
        if not any(k.lower() in cname.lower() for k in SENS):
            continue
        print("[%s] %s" % (name, cname))
