import struct,zipfile,sys
def uleb(d,o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if not b&0x80: return r,o
z=zipfile.ZipFile(sys.argv[1])
d=z.read(sys.argv[3] if len(sys.argv)>3 else 'classes4.dex')
sn,so=struct.unpack_from('<II',d,0x38)
tn,to=struct.unpack_from('<II',d,0x40)
mn,mo=struct.unpack_from('<II',d,0x58)
cn,co=struct.unpack_from('<II',d,0x60)
def st(i):
    o=struct.unpack_from('<I',d,so+i*4)[0]
    _,o=uleb(d,o); e=d.index(b'\x00',o)
    return d[o:e].decode('utf-8','replace')
def ty(i): return st(struct.unpack_from('<I',d,to+i*4)[0])
def mname(i):
    c=struct.unpack_from('<H',d,mo+i*8)[0]
    n=struct.unpack_from('<I',d,mo+i*8+4)[0]
    return ty(c), st(n)
target=sys.argv[2]
for i in range(cn):
    ci,acc,sup,ifo,src,ann,cd,sd=struct.unpack_from('<IIIIIIII',d,co+i*32)
    name=ty(ci)
    if target not in name: continue
    print("=== %s  (super=%s)"%(name, ty(sup)))
    if cd==0:
        print("   (无 class_data)"); continue
    p=cd
    sf,p=uleb(d,p);inf,p=uleb(d,p);dm,p=uleb(d,p);vm,p=uleb(d,p)
    for _ in range(sf): _,p=uleb(d,p);_,p=uleb(d,p)
    for _ in range(inf): _,p=uleb(d,p);_,p=uleb(d,p)
    idx=0
    for _ in range(dm):
        diff,p=uleb(d,p); idx+=diff
        a,p=uleb(d,p); c,p=uleb(d,p)
        cc,nn=mname(idx)
        print("   direct  %-22s code=%d"%(nn,c))
    idx=0
    for _ in range(vm):
        diff,p=uleb(d,p); idx+=diff
        a,p=uleb(d,p); c,p=uleb(d,p)
        cc,nn=mname(idx)
        print("   virtual %-22s code=%d"%(nn,c))
