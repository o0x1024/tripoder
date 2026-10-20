import struct,zipfile,sys
def uleb(d,o):
    r=0;s=0
    while True:
        b=d[o];o+=1;r|=(b&0x7f)<<s;s+=7
        if not b&0x80: return r,o
z=zipfile.ZipFile('misrc/apk/com.miui.securitycenter.apk')
d=z.read('classes4.dex')
sn,so=struct.unpack_from('<II',d,0x38); tn,to=struct.unpack_from('<II',d,0x40)
mn,mo=struct.unpack_from('<II',d,0x58)
def st(i):
    o=struct.unpack_from('<I',d,so+i*4)[0]
    _,o=uleb(d,o); e=d.index(b'\x00',o)
    return d[o:e].decode('utf-8','replace')
def ty(i): return st(struct.unpack_from('<I',d,to+i*4)[0])
def mname(i):
    c=struct.unpack_from('<H',d,mo+i*8)[0]
    n=struct.unpack_from('<I',d,mo+i*8+4)[0]
    return ty(c), st(n)
coff=int(sys.argv[1]); label=sys.argv[2]
regs,ins,outs,tries,dbg,size=struct.unpack_from('<HHHHII',d,coff)
print("=== %s  regs=%d ins=%d size=%d"%(label,regs,ins,size))
base=coff+16
i=0
while i<size:
    o=base+i*2
    u=struct.unpack_from('<H',d,o)[0]
    op=u&0xff
    if u==0x0100:
        sz,first=struct.unpack_from('<II',d,o+2)
        print("  PACKED first=%d targets=%s"%(first,list(struct.unpack_from('<%di'%sz,d,o+10))))
        i+=sz*2+4; continue
    if u==0x0200:
        sz=struct.unpack_from('<I',d,o+2)[0]
        print("  SPARSE keys=%s"%list(struct.unpack_from('<%di'%sz,d,o+4)))
        i+=sz*4+2; continue
    if op==0x1a:
        print("  const-str %r"%st(struct.unpack_from('<H',d,o+2)[0])); i+=1
    elif op==0x1b:
        print("  const-string/jumbo"); i+=2
    elif op==0x13:
        print("  const/16 %d"%struct.unpack_from('<h',d,o+2)[0]); i+=1
    elif op==0x14:
        print("  const %d"%struct.unpack_from('<i',d,o+2)[0]); i+=2
    elif op==0x15:
        print("  const/high16 0x%x"%struct.unpack_from('<i',d,o+2)[0]); i+=1
    elif 0x6e<=op<=0x72:
        c,n=mname(struct.unpack_from('<H',d,o+2)[0])
        print("  invoke-static/virtual %s.%s"%(c.split('/')[-1],n)); i+=1
    elif 0x74<=op<=0x78:
        c,n=mname(struct.unpack_from('<H',d,o+2)[0])
        print("  invoke-range %s.%s"%(c.split('/')[-1],n)); i+=2
    elif op in (0x2b,0x2c):
        print("  <switch>"); i+=2
    i+=1
