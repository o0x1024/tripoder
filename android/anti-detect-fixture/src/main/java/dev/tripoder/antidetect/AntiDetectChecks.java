package dev.tripoder.antidetect;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Self-contained battery of anti-sandbox / anti-debug / anti-virtualization checks,
// mirroring the techniques commercial app-hardening SDKs ship (梆梆/爱加密/360加固/
// 腾讯乐固/易盾 and similar). This APK has no dependency on the host or on Frida; it
// probes its OWN process and reports what it sees, as a compatibility fixture for
// the Tripoder guest runtime: does running under the engine make any of these fire
// differently than running on bare Android? It never attempts to evade or disable
// anything it finds -- purely observational, written to a result file.
public final class AntiDetectChecks {
    static { System.loadLibrary("antidetect"); }

    public static final class CheckResult {
        public final String name;
        public final boolean suspicious;
        public final String detail;
        CheckResult(String name, boolean suspicious, String detail) { this.name = name; this.suspicious = suspicious; this.detail = detail; }
        @Override public String toString() { return (suspicious ? "[SUSPICIOUS] " : "[clean]     ") + name + " - " + detail; }
    }

    // ---- native checks (native/anti_detect.cpp) ----
    public static native boolean nativeTracerPid();
    public static native boolean nativePtraceSelf();
    public static native boolean nativeThreadTracerPid();
    public static native String nativeMapsHits();
    public static native String nativeForeignCodeMaps(String ownPackage);
    public static native String nativeMountRedirect();
    public static native String nativeParentCmdline();
    public static native String nativeEmulatorProps();
    public static native String nativeFridaPorts();
    public static native boolean nativeBreakpointScan();
    public static native long nativeClockDivergence();
    public static native long nativeTimingDeltaNanos();

    // Known dual-app / virtual-container host packages that hardening SDKs enumerate.
    private static final String[] CONTAINER_PACKAGES = {
        "com.lbe.parallel", "com.lbe.parallel.intl", "com.ludashi.dualspace",
        "com.excelliance.dualaid", "com.by.chaos", "io.virtualapp", "io.va.exposed",
        "com.qihoo.magic", "com.doubleopen.cloner", "com.bly.dkplat",
        "com.lody.virtual", "com.cootek.virtualapp", "dev.tripoder.engine",
    };

    public static List<CheckResult> runAll(Context context) {
        List<CheckResult> out = new ArrayList<>();
        // debugger / tracer
        out.add(debuggerConnected());
        out.add(tracerPidJava());
        out.add(tracerPidNative());
        out.add(threadTracerPid());
        out.add(ptraceSelfAttach());
        out.add(breakpointScan());
        // emulator
        out.add(buildFingerprint());
        out.add(emulatorProps());
        // Frida / hook frameworks
        out.add(fridaFiles());
        out.add(fridaPortConnect());
        out.add(fridaPortsListening());
        out.add(suspiciousMaps());
        // root
        out.add(rootBinaries());
        out.add(magiskArtifacts());
        // container / virtualization (most relevant to an app-virtualization engine)
        out.add(sharedUidPackages(context));
        out.add(codePathMatchesPackage(context));
        out.add(dataDirMatchesPackage(context));
        out.add(processNameMatchesPackage(context));
        out.add(installerPackage(context));
        out.add(knownContainerPackages(context));
        out.add(foreignCodeMaps(context));
        out.add(mountRedirect());
        out.add(parentIsZygote());
        // timing
        out.add(clockDivergence());
        out.add(timingAnomaly());
        return out;
    }

    // ---------- debugger / tracer ----------

    private static CheckResult debuggerConnected() {
        boolean attached = Debug.isDebuggerConnected() || Debug.waitingForDebugger();
        return new CheckResult("Debug.isDebuggerConnected", attached, "attached=" + attached);
    }

    private static int readTracerPid() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TracerPid:")) return Integer.parseInt(line.substring(10).trim());
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static CheckResult tracerPidJava() {
        int tracerPid = readTracerPid();
        return new CheckResult("java:/proc/self/status TracerPid", tracerPid != 0, "TracerPid=" + tracerPid);
    }

    private static CheckResult tracerPidNative() {
        boolean traced = nativeTracerPid();
        return new CheckResult("native:/proc/self/status TracerPid", traced, "traced=" + traced);
    }

    private static CheckResult threadTracerPid() {
        boolean traced = nativeThreadTracerPid();
        return new CheckResult("native:/proc/self/task/*/status TracerPid (all threads)", traced, "anyThreadTraced=" + traced);
    }

