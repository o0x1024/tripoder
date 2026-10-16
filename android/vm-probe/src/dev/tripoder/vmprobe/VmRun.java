package dev.tripoder.vmprobe;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class VmRun {
    static volatile VmRun current;
    final File root, output, qmp;
    final JSONObject report = new JSONObject();
    final ScheduledExecutorService observation = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
    private final java.util.function.Consumer<String> status;
    private volatile LocalSocket console;
    volatile java.util.function.Consumer<File> onFrame;
    private long started;
    VmRun(Context context, java.util.function.Consumer<String> status) throws Exception {
        this.status = status; root = context.getFilesDir(); qmp = new File(root, "qmp.sock");
        output = new File(context.getExternalFilesDir(null), "run-" + System.currentTimeMillis());
        if (!output.mkdirs()) throw new IOException("Cannot create run directory");
        VmFiles.write(new File(context.getExternalFilesDir(null), "latest-run.txt"), output.getName());
        for (String name : new String[]{"qmp.sock", "console.sock"}) new File(root, name).delete();
        report.put("schema", 1).put("hostUid", Process.myUid()).put("hostPid", Process.myPid())
            .put("hostDebuggable", (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("accelerator", "tcg").put("hostRootRequired", false)
            .put("hostAdbActivationRequired", false).put("guestNetwork", "none")
            .put("hostAppBridge", false).put("hostSharedFilesystem", false)
            .put("fullAndroidBootVerified", false).put("gpuPerformanceVerified", false).put("guestHookVerified", false);
        report.put("stage", "created"); save();
    }
    void start(Context context) throws Exception {
        try { runNative(context); }
        catch (Exception | LinkageError error) {
            synchronized (report) { report.put("stage", "failed").put("failure", error.toString()); save(); }
            throw error;
        } finally {
            observation.shutdownNow(); deadline.shutdownNow();
            LocalSocket connection = console;
            if (connection != null) try { connection.close(); } catch (IOException ignored) { }
        }
    }
    private void runNative(Context context) throws Exception {
        VmFiles.prepare(context, status);
        status.accept("正在准备客体软件模拟器启动配置；保留现有系统与数据");
        report.put("guestGrubConfigSha256", GuestBootConfig.prepareSoftwareEmulator(new File(root, "vda.qcow2")))
            .put("guestLogcatBuffers", "all").put("guestBootProfile", "tripoder-software-normal-ram")
            .put("guestInitProfileSha256", GuestSoftwareProfile.NORMAL_RAM).put("guestLowRamForced", false)
            .put("guestWatchdogDisabled", false);
        List<String> args = new ArrayList<>(Arrays.asList("qemu-system-aarch64",
            "-machine", "virt,gic-version=3", "-cpu", "max", "-accel", "tcg,thread=multi,tb-size=256",
            "-smp", "2", "-m", "2048", "-nodefaults", "-nic", "none", "-display", "none",
            "-drive", "if=pflash,format=raw,readonly=on,file=" + new File(root, "efi-code.fd"),
            "-drive", "if=pflash,format=raw,file=" + new File(root, "efi-vars.fd"),
            "-drive", "if=none,id=system,format=qcow2,file=" + new File(root, "vda.qcow2"),
            "-device", "virtio-blk-pci,drive=system,bootindex=0",
            "-drive", "if=none,id=data,format=qcow2,file=" + new File(root, "vdb.qcow2"),
            "-device", "virtio-blk-pci,drive=data",
            "-device", "virtio-gpu-pci", "-device", "virtio-keyboard-pci", "-device", "virtio-tablet-pci",
            "-device", "virtio-rng-pci", "-device", "virtio-serial-pci",
            "-chardev", "socket,id=console,path=" + new File(root, "console.sock") + ",server,nowait",
            "-device", "virtconsole,chardev=console",
            "-chardev", "file,id=logcat,path=" + new File(output, "guest-logcat.txt"),
            "-device", "virtconsole,chardev=logcat",
            "-serial", "file:" + new File(output, "serial.txt"),
            "-qmp", "unix:" + qmp + ",server,nowait", "-no-reboot"));
        started = System.currentTimeMillis();
        report.put("startedAtMs", started).put("arguments", new JSONArray(args)).put("stage", "starting"); save();
        try (FileOutputStream log = new FileOutputStream(new File(output, "qemu.txt"))) {
            Os.dup2(log.getFD(), 1); Os.dup2(log.getFD(), 2);
        }
        Os.setenv("TMPDIR", root.getPath(), true);
        // Android ordinary-app capabilities remain in effect; no subprocess, shell, su or ADB helper.
        observation.scheduleWithFixedDelay(this::observe, 5, 10, TimeUnit.SECONDS);
        // Bound this experiment even when QMP or the guest stops responding.
        deadline.schedule(() -> {
            try {
                synchronized (report) { report.put("stage", "time-limit").put("stopReason", "60-minute experiment limit"); save(); }
            } catch (Exception error) { android.util.Log.e("TripoderVM", "Cannot persist deadline", error); }
            Process.killProcess(Process.myPid());
        }, 60, TimeUnit.MINUTES);
        new Thread(this::readConsole, "guest-console").start();
        status.accept("TCG 软件模拟启动中；完整 Android 启动尚待验证");
        String result = NativeEngine.start(args.toArray(new String[0]));
        synchronized (report) { report.put("stage", "stopped").put("nativeResult", result); save(); }
        status.accept(result);
    }
    private void readConsole() {
        try {
            File path = new File(root, "console.sock");
            for (int i = 0; i < 60 && !path.exists(); i++) Thread.sleep(1000);
            LocalSocket connection = new LocalSocket();
            connection.connect(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            console = connection;
            try (InputStream input = connection.getInputStream(); FileOutputStream log = new FileOutputStream(new File(output, "console.txt"))) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) { log.write(buffer, 0, count); log.flush(); }
            } finally { console = null; connection.close(); }
        } catch (Exception error) { status.accept("客体控制台：" + error.getMessage()); }
    }
    void guestCommand(String text) throws IOException {
        LocalSocket connection = console;
        if (connection == null) throw new IOException("Guest console not connected");
        connection.getOutputStream().write((text + "\n").getBytes(StandardCharsets.UTF_8));
        connection.getOutputStream().flush();
    }
    synchronized void snapshot() throws Exception {
        File image = new File(output, "screen.ppm");
        try (Qmp client = new Qmp(qmp)) {
            client.execute("screendump", new JSONObject().put("filename", image.getPath()));
        }
        java.util.function.Consumer<File> observer = onFrame;
        if (observer != null) observer.accept(image);
    }
    void stop() throws Exception { try (Qmp client = new Qmp(qmp)) { client.execute("quit", null); } }
    private void observe() {
        try {
            Object state, version;
            try (Qmp client = new Qmp(qmp)) {
                state = client.execute("query-status", null).get("return");
                version = client.execute("query-version", null).get("return");
            }
            synchronized (report) {
                report.put("qmpStatus", state).put("qemuVersion", version);
                report.put("observedAtMs", System.currentTimeMillis()).put("stage", "emulator-running");
                report.remove("observationError"); save();
            }
            snapshot();
            status.accept("模拟器运行 " + ((System.currentTimeMillis() - started) / 1000) + " 秒；系统启动需以客体证据判定");
        } catch (Exception error) {
            try { synchronized (report) { report.put("observationError", error.toString()); save(); } }
            catch (Exception saveError) { android.util.Log.e("TripoderVM", "Cannot persist observation", saveError); }
        }
    }
    private void save() throws Exception { synchronized (report) { VmFiles.write(new File(output, "run.json"), report.toString(2)); } }
}
