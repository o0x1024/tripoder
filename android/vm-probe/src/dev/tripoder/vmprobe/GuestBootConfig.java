package dev.tripoder.vmprobe;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Audited GRUB and software profile edits, committed together before QEMU opens the disk. */
final class GuestBootConfig {
    static final String ORIGINAL = "13996f57c49041470ba66f7dfb91f3907e86577ec2d30eeb92deddc5740c5625";
    static final String LOGGING = "7035cab200f4a7324f50966024fbacf707dfe148f48b337aa6127ae755cff63a";
    static final String SOFTWARE_EMULATOR = "562c6c78809200821514f298738df1a1816fec6e42f28014551da3135e8b4761";
    private static final long ENTRY = 8112448, CONTENT = 30003712, FAT = 1064960;
    private static final int FIRST_CLUSTER = 55419, CLUSTERS = 53, CAPACITY = CLUSTERS * 512;
    static String prepareSoftwareEmulator(File disk) throws Exception {
        byte[] original;
        try (QcowImage image = new QcowImage(disk, false)) {
            original = readConfig(image);
            String hash = sha256(original);
            boolean profileReady = GuestSoftwareProfile.isPrepared(image);
            if (hash.equals(SOFTWARE_EMULATOR) && profileReady) return SOFTWARE_EMULATOR;
            // Only migrate the locked image or our preceding, hash-identified diagnostic edit.
            QcowImage.require(hash.equals(ORIGINAL) || hash.equals(LOGGING) || hash.equals(SOFTWARE_EMULATOR),
                "Unrecognized guest GRUB configuration");
        }
        String text = new String(original, StandardCharsets.UTF_8);
        String desired = "\tboot_android androidboot.seriallogging.logcat_buffer=all androidboot.low_perf=1\n";
        String before = sha256(original).equals(ORIGINAL) ? "\tboot_android\n"
            : sha256(original).equals(LOGGING) ? "\tboot_android androidboot.seriallogging.logcat_buffer=all\n" : desired;
        QcowImage.require(text.indexOf(before) == text.lastIndexOf(before) && text.contains(before),
            "Expected exactly one normal Android boot entry");
        byte[] patched = text.replace(before, desired).getBytes(StandardCharsets.UTF_8);
        QcowImage.require(patched.length <= CAPACITY && sha256(patched).equals(SOFTWARE_EMULATOR), "Invalid GRUB patch");
        // Commit the complete image atomically. An interrupted preparation leaves the original intact.
        Path incoming = new File(disk.getParentFile(), disk.getName() + ".boot-config-incoming").toPath();
        Files.copy(disk.toPath(), incoming, StandardCopyOption.REPLACE_EXISTING);
        try {
            try (QcowImage image = new QcowImage(incoming.toFile(), true)) {
                QcowImage.require(Arrays.equals(readConfig(image), original), "Disk changed during preparation");
                image.write(CONTENT, patched);
                image.write(ENTRY + 28, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(patched.length).array());
                QcowImage.require(sha256(readConfig(image)).equals(SOFTWARE_EMULATOR), "GRUB patch readback failed");
                GuestSoftwareProfile.apply(image);
                image.sync();
            }
            Files.move(incoming, disk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(incoming); }
        return SOFTWARE_EMULATOR;
    }
    private static byte[] readConfig(QcowImage image) throws IOException {
        byte[] entry = image.read(ENTRY, 32);
        QcowImage.require(Arrays.equals(Arrays.copyOf(entry, 11), "GRUB    CFG".getBytes(StandardCharsets.US_ASCII))
            && (entry[11] & 0x18) == 0, "Unexpected GRUB directory entry");
        ByteBuffer b = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
        int first = ((b.getShort(20) & 65535) << 16) | (b.getShort(26) & 65535);
        int size = b.getInt(28);
        QcowImage.require(first == FIRST_CLUSTER && (size == 26717 || size == 26761 || size == 26784), "Unexpected GRUB allocation");
        for (int i = 0; i < CLUSTERS; i++) {
            int next = ByteBuffer.wrap(image.read(FAT + (FIRST_CLUSTER + i) * 4L, 4))
                .order(ByteOrder.LITTLE_ENDIAN).getInt() & 0x0fffffff;
            QcowImage.require(i == CLUSTERS - 1 ? next >= 0x0ffffff8 : next == FIRST_CLUSTER + i + 1,
                "Unexpected GRUB FAT chain");
        }
        return image.read(CONTENT, size);
    }
    static String sha256(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
