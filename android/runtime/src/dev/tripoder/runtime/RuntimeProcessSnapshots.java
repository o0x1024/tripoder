package dev.tripoder.runtime;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process HPROF heap dump and main-thread-frozen atomic SQLite snapshot for
 * {@code process_snapshot(action=heap_dump|freeze_export)}.
 *
 * <p>All outputs are written beneath {@code files/snapshots/} inside the guest installation's
 * data root so they can be exported immediately with {@code file_export} ({@code root="data"})
 * and verified end-to-end with SHA-256.
 */
public final class RuntimeProcessSnapshots {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private RuntimeProcessSnapshots() {}

    public static synchronized Map<String, Object> heapDump() {
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine == null || engine.data == null) {
            throw new IllegalStateException("SNAPSHOT_RUNTIME_NOT_READY");
        }
        String snapshotId = "heap-" + System.currentTimeMillis() + "-" + NEXT_ID.getAndIncrement();
        File snapshotsDir = new File(engine.data, "files/snapshots");
        if (!snapshotsDir.exists() && !snapshotsDir.mkdirs()) {
            throw new IllegalStateException("SNAPSHOT_DIR_CREATE_FAILED");
        }
        String fileName = snapshotId + ".hprof";
        File outFile = new File(snapshotsDir, fileName);
        long startNs = System.nanoTime();
        try {
            Debug.dumpHprofData(outFile.getAbsolutePath());
        } catch (Exception error) {
            throw new IllegalStateException("HPROF_DUMP_FAILED:" + error.getMessage(), error);
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        String sha256 = sha256Hex(outFile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshotId);
        out.put("kind", "heap_dump");
        out.put("relativePath", "files/snapshots/" + fileName);
        out.put("sizeBytes", outFile.length());
        out.put("sha256", sha256);
        out.put("durationMs", durationMs);
        out.put("createdAtIso", Instant.now().toString());
        return Collections.unmodifiableMap(out);
    }

    public static synchronized Map<String, Object> freezeExport(String databaseName) {
        if (databaseName == null || !databaseName.matches("^[A-Za-z0-9_.-]{1,128}$") || databaseName.contains("..")) {
            throw new IllegalArgumentException("SNAPSHOT_DATABASE_NAME_INVALID");
        }
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine == null || engine.data == null) {
            throw new IllegalStateException("SNAPSHOT_RUNTIME_NOT_READY");
        }
        File dbDir = new File(engine.data, "databases");
        File mainDb = new File(dbDir, databaseName);
        if (!mainDb.isFile()) {
            throw new IllegalArgumentException("SNAPSHOT_DATABASE_NOT_FOUND:" + databaseName);
        }
        String snapshotId = "freeze-" + System.currentTimeMillis() + "-" + NEXT_ID.getAndIncrement();
        File targetDir = new File(engine.data, "files/snapshots/" + snapshotId);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IllegalStateException("SNAPSHOT_DIR_CREATE_FAILED");
        }

        long startNs = System.nanoTime();
        // Run the file copy inside a main-thread barrier so the UI/main thread is parked
        // at a deterministic transaction boundary while main DB + -wal/-shm/-journal are copied.
        List<Map<String, Object>> copiedFiles = runOnMainBarrier(() -> {
            List<Map<String, Object>> files = new ArrayList<>();
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                File src = new File(dbDir, databaseName + suffix);
                if (!src.isFile()) continue;
                File dst = new File(targetDir, databaseName + suffix);
                copyAndHash(src, dst, "files/snapshots/" + snapshotId + "/" + dst.getName(), files);
            }
            return files;
        });
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;

        MessageDigest manifestDigest = sha256Digest();
        long totalBytes = 0L;
        for (Map<String, Object> entry : copiedFiles) {
            totalBytes += ((Number) entry.get("sizeBytes")).longValue();
            String line = entry.get("name") + ":" + entry.get("sizeBytes") + ":" + entry.get("sha256") + "\n";
            manifestDigest.update(line.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshotId);
        out.put("kind", "freeze_export");
        out.put("databaseName", databaseName);
        out.put("relativeDirectory", "files/snapshots/" + snapshotId);
        out.put("mainThreadParked", true);
        out.put("totalBytes", totalBytes);
        out.put("manifestSha256", toHex(manifestDigest.digest()));
        out.put("files", copiedFiles);
        out.put("durationMs", durationMs);
        out.put("createdAtIso", Instant.now().toString());
        return Collections.unmodifiableMap(out);
    }

    private static <T> T runOnMainBarrier(Callable<T> task) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return task.call();
            }
            FutureTask<T> future = new FutureTask<>(task);
            Handler.createAsync(Looper.getMainLooper()).post(future);
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("FREEZE_BARRIER_FAILED:" + error.getMessage(), error);
        }
    }

    private static void copyAndHash(File src, File dst, String relativePath, List<Map<String, Object>> outList)
            throws Exception {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] buffer = new byte[65536];
        try (FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                total += n;
            }
            out.getFD().sync();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", dst.getName());
        item.put("relativePath", relativePath);
        item.put("sizeBytes", total);
        item.put("sha256", toHex(digest.digest()));
        outList.add(Collections.unmodifiableMap(item));
    }

    private static String sha256Hex(File file) {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[65536];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        } catch (Exception error) {
            throw new IllegalStateException("SNAPSHOT_HASH_FAILED", error);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
