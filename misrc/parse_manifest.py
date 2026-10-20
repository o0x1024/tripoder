import struct, sys, zipfile

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102

def read_string_pool(d, off):
    t, hs, sz = struct.unpack_from('<HHI', d, off)
    cnt, scnt, flags, strStart, styStart = struct.unpack_from('<IIIII', d, off+8)
    utf8 = bool(flags & 0x100)
    offs_arr = off + hs                    # 偏移数组紧跟头部
    data_base = off + strStart             # 字符串数据区
    out = []
    for i in range(cnt):
        p = struct.unpack_from('<I', d, offs_arr + i*4)[0]
        s = data_base + p
        if utf8:
            s += 2 if (d[s] & 0x80) else 1          # 跳过 utf16 长度
            ln = d[s]; s += 1
            if ln & 0x80:
                ln = ((ln & 0x7f) << 8) | d[s]; s += 1
            out.append(d[s:s+ln].decode('utf-8', 'replace'))
        else:
            ln = struct.unpack_from('<H', d, s)[0]; s += 2
            if ln & 0x8000:
                ln = ((ln & 0x7fff) << 16) | struct.unpack_from('<H', d, s)[0]; s += 2
            out.append(d[s:s+ln*2].decode('utf-16-le', 'replace'))
    return out

def parse(path):
    d = zipfile.ZipFile(path).read('AndroidManifest.xml')
    if d[:4] != b'\x03\x00\x08\x00':
        raise ValueError('not AXML')
    strings = None
    off = 8
    comps = []
    while off + 8 <= len(d):
        t, hs, sz = struct.unpack_from('<HHI', d, off)
        if sz <= 0 or off + sz > len(d):
            break
        if t == RES_STRING_POOL:
            strings = read_string_pool(d, off)
        elif t == RES_XML_START_ELEMENT and strings:
            base = off + hs
            ns_i, name_i = struct.unpack_from('<iI', d, base)
            tag = strings[name_i]
            aStart, aSize, aCount = struct.unpack_from('<HHH', d, base + 8)
            attrs = {}
            for i in range(aCount):
                a = base + aStart + i*aSize
                if a + 20 > len(d): break
                _, nm_i, raw, _, _, dtype, dval = struct.unpack_from('<iIIHBBI', d, a)
                key = strings[nm_i]
                if dtype == 0x03:      # string
                    v = strings[dval] if dval < len(strings) else dval
                elif dtype == 0x12:    # boolean
                    v = (dval != 0)
                elif dtype == 0x10:    # int dec
                    v = dval
                elif dtype == 0x11:    # int hex
                    v = dval
                elif raw != 0xFFFFFFFF and raw < len(strings):
                    v = strings[raw]
                else:
                    v = dval
                attrs[key] = v
            comps.append((tag, attrs))
        off += sz
    return comps

for apk in sys.argv[1:]:
    print("=" * 72)
    print("APK:", apk)
    try:
        comps = parse(apk)
    except Exception as e:
        print("  解析失败:", e); continue
    app = next((a for t, a in comps if t == 'application'), {})
    print("  package      :", app.get('package') if 'package' in app else '(见下)')
    print("  allowBackup  :", app.get('allowBackup'))
    print("  debuggable   :", app.get('debuggable'))
    print("  cleartext    :", app.get('usesCleartextTraffic'))
    print("  netSecConfig :", app.get('networkSecurityConfig'))
    print("  minSdk/target:", app.get('minSdkVersion'), '/', app.get('targetSdkVersion'))
    exported = [(t, a.get('name'), a.get('permission')) for t, a in comps
                if t in ('activity','activity-alias','service','receiver','provider')
                and a.get('exported') is True]
    noperm = [e for e in exported if not e[2]]
    print("  导出组件: %d   无 permission 保护: %d" % (len(exported), len(noperm)))
    for t, n, p in noperm:
        print("    [无保护] %-10s %s" % (t, n))

# ---- Provider 细节 ----
def providers(path):
    comps = parse(path)
    out=[]
    for t,a in comps:
        if t=='provider' and a.get('exported') is True:
            out.append(a)
    return out
