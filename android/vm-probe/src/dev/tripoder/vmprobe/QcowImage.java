package dev.tripoder.vmprobe;

import java.io.*;
import java.nio.*;

/** Bounded access to allocated clusters in this experiment's fixed QCOW2 image. */
final class QcowImage implements AutoCloseable {
    private static final long OFFSET_MASK = 0x00fffffffffffe00L;
    private final RandomAccessFile file;
    private final int clusterSize, l1Count;
    private final long l1Offset, virtualSize;
    QcowImage(File path, boolean writable) throws IOException {
        file = new RandomAccessFile(path, writable ? "rw" : "r");
        try {
            ByteBuffer h = ByteBuffer.wrap(physicalRead(0, 104)).order(ByteOrder.BIG_ENDIAN);
            require(h.getInt(0) == 0x514649fb && h.getInt(4) == 3, "QCOW2 v3 required");
            require(h.getLong(8) == 0 && h.getInt(32) == 0 && h.getInt(60) == 0,
                "Backing files, encryption and snapshots are not supported");
            require(h.getLong(72) == 0, "QCOW2 must be clean and use the fixed format");
            int bits = h.getInt(20);
            require(bits == 16, "Unexpected cluster size");
            clusterSize = 1 << bits; virtualSize = h.getLong(24);
            l1Count = h.getInt(36); l1Offset = h.getLong(40);
            require(l1Count > 0 && l1Count < 100000, "Invalid L1 table");
        } catch (IOException | RuntimeException error) { file.close(); throw error; }
    }
    byte[] read(long offset, int length) throws IOException {
        byte[] data = new byte[length]; transfer(offset, data, false); return data;
    }
    void write(long offset, byte[] data) throws IOException { transfer(offset, data, true); }
    void sync() throws IOException { file.getFD().sync(); }
    private void transfer(long offset, byte[] data, boolean writing) throws IOException {
        require(offset >= 0 && offset <= virtualSize - data.length, "Virtual access out of bounds");
        for (int done = 0; done < data.length;) {
            long current = offset + done;
            int within = (int)(current % clusterSize);
            int amount = Math.min(data.length - done, clusterSize - within);
            long physical = translate(current, writing) + within;
            require(physical >= 0 && physical <= file.length() - amount, "Physical access out of bounds");
            file.seek(physical);
            if (writing) file.write(data, done, amount); else file.readFully(data, done, amount);
            done += amount;
        }
    }
    private long translate(long offset, boolean writing) throws IOException {
        long cluster = offset / clusterSize, l1Index = cluster / (clusterSize / 8);
        require(l1Index < l1Count, "L1 index out of bounds");
        long l1 = number(l1Offset + l1Index * 8);
        require((l1 & OFFSET_MASK) != 0, "Unallocated L1 entry");
        long l2 = number((l1 & OFFSET_MASK) + (cluster % (clusterSize / 8)) * 8);
        require((l2 & (1L << 62)) == 0 && (l2 & 1) == 0 && (l2 & OFFSET_MASK) != 0,
            "Only allocated uncompressed nonzero clusters are supported");
        if (writing) require(l1 < 0 && l2 < 0, "Refusing to modify shared QCOW2 clusters");
        return l2 & OFFSET_MASK;
    }
    private long number(long offset) throws IOException {
        return ByteBuffer.wrap(physicalRead(offset, 8)).order(ByteOrder.BIG_ENDIAN).getLong();
    }
    private byte[] physicalRead(long offset, int length) throws IOException {
        require(offset >= 0 && offset <= file.length() - length, "Invalid QCOW2 metadata");
        byte[] data = new byte[length]; file.seek(offset); file.readFully(data); return data;
    }
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    public void close() throws IOException { file.close(); }
}
