package dev.tripoder.isolationprobe;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordinary app identity only. No VM creation, device ioctl, permission grant or host app launch. */
public final class ProbeActivity extends Activity {
    private static final String[] PERMISSIONS = {
        "android.permission.MANAGE_VIRTUAL_MACHINE",
        "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setPadding(24, 60, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);
        try {
            JSONObject report = inspect();
            File directory = getExternalFilesDir(null);
            if (directory == null) throw new IllegalStateException("External app report directory unavailable");
            try (FileOutputStream stream = new FileOutputStream(new File(directory, "isolation-probe.json"))) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            output.setText(report.toString(2));
        } catch (Exception error) {
            output.setText("探测未完成：" + error);
            android.util.Log.e("TripoderIsolation", "Probe incomplete", error);
        }
    }

    private JSONObject inspect() throws Exception {
        PackageManager manager = getPackageManager();
        JSONObject report = new JSONObject().put("schema", 1).put("observedAtMs", System.currentTimeMillis())
            .put("packageName", getPackageName()).put("uid", Process.myUid()).put("pid", Process.myPid())
            .put("debuggable", (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("abis", new JSONArray(Build.SUPPORTED_ABIS))
            .put("avfFeature", manager.hasSystemFeature("android.software.virtualization_framework"))
            .put("selinuxContext", new String(Files.readAllBytes(new File("/proc/self/attr/current").toPath()), StandardCharsets.UTF_8).replace("\u0000", "").trim());
        JSONArray permissions = new JSONArray();
        for (String name : PERMISSIONS) {
            PermissionInfo info = manager.getPermissionInfo(name, 0);
            permissions.put(new JSONObject().put("name", name)
                .put("granted", checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED)
                .put("protectionLevel", info.protectionLevel));
        }
        report.put("permissions", permissions);
        PackageInfo tripoder = manager.getPackageInfo("dev.tripoder.hooklab", PackageManager.GET_PERMISSIONS);
        JSONArray hostPermissions = new JSONArray();
        for (String name : PERMISSIONS) {
            boolean requested = false;
            if (tripoder.requestedPermissions != null) {
                for (String item : tripoder.requestedPermissions) if (name.equals(item)) requested = true;
            }
            hostPermissions.put(new JSONObject().put("name", name).put("requested", requested)
                .put("granted", manager.checkPermission(name, tripoder.packageName) == PackageManager.PERMISSION_GRANTED));
        }
        report.put("tripoder", new JSONObject().put("uid", tripoder.applicationInfo.uid)
            .put("versionName", tripoder.versionName).put("permissions", hostPermissions));
        JSONArray devices = new JSONArray();
        for (String path : new String[]{"/dev/kvm", "/dev/gunyah", "/dev/vhost-vsock"}) devices.put(openDevice(path));
        return report.put("deviceOpenChecks", devices).put("vmCreated", false)
            .put("ioctlAttempted", false).put("fullAndroidBootVerified", false)
            .put("gpuVerified", false).put("hookVerified", false).put("crossAppLoginVerified", false);
    }

    private JSONObject openDevice(String path) throws Exception {
        JSONObject result = new JSONObject().put("path", path).put("operation", "open O_RDWR then close; no ioctl");
        FileDescriptor descriptor;
        try {
            descriptor = Os.open(path, OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
        } catch (ErrnoException error) {
            return result.put("opened", false).put("errno", error.errno).put("error", error.getMessage());
        }
        // Close errors invalidate the probe instead of being misreported as a denied open.
        Os.close(descriptor);
        return result.put("opened", true);
    }
}
