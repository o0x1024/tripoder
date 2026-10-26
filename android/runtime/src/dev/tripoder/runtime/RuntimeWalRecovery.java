package dev.tripoder.runtime;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Checksum;

/**
 * Offline SQLite WAL forensics: carves table-leaf-page cells directly out of the raw "-wal" file
 * bytes, independent of whatever the live connection currently shows. A row deleted from the main
 * table remains recoverable here until SQLite checkpoints (overwrites) the WAL frame that held it.
 * Read-only, bounded, no Hook/native involvement; this is the same trust level as the existing
 * direct-file-I/O inspectDatabases. Overflow-page payloads (cells too large for one page) are
 * explicitly flagged rather than silently truncated or guessed at.
 */
final class RuntimeWalRecovery {
    private RuntimeWalRecovery(){}
    private static final int MAX_WAL_BYTES = 16 * 1024 * 1024, MAX_FRAMES = 20000, MAX_ROWS = 2000;

    static Map<String,Object> recover(File dbFile, String table, List<String> columns, String rowidAliasColumn, int limit) throws Exception {
        if (!table.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("WAL_RECOVER_TABLE_NAME");
        if (limit < 1 || limit > MAX_ROWS) throw new IllegalArgumentException("WAL_RECOVER_LIMIT");
        if (columns.isEmpty() || columns.size() > 64) throw new IllegalArgumentException("WAL_RECOVER_COLUMNS");
        for (String c : columns) if (!c.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("WAL_RECOVER_COLUMN_NAME");
        if (!rowidAliasColumn.isEmpty() && !columns.contains(rowidAliasColumn)) throw new IllegalArgumentException("WAL_RECOVER_ROWID_ALIAS_NOT_IN_COLUMNS");
        File wal = new File(dbFile.getParentFile(), dbFile.getName() + "-wal");
        if (java.nio.file.Files.isSymbolicLink(wal.toPath())) throw new SecurityException("WAL_RECOVER_SYMLINK");
        if (!wal.isFile()) return Map.of("recovered", false, "reason", "NO_WAL_FILE", "rows", List.of());
        long length = wal.length();
        if (length < 32) return Map.of("recovered", false, "reason", "WAL_HEADER_TRUNCATED", "rows", List.of());
        if (length > MAX_WAL_BYTES) throw new IllegalArgumentException("WAL_RECOVER_FILE_TOO_LARGE");

        byte[] bytes;
        try (RandomAccessFile in = new RandomAccessFile(wal, "r")) {
            bytes = new byte[(int) length];
            in.readFully(bytes);
        }
        int magic = i32(bytes, 0);
        if (magic != 0x377f0682 && magic != 0x377f0683) return Map.of("recovered", false, "reason", "WAL_MAGIC_MISMATCH", "rows", List.of());
        boolean bigEndianChecksum = magic == 0x377f0683;
        int pageSize = i32(bytes, 8);
        if (pageSize < 512 || pageSize > 65536 || Integer.bitCount(pageSize) != 1) return Map.of("recovered", false, "reason", "WAL_PAGE_SIZE_INVALID", "rows", List.of());
        int salt1 = i32(bytes, 16), salt2 = i32(bytes, 20);
        int[] running = {0, 0};
        checksum(bytes, 0, 24, running, bigEndianChecksum); // seeds the rolling checksum from the WAL header's own first 24 bytes, as frame 1's starting value

        List<Map<String,Object>> rows = new ArrayList<>();
        int framesSeen = 0, framesChecksumValid = 0, pagesCarved = 0, offset = 32;
        Map<Integer,Integer> lastFrameForPage = new LinkedHashMap<>();
        List<int[]> frameOffsets = new ArrayList<>(); // [page number, byteOffsetOfPageData, frameIndex]
        while (offset + 24 + pageSize <= bytes.length && framesSeen < MAX_FRAMES) {
            int pageNumber = i32(bytes, offset), commitSize = i32(bytes, offset + 4);
            int frameSalt1 = i32(bytes, offset + 8), frameSalt2 = i32(bytes, offset + 12);
            int checksum1 = i32(bytes, offset + 16), checksum2 = i32(bytes, offset + 20);
            framesSeen++;
            if (frameSalt1 != salt1 || frameSalt2 != salt2) { offset += 24 + pageSize; continue; } // frame belongs to a prior/aborted WAL generation
            int[] next = {running[0], running[1]};
            checksum(bytes, offset, 8, next, bigEndianChecksum);
            checksum(bytes, offset + 24, pageSize, next, bigEndianChecksum);
            running = next;
            boolean valid = checksum1 == running[0] && checksum2 == running[1];
            if (valid) {
                framesChecksumValid++;
                frameOffsets.add(new int[]{pageNumber, offset + 24, frameOffsets.size()});
                lastFrameForPage.put(pageNumber, frameOffsets.size() - 1); // later frames overwrite earlier ones for the same page, exactly like a real checkpoint would
            }
            offset += 24 + pageSize;
        }

        Integer page1Frame = lastFrameForPage.get(1);
        int rootPage = page1Frame != null
            ? findRootPageInPageBytes(Arrays.copyOfRange(bytes, frameOffsets.get(page1Frame)[1], frameOffsets.get(page1Frame)[1] + pageSize), table)
            : findRootPage(dbFile, table);
        if (rootPage <= 0) return Map.of("recovered", false, "reason", "TABLE_ROOT_PAGE_NOT_FOUND", "schemaSource", page1Frame != null ? "LATEST_WAL_FRAME_FOR_PAGE_1" : "MAIN_DB_FILE", "framesSeen", framesSeen, "framesChecksumValid", framesChecksumValid, "rows", List.of());

        for (int[] entry : frameOffsets) {
            int pageNumber = entry[0], pageOffset = entry[1], frameIndex = entry[2];
            if (pageNumber != rootPage) continue; // only this table's own page; sqlite_master and other tables' pages are explicitly excluded
            int pageHeaderOffset = pageOffset + (pageNumber == 1 ? 100 : 0); // page 1 carries a 100-byte DB header before its own page header
            if (pageHeaderOffset >= bytes.length) continue;
            int type = bytes[pageHeaderOffset] & 0xFF;
            if (type != 0x0D) continue; // table-leaf pages only; an interior root (table spans multiple pages) is not walked, by design - see rootPageType below
            pagesCarved++;
            int cellCount = u16(bytes, pageHeaderOffset + 3);
            int pointerArray = pageHeaderOffset + 8;
            for (int i = 0; i < cellCount && rows.size() < limit; i++) {
                int cellPointer = pageOffset + u16(bytes, pointerArray + i * 2);
                if (cellPointer < 0 || cellPointer >= bytes.length) continue;
                long[] cursor = {cellPointer};
                long payloadLength = readVarint(bytes, cursor);
                long rowid = readVarint(bytes, cursor);
                int headerStart = (int) cursor[0];
                int usable = pageSize; // no reserved-space region assumed; a mismatch only affects the overflow threshold, which is already conservative
                long maxLocal = usable - 35;
                boolean overflow = payloadLength > maxLocal;
                Map<String,Object> row = decodeRecord(bytes, headerStart, overflow ? (int) maxLocal : (int) payloadLength, columns);
                row.put("rowid", Long.toString(rowid));
                // An INTEGER PRIMARY KEY column is a rowid alias: SQLite always stores it as a record NULL and reads it back from the rowid instead.
                if (!rowidAliasColumn.isEmpty() && row.get(rowidAliasColumn) == null) row.put(rowidAliasColumn, Math.abs(rowid) <= 9007199254740992L ? (Object) (double) rowid : Long.toString(rowid));
                row.put("walFrameIndex", frameIndex);
                row.put("walPageNumber", pageNumber);
                row.put("latestFrameForThisPage", lastFrameForPage.get(pageNumber) == frameIndex);
                row.put("payloadOverflowed", overflow);
                if (overflow) row.put("note", "Payload spans an overflow page this carver does not follow; shown columns may be truncated or missing.");
                rows.add(Collections.unmodifiableMap(row));
            }
            if (rows.size() >= limit) break;
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("recovered", !rows.isEmpty());
        out.put("table", table);
        out.put("rootPage", rootPage);
        out.put("pageSize", pageSize);
        out.put("framesSeen", framesSeen);
        out.put("framesChecksumValid", framesChecksumValid);
        out.put("pagesCarvedAsTableLeaf", pagesCarved);
        out.put("rows", List.copyOf(rows));
        out.put("truncated", rows.size() >= limit);
        out.put("semantics", "Raw WAL-frame carving of table-leaf pages, independent of the live connection. A row here may already be reflected in the main table (not actually deleted), may be a genuinely recoverable deleted/overwritten version, or may be stale from an aborted transaction; cross-check walFrameIndex ordering and latestFrameForThisPage. Column mapping is positional (declared column order), not name-verified per cell.");
        return Collections.unmodifiableMap(out);
    }

    /** Reads the schema straight from the main .db file (not the WAL): used only when page 1 never appears in this WAL generation's frames. */
    private static int findRootPage(File dbFile, String table) throws Exception {
        if (!dbFile.isFile() || dbFile.length() < 100) return -1;
        byte[] page1;
        try (RandomAccessFile in = new RandomAccessFile(dbFile, "r")) {
            byte[] header = new byte[100];
            in.readFully(header);
            int pageSize = u16(header, 16);
            if (pageSize == 1) pageSize = 65536;
            if (pageSize < 512 || Integer.bitCount(pageSize) != 1) return -1;
            page1 = new byte[pageSize];
            in.seek(0);
            in.readFully(page1);
        }
        return findRootPageInPageBytes(page1, table);
    }
    /** page1 is the full raw page-1 image (including its 100-byte DB-header prefix), from either the main file or the latest WAL frame for page 1. */
    private static int findRootPageInPageBytes(byte[] page1, String table) {
        int pageHeaderOffset = 100;
        if (pageHeaderOffset >= page1.length || (page1[pageHeaderOffset] & 0xFF) != 0x0D) return -1; // sqlite_master spanning multiple pages (interior root) is not walked
        int cellCount = u16(page1, pageHeaderOffset + 3), pointerArray = pageHeaderOffset + 8;
        List<String> schemaColumns = List.of("type", "name", "tbl_name", "rootpage", "sql");
        for (int i = 0; i < cellCount; i++) {
            int cellPointer = u16(page1, pointerArray + i * 2);
            if (cellPointer < 0 || cellPointer >= page1.length) continue;
            long[] cursor = {cellPointer};
            readVarint(page1, cursor); // payload length, unused here (sqlite_master rows never overflow in practice)
            readVarint(page1, cursor); // rowid, unused
            Map<String,Object> row = decodeRecord(page1, (int) cursor[0], page1.length - (int) cursor[0], schemaColumns);
            if ("table".equals(row.get("type")) && table.equals(row.get("name"))) {
                Object rootpage = row.get("rootpage");
                return rootpage instanceof Double d ? d.intValue() : -1;
            }
        }
        return -1;
    }
    private static Map<String,Object> decodeRecord(byte[] bytes, int start, int payloadLength, List<String> columns) {
        Map<String,Object> row = new LinkedHashMap<>();
        if (start < 0 || start >= bytes.length || payloadLength <= 0) { for (String c : columns) row.put(c, null); row.put("decodeError", "RECORD_OUT_OF_BOUNDS"); return row; }
        long[] cursor = {start};
        long headerLength = readVarint(bytes, cursor);
        int headerEnd = start + (int) headerLength;
        if (headerEnd > bytes.length || headerEnd < start) { for (String c : columns) row.put(c, null); row.put("decodeError", "RECORD_HEADER_OUT_OF_BOUNDS"); return row; }
        List<Long> serialTypes = new ArrayList<>();
        while (cursor[0] < headerEnd) serialTypes.add(readVarint(bytes, cursor));
        long valueOffset = headerEnd;
        for (int i = 0; i < serialTypes.size() && i < columns.size(); i++) {
            long type = serialTypes.get(i);
            int size = serialSize(type);
            Object value;
            if (valueOffset + size > start + payloadLength || valueOffset + size > bytes.length) value = null;
            else value = decodeValue(bytes, (int) valueOffset, type, size);
            row.put(columns.get(i), value);
            valueOffset += size;
        }
        for (int i = serialTypes.size(); i < columns.size(); i++) row.put(columns.get(i), null); // fewer stored columns than declared: SQLite defaults these to NULL
        return row;
    }

    private static int serialSize(long type) {
        if (type >= 12) return (int) ((type - (type % 2 == 0 ? 12 : 13)) / 2);
        switch ((int) type) {
            case 0: case 8: case 9: case 10: case 11: return 0;
            case 1: return 1; case 2: return 2; case 3: return 3; case 4: return 4; case 5: return 6; case 6: case 7: return 8;
            default: return 0;
        }
    }
    private static Object decodeValue(byte[] bytes, int offset, long type, int size) {
        if (type == 0) return null;
        if (type == 8) return 0.0; if (type == 9) return 1.0;
        if (type >= 12 && type % 2 == 0) return "0x" + hex(bytes, offset, size); // blob
        if (type >= 13) return new String(bytes, offset, size, StandardCharsets.UTF_8); // text
        if (type == 7) return Double.longBitsToDouble(signedBE(bytes, offset, 8));
        long raw = signedBE(bytes, offset, size);
        return Math.abs(raw) <= 9007199254740992L ? (Object) (double) raw : Long.toString(raw);
    }
    private static String hex(byte[] bytes, int offset, int length) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < length; i++) sb.append(String.format("%02x", bytes[offset + i])); return sb.toString(); }
    private static long signedBE(byte[] bytes, int offset, int length) {
        long value = (bytes[offset] & 0x80) != 0 ? -1L : 0L;
        for (int i = 0; i < length; i++) value = (value << 8) | (bytes[offset + i] & 0xFFL);
        return value;
    }
    private static long readVarint(byte[] bytes, long[] cursor) {
        long value = 0; int pos = (int) cursor[0];
        for (int i = 0; i < 8; i++) {
            byte b = bytes[pos++];
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) { cursor[0] = pos; return value; }
        }
        value = (value << 8) | (bytes[pos++] & 0xFF);
        cursor[0] = pos; return value;
    }
    private static int i32(byte[] bytes, int offset) { return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset+1] & 0xFF) << 16) | ((bytes[offset+2] & 0xFF) << 8) | (bytes[offset+3] & 0xFF); }
    private static int u16(byte[] bytes, int offset) { return ((bytes[offset] & 0xFF) << 8) | (bytes[offset+1] & 0xFF); }

    /** SQLite's WAL checksum: naive 32-bit rolling sum over big-endian (or little-endian) uint32 pairs, per the documented file-format algorithm. */
    private static void checksum(byte[] bytes, int offset, int length, int[] running, boolean bigEndian) {
        int s1 = running[0], s2 = running[1];
        for (int i = 0; i < length; i += 8) {
            int v1 = bigEndian ? i32(bytes, offset + i) : Integer.reverseBytes(i32(bytes, offset + i));
            int v2 = bigEndian ? i32(bytes, offset + i + 4) : Integer.reverseBytes(i32(bytes, offset + i + 4));
            s1 = s1 + v1 + s2;
            s2 = s2 + v2 + s1;
        }
        running[0] = s1; running[1] = s2;
    }
}
