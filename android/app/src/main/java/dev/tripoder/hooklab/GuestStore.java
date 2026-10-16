package dev.tripoder.hooklab;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Legacy owned-fixture experiment only; never the new runtime installation authority. */
final class GuestStore {
    static final String PACKAGE = "dev.tripoder.fixture";
    static final String CLASS = PACKAGE + ".GuestProbe";
    static final int VERSION = 2;
    private final Context context;
    private final File root;
    final String expectedHash;

    GuestStore(Context context) throws Exception {
        this.context = context;
        try (InputStream input = context.getAssets().open("guest-fixture.sha256")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[256]; int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            expectedHash = new String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim();
        }
        if (!expectedHash.matches("[a-f0-9]{64}")) throw new IOException("Invalid fixture manifest");
        root = new File(context.getFilesDir(), "guests/" + expectedHash);
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("LEGACY_FIXTURE_DIRECTORY_SYMLINK");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create guest directory");
    }

    /** Called only by the explicit legacy experiment. A missing recorded APK is corruption, not an initialization request. */
    synchronized File initializeOrVerifyLegacyFixture() throws Exception {
        File apk = new File(root, "base.apk");
        File marker = new File(root, ".legacy-fixture-initialized");
        boolean initialized = Files.exists(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (initialized) {
            if (!Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(marker.toPath()) != 64
                || !expectedHash.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII)))
                throw new IOException("LEGACY_FIXTURE_INITIALIZATION_RECORD_CORRUPT");
        } else if (!Files.notExists(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("LEGACY_FIXTURE_INITIALIZATION_RECORD_UNREADABLE");
        if (Files.exists(apk.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(apk.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("LEGACY_FIXTURE_CORRUPT: expected a regular APK");
            if (!expectedHash.equals(sha256(apk))) throw new IOException("LEGACY_FIXTURE_CORRUPT: APK digest mismatch");
            assertReadOnly(apk);
            if (!initialized) recordLegacyInitialization();
            return apk;
        }
        if (!Files.notExists(apk.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("LEGACY_FIXTURE_UNREADABLE");
        if (initialized || Files.exists(new File(root, "lib").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("LEGACY_FIXTURE_CORRUPT: initialized APK is missing");
        try (InputStream input = context.getAssets().open("guest-fixture.apk")) { return importApk(input); }
    }

    /** Local experiment receipt only; it is not a new-runtime install record. */
    private void recordLegacyInitialization() throws Exception {
        File marker = new File(root, ".legacy-fixture-initialized");
        if (Files.isSymbolicLink(marker.toPath())) throw new IOException("LEGACY_FIXTURE_INITIALIZATION_RECORD_SYMLINK");
        ProjectStore.atomic(marker, expectedHash);
    }

    synchronized File importApk(InputStream input) throws Exception {
        File temporary = new File(root, "incoming-" + UUID.randomUUID() + ".apk");
        try {
            writeReadOnly(input, temporary, 32L * 1024 * 1024);
            if (!expectedHash.equals(sha256(temporary))) throw new IllegalArgumentException("APK_DIGEST_MISMATCH: 仅支持本版本自有测试 APK");
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null || !PACKAGE.equals(info.packageName) || info.getLongVersionCode() != VERSION || info.signingInfo == null)
                throw new IllegalArgumentException("FIXTURE_IDENTITY_MISMATCH");
            File apk = new File(root, "base.apk");
            Files.move(temporary.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            recordLegacyInitialization();
            return apk;
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    File extractLibrary(File apk) throws Exception {
        File directory = new File(root, "lib");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create library directory");
        File library = new File(directory, "libguestfixture.so");
        File temporary = new File(directory, "extract-" + UUID.randomUUID() + ".so");
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("lib/arm64-v8a/libguestfixture.so");
            if (entry == null) throw new IOException("ARM64 fixture library missing");
            try (InputStream input = zip.getInputStream(entry)) { writeReadOnly(input, temporary, 16L * 1024 * 1024); }
            Files.move(temporary.toPath(), library.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return library;
    }

    File dataDirectory() throws IOException {
        File directory = new File(root, "data");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create data directory");
        return directory;
    }

    private static void writeReadOnly(InputStream input, File output, long limit) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            // Android 14+ requires dynamic code to be read-only before any bytes are written.
            Os.chmod(output.getAbsolutePath(), 0400);
            byte[] buffer = new byte[65536]; int read; long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("Payload size limit exceeded");
                stream.write(buffer, 0, read);
            }
            stream.getFD().sync();
        }
        assertReadOnly(output);
    }

    static void assertReadOnly(File file) throws Exception {
        if ((Os.stat(file.getAbsolutePath()).st_mode & 0222) != 0) throw new IOException("Dynamic code must be read-only");
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
