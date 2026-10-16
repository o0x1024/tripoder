package dev.tripoder.selfhook.dex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;

/** Strict DEX 039 static-reference reader. It never loads or initializes classes. */
public final class DexFile {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final class MethodRef {
        public final String dexSha256, owner, name, descriptor;
        public final int index;
        MethodRef(String digest, int index, String owner, String name, String descriptor) {
            this.dexSha256 = digest; this.index = index; this.owner = owner; this.name = name; this.descriptor = descriptor;
        }
        public String key() { return owner + "->" + name + descriptor; }
    }
    public static final class Definition {
        public final MethodRef method;
        public final int flags;
        public final List<DexCode.Call> calls;
        Definition(MethodRef method, int flags, List<DexCode.Call> calls) {
            this.method = method; this.flags = flags; this.calls = Collections.unmodifiableList(calls);
        }
    }
    final ByteBuffer data;
    final byte[] bytes;
    public final String sha256;
    public final List<MethodRef> methods;
    public final List<Definition> definitions;
    final int typeCount, fieldCount;
    private final int typeOffset;
    private final String[] strings;
    private final String[] prototypes;
    private final List<int[]> sections = new ArrayList<>();
    private long decodedUnits;

    public DexFile(byte[] input) {
        need(input != null && input.length >= 112 && input.length <= MAX_BYTES, "DEX_SIZE");
        bytes = input.clone();
        data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        need(Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{'d','e','x','\n','0','3','9',0}), "DEX_VERSION_NOT_039");
        need(u32(32) == bytes.length && u32(36) == 112 && u32(40) == 0x12345678L, "DEX_HEADER");
        need(u32(44) == 0 && u32(48) == 0, "DEX_LINK_DATA_UNSUPPORTED");
        Adler32 checksum = new Adler32(); checksum.update(bytes, 12, bytes.length - 12);
        need(u32(8) == checksum.getValue(), "DEX_CHECKSUM");
        need(Arrays.equals(Arrays.copyOfRange(bytes, 12, 32), digest("SHA-1", Arrays.copyOfRange(bytes, 32, bytes.length))), "DEX_SIGNATURE");
        sha256 = hex(digest("SHA-256", bytes));
        long dataStart = u32(108), dataSize = u32(104);
        need(dataStart >= 112 && dataStart + dataSize == bytes.length, "DEX_DATA_REGION");
        int map = offset(u32(52), 4);
        int mapCount = count(u32(map), 128);
        range(map + 4L, mapCount * 12L);
        Set<Integer> sectionTypes = new HashSet<>();
        int last = -1;
        for (int i = 0; i < mapCount; i++) {
            int item = map + 4 + i * 12, type = u16(item), start = offset(u32(item + 8), 1);
            need(u16(item + 2) == 0 && u32(item + 4) > 0 && start > last && sectionTypes.add(type), "DEX_MAP");
            sections.add(new int[]{type, start}); last = start;
        }
        int stringCount = table(56, 4, 1_000_000), stringOffset = (int) u32(60);
        strings = new String[stringCount];
        for (int i = 0; i < strings.length; i++) strings[i] = string(offset(u32(stringOffset + i * 4), 1));
        typeCount = table(64, 4, 65536); typeOffset = (int) u32(68);
        int protoCount = table(72, 12, 65536), protoOffset = (int) u32(76);
        prototypes = new String[protoCount];
        for (int i = 0; i < protoCount; i++) {
            int p = protoOffset + i * 12;
            stringAt(u32(p)); // Validate shorty string index as well.
            String result = type(u32(p + 4));
            StringBuilder signature = new StringBuilder("(");
            long parameters = u32(p + 8);
            if (parameters != 0) {
                int at = offset(parameters, 4); need((at & 3) == 0, "DEX_TYPE_LIST_ALIGNMENT");
                int n = count(u32(at), 65535); range(at + 4L, n * 2L);
                for (int j = 0; j < n; j++) { String t = type(u16(at + 4 + j * 2)); need(!t.equals("V"), "DEX_VOID_PARAMETER"); signature.append(t); }
            }
            prototypes[i] = signature.append(')').append(result).toString();
        }
        fieldCount = table(80, 8, 65536);
        int methodCount = table(88, 8, 65536), methodOffset = (int) u32(92);
        List<MethodRef> refs = new ArrayList<>();
        for (int i = 0; i < methodCount; i++) {
            int at = methodOffset + i * 8, proto = u16(at + 2);
            need(proto < prototypes.length, "DEX_PROTO_INDEX");
            String owner = type(u16(at)); need(owner.startsWith("L") || owner.startsWith("["), "DEX_METHOD_OWNER");
            refs.add(new MethodRef(sha256, i, owner, stringAt(u32(at + 4)), prototypes[proto]));
        }
        methods = Collections.unmodifiableList(refs);
        int classCount = table(96, 32, 65536), classOffset = (int) u32(100);
        List<Definition> bodies = new ArrayList<>();
        Set<String> owners = new HashSet<>(); Set<Integer> defined = new HashSet<>();
        Map<Integer, List<DexCode.Call>> codeCache = new HashMap<>();
        for (int i = 0; i < classCount; i++) {
            int at = classOffset + i * 32;
            String owner = type(u32(at)); need(owner.startsWith("L"), "DEX_CLASS_DEFINITION_TYPE");
            need(owners.add(owner), "DEX_DUPLICATE_CLASS");
            long classData = u32(at + 24);
            if (classData == 0) continue;
            int[] cursor = {offset(classData, 1)}; section(cursor[0], 0x2000);
            int staticFields = count(uleb(cursor), fieldCount), instanceFields = count(uleb(cursor), fieldCount);
            int directMethods = count(uleb(cursor), methodCount), virtualMethods = count(uleb(cursor), methodCount);
            fields(cursor, staticFields); fields(cursor, instanceFields);
            bodyGroup(cursor, directMethods, owner, bodies, defined, codeCache);
            bodyGroup(cursor, virtualMethods, owner, bodies, defined, codeCache);
        }
        definitions = Collections.unmodifiableList(bodies);
    }
    private void fields(int[] cursor, int n) {
        long index = 0;
        for (int i = 0; i < n; i++) { long delta = uleb(cursor); need(i == 0 || delta > 0, "DEX_FIELD_ORDER"); index += delta; need(index < fieldCount, "DEX_FIELD_INDEX"); uleb(cursor); }
    }
    private void bodyGroup(int[] cursor, int n, String owner, List<Definition> output, Set<Integer> defined, Map<Integer, List<DexCode.Call>> codeCache) {
        long index = 0;
        for (int i = 0; i < n; i++) {
            long delta = uleb(cursor); need(i == 0 || delta > 0, "DEX_METHOD_ORDER"); index += delta;
            need(index < methods.size() && defined.add((int)index), "DEX_DEFINED_METHOD_INDEX");
            MethodRef ref = methods.get((int) index); need(ref.owner.equals(owner), "DEX_DEFINED_OWNER");
            int flags = (int) uleb(cursor); long code = uleb(cursor);
            need((code == 0) == ((flags & (0x100 | 0x400)) != 0), "DEX_CODE_KIND");
            List<DexCode.Call> calls = new ArrayList<>();
            if (code != 0) {
                int at = offset(code, 16); need((at & 3) == 0, "DEX_CODE_OFFSET"); section(at, 0x2001);
                calls = codeCache.get(at);
                if (calls == null) {
                    int units = count(u32(at + 12), MAX_BYTES / 2); range(at + 16L, units * 2L);
                    decodedUnits += units; need(decodedUnits <= 8_000_000, "DEX_DECODE_BUDGET");
                    calls = DexCode.read(this, at + 16, units); codeCache.put(at, calls);
                }
            }
            output.add(new Definition(ref, flags, calls));
        }
    }
    private int table(int header, int width, int max) {
        int size = count(u32(header), max); long at = u32(header + 4);
        need(size == 0 ? at == 0 : at >= 112 && (at & 3) == 0, "DEX_TABLE_OFFSET");
        range(at, (long) size * width); return size;
    }
    private String type(long index) {
        need(index >= 0 && index < typeCount, "DEX_TYPE_INDEX");
        String value = stringAt(u32(typeOffset + (int)index * 4));
        int arrays = 0;
        while (arrays < value.length() && value.charAt(arrays) == '[') arrays++;
        need(arrays <= 255 && arrays < value.length(), "DEX_TYPE_DESCRIPTOR");
        String element = value.substring(arrays);
        boolean primitive = element.length() == 1 && "VZBSCIJFD".contains(element) && (arrays == 0 || !element.equals("V"));
        boolean object = element.length() > 2 && element.startsWith("L") && element.endsWith(";");
        if (object) {
            String name = element.substring(1, element.length() - 1);
            object = name.indexOf(';') < 0 && name.indexOf('[') < 0 && name.indexOf('.') < 0 && name.indexOf('\0') < 0;
        }
        need(primitive || object, "DEX_TYPE_DESCRIPTOR"); return value;
    }
    private String stringAt(long index) { need(index >= 0 && index < strings.length, "DEX_STRING_INDEX"); return strings[(int)index]; }
    private String string(int at) {
        section(at, 0x2002);
        int[] cursor = {at}; int units = count(uleb(cursor), 1_000_000);
        StringBuilder value = new StringBuilder(Math.min(units, 4096));
        while (true) {
            int first = u8(cursor[0]++); if (first == 0) break;
            int c;
            if (first < 0x80) c = first;
            else if ((first & 0xe0) == 0xc0) {
                int second = u8(cursor[0]++); need((second & 0xc0) == 0x80, "DEX_MUTF8_CONTINUATION");
                c = ((first & 31) << 6) | (second & 63); need(c == 0 || c >= 128, "DEX_MUTF8_OVERLONG");
            } else if ((first & 0xf0) == 0xe0) {
                int second = u8(cursor[0]++), third = u8(cursor[0]++);
                need((second & 0xc0) == 0x80 && (third & 0xc0) == 0x80, "DEX_MUTF8_CONTINUATION");
                c = ((first & 15) << 12) | ((second & 63) << 6) | (third & 63); need(c >= 2048, "DEX_MUTF8_OVERLONG");
            } else throw invalid("DEX_MUTF8_LEAD");
            need(value.length() < units, "DEX_STRING_LENGTH"); value.append((char)c);
        }
        need(value.length() == units, "DEX_STRING_LENGTH"); return value.toString();
    }
    long uleb(int[] cursor) {
        long value = 0;
        for (int i = 0; i < 5; i++) {
            int b = u8(cursor[0]++); need(i != 4 || b < 16, "DEX_ULEB_OVERFLOW"); value |= (long)(b & 127) << (7 * i);
            if ((b & 128) == 0) return value;
        }
        throw invalid("DEX_ULEB_OVERFLOW");
    }
    void section(int at, int expected) {
        int type = -1;
        for (int[] item : sections) { if (item[1] > at) break; type = item[0]; }
        need(type == expected, "DEX_DATA_SECTION");
    }
    int u8(int at) { range(at, 1); return bytes[at] & 255; }
    int u16(int at) { range(at, 2); return data.getShort(at) & 65535; }
    long u32(int at) { range(at, 4); return data.getInt(at) & 0xffffffffL; }
    int offset(long at, int size) { range(at, size); return (int)at; }
    void range(long at, long size) { need(at >= 0 && size >= 0 && at <= bytes.length && size <= bytes.length - at, "DEX_BOUNDS"); }
    static int count(long n, int max) { need(n >= 0 && n <= max, "DEX_COUNT_LIMIT"); return (int)n; }
    static void need(boolean ok, String reason) { if (!ok) throw invalid(reason); }
    static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    static byte[] digest(String name, byte[] input) {
        try { return MessageDigest.getInstance(name).digest(input); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
}
