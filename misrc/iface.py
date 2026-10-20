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
        s.pn,s.po=struct.unpack_from('<II',d,0x48)
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
    def proto(s,i):
        sh,ret,par=struct.unpack_from('<III',s.d,s.po+i*12)
        return s.st(sh), s.ty(ret), s.ty(par)
    def mth(s,i):
        c,p,n=struct.unpack_from('<HHI',s.d,s.mo+i*8)
        return s.ty(c), s.st(n), p
    def cls(s):
        out=[]
        for i in range(s.cn):
            ci,acc,sup,ifo,src,ann,cd,sd=struct.unpack_from('<IIIIIIII',s.d,s.co+i*32)
            out.append((s.ty(ci),cd))
        return out
apk=sys.argv[1]; target=sys.argv[2]
z=zipfile.ZipFile(apk)
for name in sorted(n for n in z.namelist() if re.match(r'classes\d*\.dex$',n)):
    dx=Dex(z.read(name))
    for cn,cd in dx.cls():
        if target not in cn: continue
        print("[%s] %s" % (name,cn))
        if cd==0: continue
        d=dx.d;p=cd
        sf,p=uleb(d,p);inf,p=uleb(d,p);dm,p=uleb(d,p);vmc,p=uleb(d,p)
        for _ in range(sf): _,p=uleb(d,p);_,p=uleb(d,p)
        for _ in range(inf): _,p=uleb(d,p);_,p=uleb(d,p)
        idx=0
        for _ in range(dm):
            diff,p=uleb(d,p); idx+=diff
            acc,p=uleb(d,p); codeoff,p=uleb(d,p)
            mc,mn,mp=dx.mth(idx)
            try: sh,ret,par=dx.proto(mp)
            except Exception: sh,ret="?","?"
            print("   %s %s(%s) -> %s   [code=%d]"%(hex(acc),mn,sh,ret,codeoff))
