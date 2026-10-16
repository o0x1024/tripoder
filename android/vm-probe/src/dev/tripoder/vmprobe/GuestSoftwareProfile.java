package dev.tripoder.vmprobe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Separate slow CPU execution from Android's low-RAM window capability policy. */
final class GuestSoftwareProfile {
    static final String NORMAL_RAM = "b833b7224aca0517c538b1c77ac8857a460f63f250f9fd978089255a199e5291";
    private static final String ORIGINAL = "7ede9e8e094da7d60cb8d2402c042a1430f5721aded7b2c5cc5b6002a49c7d8c";
    private static final String INODE_SHA = "4346bdf101428a1e5fcb31bf52ba1e05c02003399090fcdc9a88118432413f1f";
    private static final long INODE = 3752897024L, CONTENT = 3720466432L;
    private static final int LENGTH = 1416;

    static boolean isPrepared(QcowImage image) throws Exception {
        // Locked vendor/ext4 inode 283: one extent, same size, mode and SELinux label.
        QcowImage.require(GuestBootConfig.sha256(image.read(INODE, 256)).equals(INODE_SHA),
            "Unrecognized guest software profile inode");
        String hash = GuestBootConfig.sha256(image.read(CONTENT, LENGTH));
        QcowImage.require(hash.equals(ORIGINAL) || hash.equals(NORMAL_RAM),
            "Unrecognized guest software profile content");
        return hash.equals(NORMAL_RAM);
    }

    static void apply(QcowImage image) throws Exception {
        if (isPrepared(image)) return;
        String text = "on early-init && property:ro.boot.nobootanim=1\n"
            + "    setprop debug.sf.nobootanimation 1\n\n"
            + "# Tripoder: software CPU timing is independent of RAM/window capabilities.\n"
            + "on early-init && property:ro.boot.low_perf=1\n"
            + "    setprop ro.hw_timeout_multiplier 50\n"
            + "    setprop ro.setupwizard.mode DISABLED\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] patched = new byte[LENGTH];
        Arrays.fill(patched, (byte)'.');
        System.arraycopy(body, 0, patched, 0, body.length);
        // Keep file size and all filesystem metadata unchanged with a comment.
        patched[body.length] = '#';
        patched[LENGTH - 1] = '\n';
        QcowImage.require(GuestBootConfig.sha256(patched).equals(NORMAL_RAM), "Invalid software profile patch");
        image.write(CONTENT, patched);
        QcowImage.require(isPrepared(image), "Software profile readback failed");
    }
}
