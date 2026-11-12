package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * One identity record, printed once per guest process (logcat tag TripoderIdentitySnapshot).
 *
 * Why this exists: a distribution check does not ask one question, it asks a set of correlated ones -
 * package name, uid owning the process, the executable's path, the installer that delivered it, and the
 * signer. Any single one of those answered from the container instead of from the platform makes the
 * whole set inconsistent, and the app reports "not an official build" without ever naming the field.
 * Printing the set side by side (guest vs. the values the device holds for the same package) turns that
 * verdict into a diff: the field that disagrees is the defect.
 *
 * Values here are the guest's own install metadata, not user data: no contact, account or device id is
 * read, and nothing is written anywhere but logcat.
 */
final class IdentitySnapshot {
    private static final String TAG = "TripoderIdentitySnapshot";
    private static long auditSequenceRead;

    static void log(RuntimeEngine engine) {
        try {
            StringBuilder out = new StringBuilder(512);
            out.append("package=").append(engine.packageName);
            out.append(" uid=").append(Process.myUid());
            out.append(" cmdline=").append(cmdline());
            out.append(" procUid=").append(procUid());
            ApplicationInfo info = GuestPackageManager.copyApplicationInfo(engine.info, true);
            if (info != null) {
                out.append(" appUid=").append(info.uid);
                out.append(" versionName=").append(engine.packageArchive == null ? null : engine.packageArchive.versionName);
                out.append(" sourceDir=").append(info.sourceDir);
                out.append(" dataDir=").append(info.dataDir);
                out.append(" nativeLibDir=").append(info.nativeLibraryDir);
                out.append(" enabled=").append(info.enabled);
                out.append(" systemApp=").append((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                out.append(" updatedSystemApp=").append((info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
            }
            out.append(" appRecord=").append(applicationInfoRecord(info));
            out.append(" signer=").append(signer(engine));
            out.append(" installer=").append(installer(engine));
            out.append(" installSource=").append(installSource(engine));
            out.append(" platformAnswer=").append(platformAnswer(engine));
            android.util.Log.i(TAG, out.toString());
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "snapshot unavailable: " + unreadable);
        }
    }

    /** The signer the guest's own PackageManager reports, which is what an integrity check compares. */
    private static String signer(RuntimeEngine engine) {
        try {
            return signers(engine.packageArchive == null ? null : engine.packageArchive.signingInfo);
        } catch (Throwable unavailable) {
            return "error:" + unavailable.getClass().getSimpleName();
        }
    }

    /** SHA-256 of every APK content signer, the form an integrity check compares. */
    private static String signers(android.content.pm.SigningInfo signing) throws Exception {
        if (signing == null) return "none";
        StringBuilder out = new StringBuilder();
        for (Signature signature : signing.getApkContentsSigners()) {
            if (out.length() > 0) out.append(',');
            out.append(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String installer(RuntimeEngine engine) {
        try {
            return String.valueOf(engine.host.getPackageManager().getInstallerPackageName(engine.packageName));
        } catch (Throwable unavailable) {
            return "error:" + unavailable.getClass().getSimpleName();
        }
    }

    private static String installSource(RuntimeEngine engine) {
        try {
            InstallSourceInfo source = engine.host.getPackageManager().getInstallSourceInfo(engine.packageName);
            return "{installer=" + source.getInstallingPackageName()
                    + ",initiating=" + source.getInitiatingPackageName()
                    + ",originating=" + source.getOriginatingPackageName() + "}";
        } catch (Throwable unavailable) {
            return "error:" + unavailable.getClass().getSimpleName();
        }
    }

    /** What the platform says about this package, to compare against what the guest is told. */
    private static String platformAnswer(RuntimeEngine engine) {
        try {
            android.content.pm.PackageInfo real = engine.host.getPackageManager()
                    .getPackageInfo(engine.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            StringBuilder out = new StringBuilder("{uid=" + real.applicationInfo.uid
                    + ",appRecord=" + applicationInfoRecord(real.applicationInfo)
                    + ",versionCode=" + real.getLongVersionCode() + ",signer=");
            out.append(signers(real.signingInfo));
            return out.append('}').toString();
        } catch (Throwable unavailable) {
            return "error:" + unavailable.getClass().getSimpleName();
        }
    }

    /**
     * The same install facts, read through the object the guest actually holds, printed next to what the platform
     * holds for the package name.
     *
     * <p>Why this exists as a second record: every earlier round of this defect concluded "fixed" from
     * {@link #platformAnswer} alone, which reports the truth about the device's own install and says nothing about
     * what the app is told. The facade answer is the one an app reads, and it is taken through the real object on
     * purpose: only the interception path shows a branch that shadows another (see contract R5), which a direct read
     * of the identity helper behind it would hide.
     *
     * <p>Install metadata only: no account, contact or device identifier is read, and nothing is written but logcat.
     */
    static void logFacade(RuntimeEngine engine, android.content.Context guest) {
        try {
            PackageManager facade = guest == null ? null : guest.getPackageManager();
            if (facade == null) {
                android.util.Log.w(TAG, "facade snapshot unavailable: no guest package manager");
                return;
            }
            String own = guest.getPackageName();
            String host = engine.host.getPackageName();
            StringBuilder out = new StringBuilder(640);
            StringBuilder leak = new StringBuilder();
            out.append("facadeAnswer={package=").append(own);
            try {
                out.append(",uid=").append(facade.getPackageUid(own, 0));
            } catch (Throwable unreadable) {
                out.append(",uid=error:").append(unreadable.getClass().getSimpleName());
            }
            String sourceDir = null;
            try {
                android.content.pm.PackageInfo info =
                        facade.getPackageInfo(own, PackageManager.GET_SIGNING_CERTIFICATES);
                out.append(",versionCode=").append(info.getLongVersionCode());
                out.append(",versionName=").append(info.versionName);
                out.append(",signer=").append(signers(info.signingInfo));
                if (info.applicationInfo != null) {
                    sourceDir = info.applicationInfo.sourceDir;
                    out.append(",sourceDir=").append(sourceDir);
                }
            } catch (Throwable unreadable) {
                out.append(",packageInfo=error:").append(unreadable.getClass().getSimpleName());
            }
            try {
                String installer = facade.getInstallerPackageName(own);
                out.append(",installer=").append(installer);
                if (host.equals(installer)) mark(leak, "installer");
            } catch (Throwable unreadable) {
                out.append(",installer=error:").append(unreadable.getClass().getSimpleName());
            }
            try {
                InstallSourceInfo source = facade.getInstallSourceInfo(own);
                String installer = source == null ? null : source.getInstallingPackageName();
                String initiating = source == null ? null : source.getInitiatingPackageName();
                String originating = source == null ? null : source.getOriginatingPackageName();
                out.append(",installSource={installer=").append(installer)
                        .append(",initiating=").append(initiating)
                        .append(",originating=").append(originating).append('}');
                if ((host.equals(installer) || host.equals(initiating) || host.equals(originating)))
                    mark(leak, "installSource");
            } catch (Throwable unreadable) {
                out.append(",installSource=error:").append(unreadable.getClass().getSimpleName());
            }
            try {
                String[] owned = facade.getPackagesForUid(Process.myUid());
                out.append(",uidPackages=").append(java.util.Arrays.toString(owned));
                if (owned != null) for (String name : owned) {
                    if (host.equals(name)) { mark(leak, "uidPackages"); break; }
                }
            } catch (Throwable unreadable) {
                out.append(",uidPackages=error:").append(unreadable.getClass().getSimpleName());
            }
            out.append('}');
            // Contract R1: the host package must not appear in any facade answer. The copy living under the
            // container's own data directory is a registered divergence (contract §6), not a leak.
            ApplicationInfo hostInfo = engine.host.getApplicationInfo();
            if (sourceDir != null && hostInfo != null && hostInfo.dataDir != null && sourceDir.startsWith(hostInfo.dataDir))
                out.append(" sourceDirForm=container-private");
            out.append(" hostLeak=").append(leak.length() == 0 ? "none" : leak.toString());
            // Measured 2026-11-12: the facade reports the device's own /data/app path, not the container copy's,
            // so the registered divergence is that the metadata projects the platform's installation path. Label
            // whichever shape was actually observed instead of asserting the one we expected.
            out.append(" knownDivergence=sourceDir:").append(
                    sourceDir != null && hostInfo != null && hostInfo.dataDir != null && sourceDir.startsWith(hostInfo.dataDir)
                            ? "container-private" : "platform-projection");
            out.append(" platformAnswer=").append(platformAnswer(engine));
            out.append(" verdict=").append(leak.length() == 0 ? "consistent" : "HOST_IDENTITY_LEAK(" + leak + ")");
            android.util.Log.i(TAG, out.toString());
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "facade snapshot unavailable: " + unreadable);
        }
    }

    /** Names the plane whose answer leaked the host package, once per plane. */
    private static void mark(StringBuilder sink, String plane) {
        if (sink.indexOf(plane) >= 0) return;
        if (sink.length() > 0) sink.append(',');
        sink.append(plane);
    }

    private static String applicationInfoRecord(ApplicationInfo info) {
        if (info == null) return "null";
        return "{flags=0x" + Integer.toHexString(info.flags)
                + ",privateFlags=" + applicationInfoField(info, "privateFlags")
                + ",process=" + info.processName + ",class=" + info.className
                + ",enabled=" + info.enabled + ",targetSdk=" + info.targetSdkVersion
                + ",minSdk=" + info.minSdkVersion + ",taskAffinity=" + info.taskAffinity
                + ",nativeLib=" + info.nativeLibraryDir + ",data=" + info.dataDir
                + ",deviceData=" + info.deviceProtectedDataDir
                + ",credentialData=" + applicationInfoField(info, "credentialProtectedDataDir")
                + ",primaryAbi=" + applicationInfoField(info, "primaryCpuAbi")
                + ",secondaryAbi=" + applicationInfoField(info, "secondaryCpuAbi")
                + ",nativeRoot=" + applicationInfoField(info, "nativeLibraryRootDir") + "}";
    }

    private static String applicationInfoField(ApplicationInfo info, String name) {
        try {
            java.lang.reflect.Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            return String.valueOf(field.get(info));
        } catch (Throwable unavailable) {
            return "error:" + unavailable.getClass().getSimpleName();
        }
    }

    private static String cmdline() {
        String raw = read("/proc/self/cmdline");
        return raw == null ? "?" : raw.replace('\0', '|').trim();
    }

    /** The uid line of /proc/self/status: real, effective, saved, filesystem - all four are read. */
    private static String procUid() {
        String status = read("/proc/self/status");
        if (status == null) return "?";
        for (String line : status.split("\n")) if (line.startsWith("Uid:")) return line.substring(4).trim();
        return "?";
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            byte[] buffer = new byte[256];
            int count;
            while ((count = in.read(buffer)) > 0 && out.size() < 4096) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    /**
     * What a client integrity check can read about this process' own mappings. It is
     * sampled through the same path a native SDK uses (open + read of /proc/self/maps),
     * so the numbers describe the virtualized view the guest is served, not the physical
     * one the runtime verifies against. Nothing is written anywhere but logcat.
     */
    static void logMapsAudit(String when) {
        try {
            String maps = readFully("/proc/self/maps", 8 << 20);
            if (maps == null) {
                android.util.Log.w(TAG, "maps libc " + when + " unavailable");
                return;
            }
            logMapsText("libc", when, maps);
            String smaps = readFully("/proc/self/smaps", 16 << 20);
            if (smaps == null) {
                android.util.Log.w(TAG, "smaps libc " + when + " unavailable");
            } else {
                logMapsText("libc-smaps", when, smaps);
            }
            String mountinfo = readFully("/proc/self/mountinfo", 1 << 20);
            if (mountinfo == null) {
                android.util.Log.w(TAG, "mountinfo libc " + when + " unavailable");
            } else {
                int rows = 0, container = 0;
                for (String row : mountinfo.split("\n")) {
                    if (row.isEmpty()) continue;
                    rows++;
                    if (row.toLowerCase(Locale.ROOT).contains("tripoder")) container++;
                }
                android.util.Log.i(TAG, "mountinfo libc " + when + " rows=" + rows
                    + " bytes=" + mountinfo.length() + " containerRows=" + container);
            }
            logFdAudit(when);
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "maps libc " + when + " failed: " + unreadable);
        }
    }

    /** What a client integrity check sees when it scans /proc/self/fd: every live
     * descriptor must readlink cleanly; an ENOENT on a listed fd exposes hidden
     * runtime files. Sampled through libc, the same view a native SDK gets. */
    private static void logFdAudit(String when) {
        try {
            String[] fds = new java.io.File("/proc/self/fd").list();
            if (fds == null) {
                android.util.Log.w(TAG, "fdscan libc " + when + " unavailable");
                return;
            }
            int ok = 0, failed = 0, suspicious = 0;
            StringBuilder failedList = new StringBuilder();
            StringBuilder suspiciousList = new StringBuilder();
            for (String fd : fds) {
                String target;
                try {
                    target = android.system.Os.readlink("/proc/self/fd/" + fd);
                } catch (Throwable error) {
                    failed++;
                    if (failedList.length() < 700) {
                        String raw = null;
                        try {
                            raw = RuntimeSelfHookStorage.rawFdTargetNative(Integer.parseInt(fd));
                        } catch (Throwable ignored) {}
                        failedList.append(failedList.length() == 0 ? "" : ",").append(fd);
                        if (raw != null) failedList.append("~").append(raw);
                    }
                    continue;
                }
                ok++;
                String lower = target.toLowerCase(Locale.ROOT);
                if (lower.contains("tripoder") || lower.contains("memfd") || lower.contains("selfhook")) {
                    suspicious++;
                    if (suspiciousList.length() < 400) {
                        suspiciousList.append(suspiciousList.length() == 0 ? "" : " | ").append(fd).append("->").append(target);
                    }
                }
            }
            android.util.Log.i(TAG, "fdscan libc " + when + " total=" + fds.length
                + " ok=" + ok + " failed=" + failed + " suspicious=" + suspicious
                + " failedFds=[" + failedList + "] suspiciousFds=[" + suspiciousList + "]");
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "fdscan libc " + when + " failed: " + unreadable);
        }
    }

    /** Compare the runtime's allowlisted internal SVC gateway with an ordinary
     * guest-PC SVC. Only the latter has the same seccomp reachability as SDK
     * code; physical bytes returned by the internal gateway are not by
     * themselves evidence of a guest-visible leak. */
    static void logRawMapsAudit(String when) {
        try {
            android.util.Log.i(TAG, "maps namespace " + when + " " + RuntimeSelfHookStorage.syscallNamespaceNative());
            byte[] internal = RuntimeSelfHookStorage.rawProcMapsNative(8 << 20);
            if (internal != null) logMapsText("svc-internal", when,
                new String(internal, java.nio.charset.StandardCharsets.UTF_8));
            byte[] guest = RuntimeSelfHookStorage.guestProcMapsNative(8 << 20);
            if (guest == null) {
                android.util.Log.w(TAG, "maps svc-guest " + when + " unavailable");
                return;
            }
            logMapsText("svc-guest", when, new String(guest, java.nio.charset.StandardCharsets.UTF_8));
            logRawMountsAudit(when);
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "maps raw-svc " + when + " failed: " + unreadable);
        }
    }

    private static void logRawMountsAudit(String when) {
        for (String view : new String[]{"svc-internal", "svc-guest"}) {
            try {
                byte[] bytes = view.equals("svc-guest")
                    ? RuntimeSelfHookStorage.guestProcMountsNative(4 << 20)
                    : RuntimeSelfHookStorage.rawProcMountsNative(4 << 20);
                if (bytes == null) {
                    android.util.Log.w(TAG, "mounts " + view + " " + when + " unavailable");
                    continue;
                }
                String mounts = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                int rows = 0, container = 0, hook = 0, guest = 0;
                java.util.LinkedHashSet<String> samples = new java.util.LinkedHashSet<>();
                for (String row : mounts.split("\\n")) {
                    if (row.isEmpty()) continue;
                    rows++;
                    String lower = row.toLowerCase(Locale.ROOT);
                    if (lower.contains("tripoder")) container++;
                    if (lower.contains("selfhook")) hook++;
                    if (lower.contains("com.ss.android.ugc.aweme")) guest++;
                    if ((lower.contains("tripoder") || lower.contains("selfhook")
                        || lower.contains("com.ss.android.ugc.aweme")) && samples.size() < 12) {
                        samples.add(row.length() > 300 ? row.substring(0, 300) : row);
                    }
                }
                android.util.Log.i(TAG, "mounts " + view + " " + when + " rows=" + rows
                    + " bytes=" + bytes.length + " containerRows=" + container
                    + " hookLibRows=" + hook + " guestRows=" + guest);
                for (String sample : samples) android.util.Log.i(TAG, "mounts " + view + " row=" + sample);
            } catch (Throwable unreadable) {
                android.util.Log.w(TAG, "mounts " + view + " " + when + " failed: " + unreadable);
            }
        }
    }

    private static void logMapsText(String view, String when, String maps) {
        try {
            java.util.LinkedHashSet<String> appDirs = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> dataDirs = new java.util.LinkedHashSet<>();
            java.util.List<String> foreignRowsSeen = new java.util.ArrayList<>();
            java.util.List<String> containerRowsSeen = new java.util.ArrayList<>();
            int rows = 0, container = 0, hookLib = 0, foreign = 0, deleted = 0;
            for (String row : maps.split("\n")) {
                if (row.isEmpty()) continue;
                rows++;
                String lower = row.toLowerCase(Locale.ROOT);
                if (lower.contains("tripoder")) {
                    container++;
                    if (containerRowsSeen.size() < 6) containerRowsSeen.add(tail(row));
                }
                if (lower.contains("libtripoderselfhook")) hookLib++;
                if (lower.contains("magisk") || lower.contains("frida") || lower.contains("xposed") || lower.contains("zygisk")) {
                    foreign++;
                    if (foreignRowsSeen.size() < 4) foreignRowsSeen.add(tail(row));
                }
                if (lower.contains("(deleted)")) deleted++;
                int app = row.indexOf("/data/app/");
                if (app >= 0) {
                    int end = row.indexOf('/', app + 10);
                    if (end > app) {
                        int after = row.indexOf('/', end + 1);
                        appDirs.add(after > end ? row.substring(app, after) : row.substring(app, end));
                    }
                }
                int user = row.indexOf("/data/user/");
                if (user >= 0) {
                    int end = row.indexOf('/', user + 11);
                    if (end > user) {
                        int after = row.indexOf('/', end + 1);
                        dataDirs.add(after > end ? row.substring(user, after) : row.substring(user, end));
                    }
                }
            }
            android.util.Log.i(TAG, "maps " + view + " " + when + " rows=" + rows
                + " bytes=" + maps.length() + " containerRows=" + container + " hookLibRows=" + hookLib
                + " foreignRows=" + foreign + " deletedRows=" + deleted
                + " appDirs=" + appDirs + " dataDirs=" + dataDirs
                + " foreign=" + foreignRowsSeen);
            for (int i = 0; i < containerRowsSeen.size(); i++) {
                android.util.Log.i(TAG, "maps " + view + " " + when + " container[" + i + "]=" + containerRowsSeen.get(i));
            }
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "maps " + view + " " + when + " summary failed: " + unreadable);
        }
    }

    /**
     * Read the seccomp ring through an ordinary JNI getter. Unlike the legacy evidence FD,
     * this view cannot be closed by guest code or redirected by external-storage projection.
     * Sequence numbers make each five-second sample incremental even when the ring wraps.
     */
    static synchronized void logSyscallAudit(RuntimeEngine engine, String when) {
        try {
            String snapshot = RuntimeSelfHookStorage.syscallAuditNative();
            if (snapshot == null || snapshot.isEmpty()) {
                android.util.Log.w(TAG, "syscall audit " + when + " empty "
                    + RuntimeSelfHookStorage.syscallNamespaceNative());
                return;
            }
            int lines = 0;
            long previousSequence = auditSequenceRead, maxSequence = previousSequence;
            int procSelf = 0, procOther = 0, sys = 0, appData = 0,
                container = 0, rootTool = 0, devLocal = 0;
            java.util.LinkedHashSet<String> samples = new java.util.LinkedHashSet<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(snapshot))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    long sequence = -1;
                    if (line.startsWith("syscall seq=")) {
                        int end = line.indexOf(' ', 12);
                        if (end > 12) {
                            try { sequence = Long.parseLong(line.substring(12, end)); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                    if (sequence >= 0 && sequence <= previousSequence) continue;
                    lines++;
                    if (sequence > maxSequence) maxSequence = sequence;
                    int at = line.indexOf("path=");
                    if (at < 0) continue;
                    String path = line.substring(at + 5);
                    int stop = path.indexOf(" target=");
                    if (stop >= 0) path = path.substring(0, stop);
                    if (path.isEmpty()) continue;
                    String lower = path.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("/proc/self/")) procSelf++;
                    else if (lower.startsWith("/proc/")) procOther++;
                    else if (lower.startsWith("/sys/")) sys++;
                    else if (lower.contains("/data/app/") || lower.contains("/data/user/")
                        || lower.contains("/data/data/") || lower.contains("/data/local/")) {
                        appData++; devLocal += lower.contains("/data/local/") ? 1 : 0;
                    }
                    else if (lower.contains("tripoder")) container++;
                    else if (lower.contains("/su") || lower.contains("magisk") || lower.contains("frida")
                        || lower.contains("xposed") || lower.contains("zygisk")) rootTool++;
                    boolean diagnosticPath = (lower.startsWith("/proc/") || lower.startsWith("/sys/")
                        || lower.contains("/data/app/") || lower.contains("/data/local/")
                        || lower.contains("tripoder") || lower.contains("selfhook")
                        || lower.contains("/su") || lower.contains("magisk") || lower.contains("frida")
                        || lower.contains("xposed") || lower.contains("zygisk"))
                        && !lower.contains("/task/") && !lower.startsWith("/proc/self/fd/")
                        && !lower.equals("/proc/self/fd")
                        && !lower.contains("xposed");
                    // execve(221)/execveat(281) targets: the guest can never exec, so
                    // every attempt is a behavioral anomaly worth seeing whole.
                    boolean execAttempt = line.contains(" nr=221 ") || line.contains(" nr=281 ");
                    if ((diagnosticPath || execAttempt) && samples.size() < 128) {
                        String trimmed = line.length() > 220 ? line.substring(0, 220) : line;
                        samples.add(resolveAuditPc(trimmed));
                    }
                }
            }
            auditSequenceRead = maxSequence;
            android.util.Log.i(TAG, "syscall audit " + when + " newLines=" + lines
                + " latestSequence=" + maxSequence + " snapshotBytes=" + snapshot.length()
                + " procSelf=" + procSelf + " procOther=" + procOther + " sys=" + sys
                + " appData=" + appData + " devLocal=" + devLocal
                + " containerPaths=" + container + " rootTools=" + rootTool
                + " sampleCount=" + samples.size());
            int index = 0;
            StringBuilder batch = new StringBuilder();
            for (String sample : samples) {
                if (index % 8 == 0) batch = new StringBuilder();
                batch.append(index % 8 == 0 ? "" : " | ").append(sample);
                if (index % 8 == 7 || index + 1 == samples.size()) {
                    android.util.Log.i(TAG, "syscall audit paths " + when + "[" + (index - index % 8) + "]=" + batch);
                }
                index++;
            }
        } catch (Throwable unreadable) {
            android.util.Log.w(TAG, "syscall audit " + when + " failed: " + unreadable);
        }
    }

    /** Resolve the sampled trap pc to a mapped library name (guest maps view;
     * addresses are unaffected by the path projection). */
    private static long[][] auditMapRanges;
    private static String[] auditMapPaths;
    private static long auditMapLoadedAt;
    private static String resolveAuditPc(String line) {
        int at = line.indexOf(" pc=");
        if (at < 0) return line;
        int end = line.indexOf(' ', at + 4);
        if (end < 0) end = line.length();
        long pc;
        try {
            pc = Long.parseUnsignedLong(line.substring(at + 4, end), 16);
        } catch (NumberFormatException bad) {
            return line;
        }
        loadAuditMaps();
        if (auditMapRanges == null) return line;
        for (int i = 0; i < auditMapRanges.length; i++) {
            if (pc >= auditMapRanges[i][0] && pc < auditMapRanges[i][1]) {
                String path = auditMapPaths[i];
                int slash = path == null ? -1 : path.lastIndexOf('/');
                String name = slash >= 0 ? path.substring(slash + 1) : (path == null ? "anon" : path);
                return line + " <" + name + "+0x" + Long.toHexString(pc - auditMapRanges[i][0]) + ">";
            }
        }
        return line;
    }
    private static void loadAuditMaps() {
        long now = android.os.SystemClock.uptimeMillis();
        if (auditMapRanges != null && now - auditMapLoadedAt < 4000) return;
        try {
            java.util.List<long[]> ranges = new java.util.ArrayList<>();
            java.util.List<String> paths = new java.util.ArrayList<>();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String row;
            while ((row = reader.readLine()) != null) {
                String[] parts = row.split("\\s+");
                if (parts.length < 6) continue;
                int dash = parts[0].indexOf('-');
                if (dash < 0) continue;
                ranges.add(new long[]{Long.parseUnsignedLong(parts[0].substring(0, dash), 16),
                    Long.parseUnsignedLong(parts[0].substring(dash + 1), 16)});
                paths.add(parts[parts.length - 1]);
            }
            reader.close();
            auditMapRanges = ranges.toArray(new long[0][]);
            auditMapPaths = paths.toArray(new String[0]);
            auditMapLoadedAt = now;
        } catch (Throwable ignored) { }
    }

    /** One-second windows keep the 1024-entry ring ahead of login syscall bursts. */
    static void scheduleSyscallAudit(RuntimeEngine engine) {
        Thread sampler = new Thread(() -> {
            for (int seconds = 1; seconds <= 90; seconds += 1) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException stop) {
                    return;
                }
                logSyscallAudit(engine, "post-attach@" + seconds + "s");
            }
        }, "TripoderSyscallAudit");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** Sample twice: once after the guest rename, once after the guest is fully attached. */
    static void scheduleMapsAudit() {
        for (final int seconds : new int[]{15, 40}) {
            Thread sampler = new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException stop) {
                    return;
                }
                logMapsAudit("post-attach@" + seconds + "s");
                logRawMapsAudit("post-attach@" + seconds + "s");
            }, "TripoderMapsAudit");
            sampler.setDaemon(true);
            sampler.start();
        }
    }

    /** Enumerate the environment-fingerprint SDK's own classes and their native entry points.
     *
     *  The login verdict is computed from that SDK's report, so the report's shape decides where an
     *  observer has to sit: which native call produces it, and whether it crosses the JNI boundary as
     *  text or as bytes. Only names and signatures are logged, never a value.
     */
    static void logBadgerSurface(RuntimeEngine engine, ClassLoader guestLoader) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String candidate : new String[]{"com.bytedance.badger.Badger",
                "com.bytedance.badger.BadgerNative", "com.bytedance.badger.env.BadgerEnv"}) {
            names.add(candidate);
        }
        dalvik.system.DexFile dex = null;
        try {
            dex = new dalvik.system.DexFile(engine.info.sourceDir);
            java.util.Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements() && names.size() < 32) {
                String name = entries.nextElement();
                if (name.toLowerCase(Locale.ROOT).contains("badger")) names.add(name);
            }
        } catch (Throwable noDex) {
            android.util.Log.w(TAG, "badger dex walk failed: " + noDex);
        } finally {
            if (dex != null) {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            }
        }
        android.util.Log.i(TAG, "badger classes=" + names);
        ClassLoader loader = guestLoader;
        for (String name : names) {
            StringBuilder line = new StringBuilder("badger ").append(name).append(" native:");
            try {
                Class<?> type = Class.forName(name, false, loader);
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isNative(method.getModifiers())) continue;
                    line.append(' ').append(method.getName()).append('(');
                    for (Class<?> parameter : method.getParameterTypes()) {
                        line.append(parameter.getSimpleName()).append(',');
                    }
                    line.append(")->").append(method.getReturnType().getSimpleName());
                    if (line.length() > 800) break;
                }
            } catch (Throwable unresolved) {
                line.append(" ?").append(unresolved.getClass().getSimpleName());
            }
            android.util.Log.i(TAG, line.toString());
        }
    }

    static void scheduleBadgerSurface(RuntimeEngine engine, ClassLoader guestLoader) {
        Thread probe = new Thread(() -> {
            try {
                Thread.sleep(25_000L);
            } catch (InterruptedException stop) {
                return;
            }
            logBadgerSurface(engine, guestLoader);
            installBadgerBridgeProbe(guestLoader);
        }, "TripoderBadgerSurface");
        probe.setDaemon(true);
        probe.start();
    }

    private static final java.util.concurrent.atomic.AtomicInteger badgerBridgeCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Observe the existing native ArtMethod through ManagedHook. This does not
     * replace the shared JNIEnv table or rewrite RegisterNatives. */
    private static void installBadgerBridgeProbe(ClassLoader guestLoader) {
        try {
            Class<?> owner = Class.forName("com.bytedance.badger.NativeBridge", false, guestLoader);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (java.lang.reflect.Modifier.isNative(method.getModifiers())
                        && method.getName().equals("a") && parameters.length == 2
                        && parameters[0] == byte[].class && parameters[1] == Object.class
                        && method.getReturnType() == byte[].class) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                android.util.Log.w(TAG, "badger bridge probe target absent");
                return;
            }
            dev.tripoder.selfhook.ManagedHook.Handle handle =
                    dev.tripoder.selfhook.ManagedHook.prepare(target, new java.lang.reflect.Method[0]);
            handle.enable(call -> {
                Object[] arguments = call.arguments();
                Object context = arguments[1];
                int sequence = badgerBridgeCalls.incrementAndGet();
                long started = android.os.SystemClock.elapsedRealtimeNanos();
                try {
                    Object result = call.original(arguments);
                    if (sequence <= 96) {
                        android.util.Log.i(TAG, "badger bridge #" + sequence + " context="
                                + badgerContext(context) + " elapsedNs="
                                + (android.os.SystemClock.elapsedRealtimeNanos() - started));
                        android.util.Log.i(TAG, "badger bridge #" + sequence + " input "
                                + badgerBytes((byte[]) arguments[0]));
                        android.util.Log.i(TAG, "badger bridge #" + sequence + " output "
                                + badgerBytes((byte[]) result));
                        if (((byte[]) result).length > 512) {
                            String encoded = android.util.Base64.encodeToString(
                                    (byte[]) result, android.util.Base64.NO_WRAP);
                            int parts = (encoded.length() + 699) / 700;
                            for (int offset = 0, part = 0; offset < encoded.length(); offset += 700, part++) {
                                android.util.Log.i(TAG, "badger bridge #" + sequence
                                        + " fullOutputBase64 " + (part + 1) + "/" + parts + " "
                                        + encoded.substring(offset, Math.min(encoded.length(), offset + 700)));
                            }
                        }
                        android.util.Log.i(TAG, "badger bridge #" + sequence + " stack=" + badgerStack());
                    }
                    return result;
                } catch (Throwable failure) {
                    android.util.Log.w(TAG, "badger bridge #" + sequence + " original failed", failure);
                    throw failure;
                }
            });
            android.util.Log.i(TAG, "badger bridge probe armed through ManagedHook");
        } catch (Throwable unavailable) {
            android.util.Log.w(TAG, "badger bridge probe unavailable", unavailable);
        }
    }

    private static String badgerContext(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.getClass().getName() + ":" + value;
        }
        if (value instanceof java.util.Map<?, ?>) {
            return value.getClass().getName() + ":keys=" + ((java.util.Map<?, ?>) value).keySet();
        }
        if (value instanceof Object[]) {
            StringBuilder out = new StringBuilder(value.getClass().getName()).append('[');
            Object[] values = (Object[]) value;
            for (int i = 0; i < Math.min(values.length, 16); i++) {
                Object item = values[i];
                if (i != 0) out.append(',');
                out.append(item == null ? "null" : item.getClass().getName());
                if (item instanceof Number || item instanceof Boolean) out.append(':').append(item);
            }
            return out.append(']').toString();
        }
        return value.getClass().getName();
    }

    private static String badgerBytes(byte[] value) {
        if (value == null) return "null";
        int count = Math.min(value.length, 128);
        StringBuilder printable = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            int b = value[i] & 0xff;
            printable.append(b >= 32 && b <= 126 ? (char) b : '.');
        }
        try {
            return "len=" + value.length + " sha256="
                    + hex(MessageDigest.getInstance("SHA-256").digest(value)) + " headHex="
                    + hex(java.util.Arrays.copyOf(value, count)) + " headAscii=" + printable;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String badgerStack() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("dev.tripoder.") || name.startsWith("java.lang.")
                    || name.startsWith("dalvik.") || name.equals(Thread.class.getName())) continue;
            if (out.length() != 0) out.append(" <- ");
            out.append(name).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (out.length() > 700) break;
        }
        return out.toString();
    }

    /** The mapped path end of a maps row, bounded so one log line stays readable. */
    private static String tail(String row) {
        return row.length() > 160 ? "..." + row.substring(row.length() - 160) : row;
    }

    private static String readFully(String path, int limit) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0 && out.size() < limit) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private IdentitySnapshot() {}
}