    private static CheckResult ptraceSelfAttach() {
        boolean alreadyTraced = nativePtraceSelf();
        return new CheckResult("native:ptrace(PTRACE_TRACEME) self-attach", alreadyTraced, "already-traced=" + alreadyTraced);
    }

    private static CheckResult breakpointScan() {
        boolean found = nativeBreakpointScan();
        return new CheckResult("native:.text AArch64 BRK software-breakpoint scan", found, "breakpointFound=" + found);
    }

    // ---------- emulator ----------

    private static CheckResult buildFingerprint() {
        String fp = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase();
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase();
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase();
        boolean testKeys = "test-keys".equals(Build.TAGS);
        boolean genericMarkers = fp.contains("generic") || fp.contains("sdk_gphone") || model.contains("sdk") || model.contains("emulator") || product.contains("sdk") || product.contains("emulator");
        boolean suspicious = testKeys || genericMarkers;
        return new CheckResult("Build fingerprint/model/tags", suspicious,
            "FINGERPRINT=" + Build.FINGERPRINT + " MODEL=" + Build.MODEL + " PRODUCT=" + Build.PRODUCT + " TAGS=" + Build.TAGS);
    }

    private static CheckResult emulatorProps() {
        String hits = nativeEmulatorProps();
        boolean suspicious = hits != null && !hits.isEmpty();
        return new CheckResult("native:emulator system properties", suspicious, suspicious ? hits : "no qemu/goldfish/ranchu markers");
    }

    // ---------- Frida / hook frameworks ----------

    private static final String[] FRIDA_PATHS = {
        "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
        "/data/local/tmp/frida-helper-32", "/data/local/tmp/frida-helper-64",
    };

    private static CheckResult fridaFiles() {
        List<String> hits = new ArrayList<>();
        for (String path : FRIDA_PATHS) if (new File(path).exists()) hits.add(path);
        return new CheckResult("Frida well-known files", !hits.isEmpty(), hits.isEmpty() ? "none found" : hits.toString());
    }

