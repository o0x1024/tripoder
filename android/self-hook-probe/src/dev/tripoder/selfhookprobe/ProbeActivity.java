package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent H1/H2a research APK. Never loads a guest or modifies another process. */
public final class ProbeActivity extends Activity {
    private static native String runNative(String fixturePath, String evidenceDirectory);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Tripoder self-hook Native/ART probe running");
        view.setPadding(32, 64, 32, 32);
        setContentView(view);
        String supplied = getIntent().getStringExtra("runId");
        // Require a fresh caller identity; no stale fixed result file.
        final String runId;
        try {
            runId = UUID.fromString(supplied).toString();
        } catch (Exception error) {
            view.setText("A valid runId UUID is required");
            return;
        }
        new Thread(() -> execute(runId, view), "self-hook-probe").start();
    }

    private void execute(String runId, TextView view) {
        JSONObject report = new JSONObject();
        File base = getExternalFilesDir(null);
        if (base == null) {
            runOnUiThread(() -> view.setText("External evidence directory unavailable"));
            return;
        }
        File directory = new File(base, runId);
        boolean ownsDirectory = false;
        try {
            if (!directory.mkdir()) throw new IllegalStateException("RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");
            ownsDirectory = true;
            report.put("schema", 1).put("runId", runId);
            File fixture=ProbeEnvironment.load(this,report);
            File nativeLibrary = new File(getApplicationInfo().nativeLibraryDir, "libtripoderselfhook.so");
            JSONObject nativeReport = new JSONObject(runNative(fixture.getAbsolutePath(), directory.getAbsolutePath()));
            report.put("native", nativeReport);
            report.put("threadPark", ThreadParkProbe.run());
            require(report.getJSONObject("threadPark").getBoolean("passed"), "NATIVE_THREAD_PARK");
            report.put("noncooperative", NoncooperativeProbe.run(fixture.getAbsolutePath(), directory.getAbsolutePath()));
            require(report.getJSONObject("noncooperative").getBoolean("passed"), "NONCOOPERATIVE_OWNED_ELF");
            report.put("art", ArtProbe.run(getApplicationInfo().sourceDir));
            if (report.getJSONObject("art").getBoolean("observationPassed"))
                report.put("artNative", ArtNativeProbe.run(getIntent().getStringExtra("artCompilation")));
            if (report.has("artNative") && report.getJSONObject("artNative").getBoolean("passed"))
                report.put("artManaged", ArtManagedProbe.run(getIntent().getStringExtra("artCompilation"), getApplicationInfo().sourceDir));
            require(report.has("artManaged") && report.getJSONObject("artManaged").getBoolean("passed"), "ART_MANAGED_BEFORE_LIBC");
            report.put("reflectionView",ReflectionViewProbe.run(getIntent().getStringExtra("artCompilation"),getApplicationInfo().sourceDir));
            require(report.getJSONObject("reflectionView").getBoolean("passed"),"REFLECTION_IDENTITY_VIEW");
            report.put("fixedLibc", LibcProbe.run(this, directory));
            require(report.getJSONObject("fixedLibc").getBoolean("passed"), "FIXED_LIBC_STORAGE");
            JSONObject snapshots = new JSONObject();
            for (String stage : new String[]{"prepared", "active", "disabled", "restored", "parked-active", "parked-restored"}) {
                File snapshot = new File(directory, "maps-" + stage + ".txt");
                if (snapshot.exists()) snapshots.put(stage, sha(snapshot));
            }
            report.put("pageSnapshots", snapshots);
            JSONObject libcSnapshots = new JSONObject();
            for (String stage : new String[]{"libc-prepared", "libc-rejected", "libc-active", "libc-restored"}) {
                for (String kind : new String[]{"maps", "smaps"}) {
                    String name=kind+"-"+stage+".txt";
                    libcSnapshots.put(name,sha(new File(directory,name)));
                }
                String name="libc-pages-"+stage+".bin";
                libcSnapshots.put(name,sha(new File(directory,name)));
            }
            report.put("libcSnapshots",libcSnapshots);
            report.put("fixtureSha256After", sha(fixture));
            require(report.getString("fixtureSha256After").equals(report.getString("fixtureSha256")),
                "FIXTURE_FILE_MODIFIED");
            String maps = read("/proc/self/maps");
            require(maps.contains(nativeLibrary.getAbsolutePath()), "NATIVE_MAPPING_MISSING");
            write(new File(directory, "maps.txt"), maps);
            report.put("mapsSha256", sha(new File(directory, "maps.txt")));
            JSONArray forbidden = new JSONArray();
            for (String line : maps.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("frida") || lower.contains("gadget") || lower.contains("libgum"))
                    forbidden.put(line);
            }
            report.put("forbiddenMappings", forbidden);
            require(forbidden.length() == 0, "FORBIDDEN_MAPPING");
            JSONObject core = nativeReport.getJSONObject("core");
            JSONObject execution = nativeReport.getJSONObject("execution");
            require(core.getInt("checks") > 0 && core.getInt("failures") == 0, "CORE_CHECKS");
            require(execution.getInt("checks") > 0 && execution.getInt("failures") == 0
                && execution.getBoolean("executed") && execution.getBoolean("rwObserved")
                && execution.getBoolean("rxObserved"), "SYNTHETIC_EXECUTION");
            JSONObject owned = nativeReport.getJSONObject("ownedHook");
            require(owned.getInt("checks") > 0 && owned.getInt("failures") == 0
                && owned.getBoolean("executed") && owned.getBoolean("liveElfEntryPatched")
                && owned.getInt("cycles") == 50 && owned.getInt("callbackFailures") == 0
                && owned.getBoolean("unloaded") && owned.getBoolean("reloaded"), "OWNED_ELF_HOOK");
            require(!maps.contains(fixture.getAbsolutePath()), "FIXTURE_LEASE_NOT_RELEASED");
            require(report.getJSONObject("art").getBoolean("observationPassed"), "ART_OBSERVATION");
            require(report.has("artNative") && report.getJSONObject("artNative").getBoolean("passed"), "ART_NATIVE_BINDING");
            require(report.has("artManaged") && report.getJSONObject("artManaged").getBoolean("passed"), "ART_MANAGED_OWNED");
            report.put("passed", true);
        } catch (Throwable error) {
            try { report.put("passed", false).put("error", error.toString()); }
            catch (Exception ignored) { /* org.json values above are ordinary strings. */ }
        }
        if (!ownsDirectory) {
            runOnUiThread(() -> view.setText("Run directory already exists or cannot be created"));
            return;
        }
        try {
            // A second invocation with the same runId must not overwrite its evidence.
            publishResult(directory, report.toString(2) + "\n");
            runOnUiThread(() -> view.setText("Self-hook probe " + (report.optBoolean("passed") ? "PASS" : "FAIL")
                + "\n" + runId + "\nSee phase results; product migration remains pending"));
        } catch (Exception error) {
            runOnUiThread(() -> view.setText("Evidence write failed: " + error));
        }
    }

}
