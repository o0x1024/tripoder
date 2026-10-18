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
    private static boolean notFound(Attempt attempt) throws Exception {
        try { attempt.run(); return false; }
        catch (android.content.pm.PackageManager.NameNotFoundException expected) { return true; }
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
        // Android answers a service name that does not exist with null; it is not an error the app can have a handler for.
        checks.put("unknown-service-absent", app.getSystemService("tripoder.unsupported.service") == null);
        // A package outside the guest's world is not installed as far as the guest can tell.
        checks.put("other-package-query-not-found", notFound(() -> app.getPackageManager().getApplicationInfo("dev.tripoder.p0.b", 0)));
        boolean missingProvider=false;
        try { app.getContentResolver().call("tripoder.unsupported", "GET", "key", null); }
        catch (IllegalArgumentException error) {
            missingProvider="Unknown authority tripoder.unsupported".equals(error.getMessage());
        }
        checks.put("other-provider-rejected", missingProvider);
        checks.put("settings-write-rejected", denied(() -> app.getContentResolver().call("settings", "PUT_system", "tripoder_p0_forbidden", Bundle.EMPTY), "PROVIDER_CALL_UNSUPPORTED"));
        // OBB/media roots are guest-private directories under the guest data root, never shared storage.
        checks.put("obb-dir-is-guest-private", app.getObbDir().getAbsolutePath().startsWith(app.getDataDir().getAbsolutePath()+java.io.File.separator)
                && app.getObbDirs().length == 1 && app.getExternalMediaDirs().length == 1
                && app.getExternalMediaDirs()[0].getAbsolutePath().startsWith(app.getDataDir().getAbsolutePath()+java.io.File.separator));
        boolean traversal = false;
        try { app.getFileStreamPath("../host-file"); }
        catch (IllegalArgumentException error) { traversal = "INVALID_FILE_NAME".equals(error.getMessage()); }
        checks.put("file-name-traversal-rejected", traversal);
        return checks;
    }
    private CapabilityChecks() {}
}