    private static CheckResult fridaPortConnect() {
        boolean open = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 27042), 150);
            open = true;
        } catch (Exception ignored) { }
        return new CheckResult("127.0.0.1:27042 connect (frida-server default)", open, "open=" + open);
    }

    private static CheckResult fridaPortsListening() {
        String ports = nativeFridaPorts();
        boolean suspicious = ports != null && (ports.contains("27042") || ports.contains("27043"));
        return new CheckResult("native:/proc/net/tcp listening frida ports", suspicious, ports == null || ports.isEmpty() ? "none listening" : ports);
    }

    private static CheckResult suspiciousMaps() {
        String hits = nativeMapsHits();
        boolean suspicious = hits != null && !hits.isEmpty();
        return new CheckResult("native:/proc/self/maps hook-framework scan", suspicious, suspicious ? hits : "no frida/xposed/substrate/gadget markers mapped");
    }

    // ---------- root ----------

    private static final String[] ROOT_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk",
        "/data/local/su", "/su/bin/su",
    };

    private static CheckResult rootBinaries() {
        List<String> hits = new ArrayList<>();
        for (String path : ROOT_PATHS) if (new File(path).exists()) hits.add(path);
        return new CheckResult("root binary paths", !hits.isEmpty(), hits.isEmpty() ? "none found" : hits.toString());
    }

    private static final String[] MAGISK_PATHS = {
        "/sbin/.magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock",
        "/cache/magisk.log", "/data/adb/magisk", "/data/adb/modules",
        "/system/addon.d/99-magisk.sh", "/data/magisk.apk",
    };

    private static CheckResult magiskArtifacts() {
        List<String> hits = new ArrayList<>();
        for (String path : MAGISK_PATHS) if (new File(path).exists()) hits.add(path);
        return new CheckResult("Magisk artifacts", !hits.isEmpty(), hits.isEmpty() ? "none found" : hits.toString());
    }

    // ---------- container / virtualization ----------

    // A normal app owns its UID alone; a virtual-app engine runs the guest under the
    // host UID, so getPackagesForUid returns the host (and often several guests).
    private static CheckResult sharedUidPackages(Context context) {
        String own = context.getPackageName();
        String[] packages = context.getPackageManager().getPackagesForUid(Process.myUid());
        List<String> list = packages == null ? List.of() : Arrays.asList(packages);
        boolean suspicious = list.size() > 1 || (list.size() == 1 && !list.get(0).equals(own));
        return new CheckResult("UID shared across packages", suspicious, "uid=" + Process.myUid() + " own=" + own + " packages=" + list);
    }

    // The APK our code runs from should sit under /data/app/<own-package>...; a
    // container serves the guest out of the host's artifact tree.
    private static CheckResult codePathMatchesPackage(Context context) {
        String own = context.getPackageName();
        String codePath = context.getPackageCodePath();
        String sourceDir = context.getApplicationInfo().sourceDir;
        boolean suspicious = codePath == null || !codePath.contains(own) || !codePath.startsWith("/data/app/");
        return new CheckResult("code path belongs to own package", suspicious, "codePath=" + codePath + " sourceDir=" + sourceDir);
    }

    private static CheckResult dataDirMatchesPackage(Context context) {
        String own = context.getPackageName();
        String dataDir = context.getApplicationInfo().dataDir;
        String filesDir = context.getFilesDir().getAbsolutePath();
        boolean suspicious = dataDir == null || !dataDir.contains(own) || !filesDir.contains(own);
        return new CheckResult("data dir belongs to own package", suspicious, "dataDir=" + dataDir + " filesDir=" + filesDir);
    }

    private static CheckResult processNameMatchesPackage(Context context) {
        String own = context.getPackageName();
        String cmdline = readCmdline();
        // Strip any ":subprocess" suffix before comparing to the package name.
        String base = cmdline.contains(":") ? cmdline.substring(0, cmdline.indexOf(':')) : cmdline;
        boolean suspicious = !base.equals(own);
        return new CheckResult("process name matches package", suspicious, "cmdline=" + cmdline + " own=" + own);
    }

    private static String readCmdline() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String line = reader.readLine();
            return line == null ? "" : line.replace('\0', ' ').trim();
        } catch (Exception ignored) { return ""; }
    }

    private static CheckResult installerPackage(Context context) {
        String installer;
        try {
            if (Build.VERSION.SDK_INT >= 30) installer = context.getPackageManager().getInstallSourceInfo(context.getPackageName()).getInstallingPackageName();
            else installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
        } catch (Exception error) { installer = "error:" + error.getClass().getSimpleName(); }
        // Being installed BY a container/dual-app host is the tell, not a null (sideload).
        boolean suspicious = installer != null && Arrays.asList(CONTAINER_PACKAGES).contains(installer);
        return new CheckResult("installer package", suspicious, "installer=" + installer);
    }

    private static CheckResult knownContainerPackages(Context context) {
        PackageManager manager = context.getPackageManager();
        List<String> present = new ArrayList<>();
        for (String pkg : CONTAINER_PACKAGES) {
            if (pkg.equals(context.getPackageName())) continue;
            try { manager.getPackageInfo(pkg, 0); present.add(pkg); } catch (Exception ignored) { }
        }
        return new CheckResult("known container/dual-app packages visible", !present.isEmpty(), present.isEmpty() ? "none visible" : present.toString());
    }

    private static CheckResult foreignCodeMaps(Context context) {
        String hits = nativeForeignCodeMaps(context.getPackageName());
        boolean suspicious = hits != null && !hits.isEmpty();
        return new CheckResult("native:foreign-package code mapped in process", suspicious, suspicious ? hits : "all mapped app code belongs to own package");
    }

    private static CheckResult mountRedirect() {
        String hits = nativeMountRedirect();
        boolean suspicious = hits != null && !hits.isEmpty();
        return new CheckResult("native:bind/overlay/fuse redirect on app dirs", suspicious, suspicious ? hits : "no redirected app-dir mounts");
    }

    private static CheckResult parentIsZygote() {
        String parent = nativeParentCmdline();
        String lower = parent == null ? "" : parent.toLowerCase();
        boolean suspicious = !(lower.contains("zygote"));
        return new CheckResult("parent process is zygote", suspicious, "parent=" + (parent == null || parent.isEmpty() ? "<unknown>" : parent));
    }

    // ---------- timing ----------

    private static CheckResult clockDivergence() {
        long ratio = nativeClockDivergence(); // wall/cpu * 100; ~100 normal
        boolean suspicious = ratio > 300; // 3x wall-over-cpu for a pure busy loop is abnormal
        return new CheckResult("native wall-vs-cpu clock divergence", suspicious, "ratioX100=" + ratio);
    }

    private static CheckResult timingAnomaly() {
        long deltaNanos = nativeTimingDeltaNanos();
        boolean suspicious = deltaNanos > 50_000_000L; // 50ms for a trivial native loop is abnormal
        return new CheckResult("native timing probe", suspicious, "deltaNanos=" + deltaNanos);
    }
}
