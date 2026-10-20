import struct, sys, zipfile, re

def uleb(d, o):
    r=0; s=0
    while True:
        b=d[o]; o+=1; r|=(b&0x7f)<<s; s+=7
        if not b&0x80: return r,o

def parse_dex(d):
    string_ids_size, string_ids_off = struct.unpack_from('<II', d, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from('<II', d, 0x40)
    proto_ids_size, proto_ids_off = struct.unpack_from('<II', d, 0x48)
    method_ids_size, method_ids_off = struct.unpack_from('<II', d, 0x58)
    class_defs_size, class_defs_off = struct.unpack_from('<II', d, 0x60)

    def getstr(i):
        o = struct.unpack_from('<I', d, string_ids_off + i*4)[0]
        _, o = uleb(d, o)          # utf16 length
        e = d.index(b'\x00', o)
        return d[o:e].decode('utf-8','replace')

    types = [getstr(struct.unpack_from('<I', d, type_ids_off+i*4)[0]) for i in range(type_ids_size)]
    methods = []
    for i in range(method_ids_size):
        cls_i, proto_i, name_i = struct.unpack_from('<HHI', d, method_ids_off + i*8)
        methods.append((types[cls_i], getstr(name_i)))
    classes = []
    for i in range(class_defs_size):
        ci, acc, sup, ifo, src, ann, cdata, sdata = struct.unpack_from('<IIIIIIII', d, class_defs_off + i*32)
        classes.append(types[ci])
    return methods, classes

if __name__ == '__main__':
    apk = sys.argv[1]; needle = sys.argv[2]
    z = zipfile.ZipFile(apk)
    found = []
    for n in z.namelist():
        if not re.match(r'classes\d*\.dex$', n): continue
        d = z.read(n)
        ms, cs = parse_dex(d)
        for c in cs:
            if needle in c:
                found.append((n, c, [m for cl, m in ms if cl == c]))
    for n, c, ms in found:
        print("="*70)
        print("[%s] %s  (%d 个方法)" % (n, c, len(ms)))
        for m in sorted(set(ms)):
            print("    ", m)
