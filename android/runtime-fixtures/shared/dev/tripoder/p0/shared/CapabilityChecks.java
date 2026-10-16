package dev.tripoder.p0.shared;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;

/** Public Android calls only; this APK has no Tripoder dependency. */
public final class CapabilityChecks {
    private interface Attempt { void run() throws Exception; }
    private static boolean denied(Attempt attempt, String code) throws Exception {
        try { attempt.run(); return false; }
        catch (UnsupportedOperationException error) { return error.getMessage().startsWith(code); }
    }
    public static JSONObject run(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File root = app.getDataDir();
        JSONObject checks = new JSONObject();
        File marker = app.getFileStreamPath("context-file-check.txt");
        try (var out = app.openFileOutput(marker.getName(), Context.MODE_PRIVATE)) { out.write(7); }
        try (var in = app.openFileInput(marker.getName())) {
            checks.put("file-api-space-owned", marker.getParentFile().equals(app.getFilesDir())
                    && in.read() == 7 && Arrays.asList(app.fileList()).contains(marker.getName())
                    && app.deleteFile(marker.getName()) && !marker.exists());
        }
        checks.put("private-dir-space-owned", app.getDir("probe", Context.MODE_PRIVATE).getParentFile().equals(root));
        checks.put("external-dirs-space-owned", app.getExternalFilesDirs(null)[0].getParentFile().equals(root)
                && app.getExternalCacheDirs()[0].getParentFile().equals(root));
        Context configured = app.createConfigurationContext(new Configuration(app.getResources().getConfiguration()));
        checks.put("configured-context-retains-identity", configured.getPackageName().equals(app.getPackageName())
                && configured.getFilesDir().equals(app.getFilesDir()) && configured.getClassLoader() == app.getClassLoader());
        checks.put("unknown-service-rejected", denied(() -> app.getSystemService("tripoder.unsupported.service"), "SYSTEM_SERVICE_UNSUPPORTED"));
        checks.put("host-package-query-rejected", denied(() -> app.getPackageManager().getApplicationInfo("dev.tripoder.p0.b", 0), "OTHER_PACKAGE_QUERY_UNSUPPORTED"));
        boolean missingProvider=false;
        try { app.getContentResolver().call("tripoder.unsupported", "GET", "key", null); }
        catch (IllegalArgumentException error) {
            missingProvider="Unknown authority tripoder.unsupported".equals(error.getMessage());
        }
        checks.put("other-provider-rejected", missingProvider);
        checks.put("settings-write-rejected", denied(() -> app.getContentResolver().call("settings", "PUT_system", "tripoder_p0_forbidden", Bundle.EMPTY), "PROVIDER_CALL_UNSUPPORTED"));
        checks.put("obb-rejected", denied(app::getObbDir, "OBB_UNSUPPORTED"));
        boolean traversal = false;
        try { app.getFileStreamPath("../host-file"); }
        catch (IllegalArgumentException error) { traversal = "INVALID_FILE_NAME".equals(error.getMessage()); }
        checks.put("file-name-traversal-rejected", traversal);
        return checks;
    }
    private CapabilityChecks() {}
}
