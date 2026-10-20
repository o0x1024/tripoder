"""解码 onTransact 的 switch 表与参数读取顺序"""
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
    def mth(s,i):
        c,p,n=struct.unpack_from('<HHI',s.d,s.mo+i*8)
        return s.ty(c), s.st(n)
    def cls(s):
        out=[]
        for i in range(s.cn):
            ci,acc,sup,ifo,src,ann,cd,sd=struct.unpack_from('<IIIIIIII',s.d,s.co+i*32)
            out.append((s.ty(ci),cd))
        return out

def find_method_code(dx, cdata, want):
    d=dx.d;p=cdata
    sf,p=uleb(d,p);inf,p=uleb(d,p);dm,p=uleb(d,p);vm,p=uleb(d,p)
    for _ in range(sf): _,p=uleb(d,p);_,p=uleb(d,p)
    for _ in range(inf): _,p=uleb(d,p);_,p=uleb(d,p)
    idx=0
    for _ in range(dm):
        diff,p=uleb(d,p); idx+=diff
        acc,p=uleb(d,p); coff,p=uleb(d,p)
        c,n=dx.mth(idx)
        if n==want: return coff,idx
    return None,None

def decode(dx, coff):
    d=dx.d
    regs,ins,outs,tries,dbg,insns_size=struct.unpack_from('<HHHHII',d,coff)
    base=coff+16
    out=[]
    i=0
    while i < insns_size:
        o=base+i*2
        u=struct.unpack_from('<H',d,o)[0]
        op=u&0xff
        if op==0 and u!=0:  # payload
            ident=u
            if ident==0x0100:
                sz,first=struct.unpack_from('<II',d,o+2)
                tg=struct.unpack_from('<%di'%sz,d,o+10)
                out.append(("PACKED",sz,first,list(tg)))
                i+= (sz*2+4)
                continue
            if ident==0x0200:
                sz=struct.unpack_from('<I',d,o+2)[0]
                keys=struct.unpack_from('<%di'%sz,d,o+4)
                out.append(("SPARSE",sz,list(keys)))
                i+= (sz*4+2)
                continue
        if op==0x12: # const/4
            v=(u>>12)&0xf; val=(u>>8)&0xf
            if val&8: val-=16
            out.append(("const/4",v,val))
        elif op==0x13: # const/16
            v=(u>>8)&0xff; val=struct.unpack_from('<h',d,o+2)[0]
            out.append(("const/16",v,val)); i+=1
        elif op==0x14:
            v=(u>>8)&0xff; val=struct.unpack_from('<i',d,o+2)[0]
            out.append(("const",v,val)); i+=2
        elif op==0x1a: # const-string
            v=(u>>8)&0xff; si=struct.unpack_from('<H',d,o+2)[0]
            out.append(("const-str",v,dx.st(si))); i+=1
        elif 0x6e<=op<=0x72 or 0x74<=op<=0x78:
            mi=struct.unpack_from('<H',d,o+2)[0]
            c,n=dx.mth(mi)
            out.append(("invoke",op,c,n))
            i+= (2 if op>=0x74 else 1)
        elif op==0x2b or op==0x2c:
            out.append(("switch",hex(op))); i+=2
        i+=1
    return out

apk=sys.argv[1]; cls=sys.argv[2]; meth=sys.argv[3]
z=zipfile.ZipFile(apk)
for name in sorted(n for n in z.namelist() if re.match(r'classes\d*\.dex$',n)):
    dx=Dex(z.read(name))
    for cn,cd in dx.cls():
        if cn!=cls: continue
        coff,mi=find_method_code(dx,cd,meth)
        if coff is None: continue
        print("=== %s.%s ==="%(cn,meth))
        for ins in decode(dx,coff):
            if ins[0]=="invoke":
                print("   invoke  %s.%s"%(ins[2].split('/')[-1],ins[3]))
            elif ins[0]=="const-str":
                print("   str     %r"%ins[2])
            elif ins[0] in ("const/4","const/16","const"):
                print("   %s v%d = %d"%(ins[0],ins[1],ins[2]))
            elif ins[0]=="PACKED":
                print("   PACKED size=%d first=%d targets=%s"%(ins[1],ins[2],ins[3]))
            elif ins[0]=="SPARSE":
                print("   SPARSE size=%d keys=%s"%(ins[1],ins[2]))
