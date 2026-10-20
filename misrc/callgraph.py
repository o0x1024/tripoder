"""解析 dex code_item，找 Binder.transact / bindService 的调用者类"""
import struct, sys, zipfile, re

def uleb(d,o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if not b&0x80: return r,o

class Dex:
    def __init__(self, d):
        self.d=d
        self.sn,self.so = struct.unpack_from('<II',d,0x38)
        self.tn,self.to = struct.unpack_from('<II',d,0x40)
        self.pn,self.po = struct.unpack_from('<II',d,0x48)
        self.fn,self.fo = struct.unpack_from('<II',d,0x50)
        self.mn,self.mo = struct.unpack_from('<II',d,0x58)
        self.cn,self.co = struct.unpack_from('<II',d,0x60)
        self._s={}; self._t={}
    def s(self,i):
        if i in self._s: return self._s[i]
        o=struct.unpack_from('<I',self.d,self.so+i*4)[0]
        _,o=uleb(self.d,o)
        e=self.d.index(b'\x00',o)
        v=self.d[o:e].decode('utf-8','replace'); self._s[i]=v; return v
    def t(self,i):
        if i in self._t: return self._t[i]
        v=self.s(struct.unpack_from('<I',self.d,self.to+i*4)[0]); self._t[i]=v; return v
    def method(self,i):
        c,p,n=struct.unpack_from('<HHI',self.d,self.mo+i*8)
        return self.t(c), self.s(n)
    def field(self,i):
        c,ty,n=struct.unpack_from('<HHI',self.d,self.fo+i*8)
        return self.t(c), self.s(n)
    def classes(self):
        for i in range(self.cn):
            ci,acc,sup,ifo,src,ann,cd,sd=struct.unpack_from('<IIIIIIII',self.d,self.co+i*32)
            yield self.t(ci), cd
    def code_refs(self, cdata_off):
        """返回该类中所有 method/field 引用索引"""
        d=self.d
        if cdata_off==0: return [],[]
        p=cdata_off
        sf, p = uleb(d,p); inf, p = uleb(d,p)
        dm, p = uleb(d,p); vm, p = uleb(d,p)
        for _ in range(sf): _,p=uleb(d,p); _,p=uleb(d,p)
        for _ in range(inf): _,p=uleb(d,p); _,p=uleb(d,p)
        mrefs=[]; frefs=[]
        def enc_methods(cnt):
            nonlocal p
            idx=0
            for _ in range(cnt):
                diff,p=uleb(d,p); idx+=diff
                _,p=uleb(d,p)
                off,p=uleb(d,p)
                if off!=0: mrefs.append((idx,off))
        def enc_fields(cnt):
            nonlocal p
            idx=0
            for _ in range(cnt):
                diff,p=uleb(d,p); idx+=diff
                _,p=uleb(d,p)
                if _>=0: pass
        enc_methods(dm); enc_fields(vm)
        return mrefs, frefs

apk=sys.argv[1]
z=zipfile.ZipFile(apk)
for name in sorted(n for n in z.namelist() if re.match(r'classes\d*\.dex$',n)):
    dx=Dex(z.read(name))
    # 收集所有 string 里含 transact/bindService 的方法引用
    susp_methods=set()
    for i in range(dx.mn):
        cls,nm = dx.method(i)
        if nm in ('transact','bindService','query','call','openFile','openAssetFile'):
            susp_methods.add((cls,nm))
    print("[%s] 可疑方法引用 %d 个" % (name, len(susp_methods)))
