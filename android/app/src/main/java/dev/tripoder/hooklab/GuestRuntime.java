package dev.tripoder.hooklab;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import dalvik.system.DexClassLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

final class GuestRuntime {
    private final Context context;
    private String projectId=ProjectStore.DEFAULT;
    String projectId(){return projectId;}
    JSONObject selectProject(String id) throws Exception {
        ProjectStore projects=new ProjectStore(context);projects.initialize();projects.get(id);
        if(!projects.runnable(id))throw new IllegalStateException("此应用尚未接入运行器，请先查看兼容性报告");
        if(loader!=null&&!projectId.equals(id))return new JSONObject().put("restartRequired",true).put("activeProject",projectId);
        projectId=id;return new JSONObject().put("projectId",id);
    }
    void requireProject(String id){if(!projectId.equals(id))throw new IllegalStateException("项目已切换，请重新打开当前项目");}
    private final String session = UUID.randomUUID().toString();
    private GuestStore store;
    private File apk, library, data;
    private DexClassLoader loader;
    private Method greet, delay, counter;
    private boolean hostClassAbsent;
    private android.content.res.loader.ResourcesLoader resourcesLoader;
    ClassLoader componentLoader() { return loader; }
    File componentData() { return data; }
    String guestHash() { return store.expectedHash; }
    void attachComponent(android.app.Activity activity) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Component resources require Android 11+");
        if (resourcesLoader == null) {
            resourcesLoader = new android.content.res.loader.ResourcesLoader();
            try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(apk, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                resourcesLoader.addProvider(android.content.res.loader.ResourcesProvider.loadFromApk(fd));
            }
        }
        activity.getResources().addLoaders(resourcesLoader);
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        if (AgentBridge.componentApplication == null) {
            android.content.Context isolated = context.createConfigurationContext(new android.content.res.Configuration(context.getResources().getConfiguration()));
            isolated.getResources().addLoaders(resourcesLoader);
            android.app.Application application = new android.app.Instrumentation().newApplication(loader, "dev.tripoder.fixture.FixtureApplication",
                new GuestContext(context, loader, isolated.getResources(), data));
            // Activity dispatches later lifecycle events through getApplication(),
            // which now returns this guest Application. Keep the host controller registered.
            application.registerActivityLifecycleCallbacks((TripoderApplication) context.getApplicationContext());
            AgentBridge.componentApplication = application;
            new android.app.Instrumentation().callApplicationOnCreate(application);
        }
    }
    JSONObject files(String operation, String path, String content) throws Exception { load(); return new GuestFiles(data).operate(operation, path, content); }
    JSONObject environment(String profile) throws Exception {
        load(); JSONObject input = new JSONObject().put("op", "environment").put("profile", new JSONObject(profile));
        JSONObject result = new JSONObject(AgentBridge.dispatch(input.toString()));
        if (!result.optBoolean("ok")) throw new IllegalArgumentException(result.optString("error"));
        return result;
    }

    GuestRuntime(Context context) { this.context = context; }

    private void load() throws Exception {
        if (loader != null) return;
        if (!Application.getProcessName().endsWith(":guest")) throw new IllegalStateException("Guest execution requires the guest process");
        store = new GuestStore(context);
        apk = store.ensureFixture(); library = store.extractLibrary(apk); data = new ProjectStore(context).data(projectId);
        try { context.getClassLoader().loadClass(GuestStore.CLASS); }
        catch (ClassNotFoundException expected) { hostClassAbsent = true; }
        if (!hostClassAbsent) throw new IllegalStateException("Guest class was accidentally compiled into the host");
        DexClassLoader candidate = new DexClassLoader(apk.getAbsolutePath(), context.getCodeCacheDir().getAbsolutePath(),
            library.getParent(), context.getClassLoader());
        Class<?> target = candidate.loadClass(GuestStore.CLASS);
        if (target.getClassLoader() != candidate) throw new IllegalStateException("Wrong guest ClassLoader");
        greet = target.getMethod("greet", String.class);
        delay = target.getMethod("nativeDelay", int.class);
        counter = target.getMethod("incrementCounter", String.class);
        delay.invoke(null, 3); // Resolve JNI and initialize the guest library before attaching Hooks.
        JSONObject identity = new JSONObject().put("mode", "guest").put("session", session)
            .put("target", new JSONObject().put("packageName", GuestStore.PACKAGE).put("versionCode", Integer.toString(GuestStore.VERSION)).put("apkSha256", store.expectedHash));
        AgentBridge.guestLoader = candidate;
        AgentBridge.observedIdentity = identity.toString();
        System.loadLibrary("gadget");
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!new JSONObject(AgentBridge.dispatch("{\"op\":\"status\"}")).optBoolean("ok")) {
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Guest agent did not become ready");
            Thread.sleep(50);
        }
        loader = candidate;
    }

    JSONObject command(String op, JSONObject params) throws Exception {
        load();
        JSONObject input = new JSONObject().put("op", op);
        if (params != null) input.put("params", params);
        JSONObject result = new JSONObject(AgentBridge.dispatch(input.toString()));
        if (!result.optBoolean("ok")) throw new IllegalStateException(result.toString());
        return result;
    }
    JSONObject editorState() throws Exception {
        JSONObject result = command("status", null);
        String source=new ProjectStore(context).read(projectId,"scripts/main.ts",result.getString("template"));
        return result.put("draft",source).put("projectId",projectId);
    }
    JSONObject script(String operation, String source, String expectedSession, boolean persist) throws Exception {
        load();
        JSONObject input = new JSONObject().put("op", operation).put("source", source).put("session", expectedSession);
        JSONObject result = new JSONObject(AgentBridge.dispatch(input.toString()));
        if (!result.optBoolean("ok")) throw new IllegalArgumentException(result.optString("error"));
        if (persist && operation.equals("deploy")) {
            ProjectStore projects=new ProjectStore(context);projects.write(projectId,"scripts/main.ts",source);
            projects.write(projectId,"scripts/deployed.ts",source);
            projects.event(projectId,"deployment",new JSONObject().put("session",session).put("revision",result.optInt("revision")));
        }
        return result;
    }
    JSONObject scriptTest(int hostPid) throws Exception {
        load(); command("reset", null);
        JSONObject report = new JSONObject().put("startedAtMs", System.currentTimeMillis()).put("session", session)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("hostPid", hostPid)
            .put("guestApkSha256", store.expectedHash).put("debuggerConnected", Debug.isDebuggerConnected())
            .put("debuggable", (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("hostApkSha256", GuestStore.sha256(new File(context.getApplicationInfo().sourceDir)));
        JSONArray checks = new JSONArray(); report.put("checks", checks); boolean passed = false;
        try {
            String source = command("status", null).getString("template");
            JSONObject checked = script("check", source, session, false);
            check(checks, "draft-prepares-without-activation", checked.getBoolean("checked") && !command("status", null).getBoolean("enabled"), checked);
            script("deploy", source, session, false); pair(checks, "source-v1", "script:PHONE", 706);
            script("deploy", source.replace("script:", "changed:").replace("* 2", "* 3"), session, false);
            pair(checks, "source-hot-replacement", "changed:PHONE", 709);
            command("rollback", null); pair(checks, "source-rollback", "script:PHONE", 706);
            int revision = command("status", null).getInt("revision"); boolean rejected = false;
            try { script("deploy", source.replace("setup(ctx) {", "setup(ctx) { if (false) { while(true) {} }"), session, false); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(checks, "unreachable-loop-rejected", rejected, rejected);
            check(checks, "rejected-source-keeps-revision", command("status", null).getInt("revision") == revision, revision);
            rejected = false;
            try { script("deploy", source, "old-process", false); } catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("STALE_EVIDENCE"); }
            check(checks, "stale-process-draft-rejected", rejected, rejected);
            pair(checks, "after-draft-rejection", "script:PHONE", 706);
            String phases = "import {definePlan} from '@abox/hook'; export default definePlan({id:'phases',target,params:{},setup(ctx){"
                + "ctx.java.method(symbols.greet.className,'greet',['java.lang.String']).before(({args})=>[String(args[0]).toUpperCase()]).after(({result})=>'after:'+result);"
                + "ctx.native.function(symbols.delay.moduleName,symbols.delay.symbol,{argumentTypes:['int32'],returnType:'int32',buildId:symbols.delay.buildId})"
                + ".before(({args})=>[Number(args[0])+1]).after(({result})=>Number(result)*2);}});";
            script("deploy", phases, session, false); pair(checks, "before-original-after", "after:guest-original:PHONE", 38);
            script("deploy", source.replace("params.message + String(args[0]).toUpperCase()", "globalThis.secret"), session, false);
            pair(checks, "blocked-global-fallback", "guest-original:phone", 706);
            check(checks, "script-error-recorded", command("status", null).getJSONArray("errors").length() > 0, true);
            command("disable", null); pair(checks, "script-uninstalled", "guest-original:phone", 16);
            check(checks, "script-native-bytes-restored", !command("status", null).getBoolean("nativeCodeChanged"), true);
            passed = true;
        } catch (Throwable error) { report.put("error", Log.getStackTraceString(error)); }
        finally { report.put("finalAgent", command("reset", null)); }
        report.put("passed", passed).put("finishedAtMs", System.currentTimeMillis());
        save(new File(context.getExternalFilesDir(null), "script-result.json"), report);
        return report;
    }
    JSONObject sample() throws Exception {
        load();
        return new JSONObject().put("java", greet.invoke(null, "phone")).put("native", delay.invoke(null, 3))
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("session", session).put("agent", command("status", null));
    }
    private void check(JSONArray checks, String name, boolean pass, Object actual) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", pass).put("actual", actual));
        if (!pass) throw new IllegalStateException(name + ": " + actual);
    }
    private void pair(JSONArray checks, String phase, String javaExpected, int nativeExpected) throws Exception {
        Object javaActual = greet.invoke(null, "phone"), nativeActual = delay.invoke(null, 3);
        check(checks, phase + "/Java", javaExpected.equals(javaActual), javaActual);
        check(checks, phase + "/Native", Integer.valueOf(nativeExpected).equals(nativeActual), nativeActual);
    }

    JSONObject selfTest(int hostPid) throws Exception {
        JSONObject report = new JSONObject().put("startedAtMs", System.currentTimeMillis()).put("session", session)
            .put("hostPid", hostPid).put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("processName", Application.getProcessName()).put("model", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("hostApkSha256", GuestStore.sha256(new File(context.getApplicationInfo().sourceDir)))
            .put("debuggable", (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("debuggerConnected", Debug.isDebuggerConnected()).put("securityIsolation", false);
        JSONArray checks = new JSONArray(); report.put("checks", checks);
        boolean passed = false;
        try {
            load();
            check(checks, "separate-guest-process", hostPid != Process.myPid() && Application.getProcessName().endsWith(":guest"), Process.myPid());
            check(checks, "ordinary-uid", Process.myUid() >= 10000, Process.myUid());
            check(checks, "non-debuggable", !report.getBoolean("debuggable"), report.getBoolean("debuggable"));
            check(checks, "no-debugger", !Debug.isDebuggerConnected(), Debug.isDebuggerConnected());
            check(checks, "guest-class-absent-from-host", hostClassAbsent, hostClassAbsent);
            check(checks, "dedicated-dex-classloader", greet.getDeclaringClass().getClassLoader() == loader, loader.toString());
            GuestStore.assertReadOnly(apk); GuestStore.assertReadOnly(library);
            check(checks, "read-only-apk-and-library", true, "mode 0400");
            check(checks, "unchanged-guest-apk", store.expectedHash.equals(GuestStore.sha256(apk)), GuestStore.sha256(apk));
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            String signer = GuestStore.hex(MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()));
            report.put("guestSignerSha256", signer).put("guestApkSha256", store.expectedHash).put("guestDataDirectory", data.getAbsolutePath());
            boolean rejected = false;
            try { store.importApk(new ByteArrayInputStream(new byte[]{0, 1, 2, 3})); }
            catch (IllegalArgumentException expected) { rejected = expected.getMessage().startsWith("APK_DIGEST_MISMATCH"); }
            check(checks, "invalid-import-rejected", rejected, rejected);
            check(checks, "invalid-import-preserves-active-apk", store.expectedHash.equals(GuestStore.sha256(apk)), GuestStore.sha256(apk));
            command("reset", null); pair(checks, "baseline", "guest-original:phone", 16);
            int value = (Integer) counter.invoke(null, data.getAbsolutePath());
            report.put("persistentCounter", value);
            check(checks, "guest-writes-own-data-directory", Integer.toString(value).equals(new String(Files.readAllBytes(new File(data, "counter.txt").toPath()), StandardCharsets.UTF_8)), value);
            command("update", new JSONObject().put("message", "guest-hook:").put("delayMs", 500));
            JSONObject on = command("enable", null); pair(checks, "enabled", "guest-hook:phone", 503);
            check(checks, "native-machine-code-patched", on.getBoolean("nativeCodeChanged"), on.getBoolean("nativeCodeChanged"));
            check(checks, "evidence-bound-to-guest-apk", on.getJSONObject("evidence").getJSONObject("target").getString("apkSha256").equals(store.expectedHash), store.expectedHash);
            command("update", new JSONObject().put("message", "guest-live:").put("delayMs", 900));
            pair(checks, "hot-update", "guest-live:phone", 903);
            JSONObject bad = new JSONObject(AgentBridge.dispatch("{\"op\":\"update\",\"params\":{\"delayMs\":-1}}"));
            check(checks, "invalid-parameters-rejected", !bad.getBoolean("ok"), bad.getBoolean("ok"));
            pair(checks, "after-rejection", "guest-live:phone", 903);
            command("rollback", null); pair(checks, "rollback", "guest-hook:phone", 503);
            JSONObject before = command("status", null);
            boolean stress = true;
            for (int i = 0; i < 1000; i++) {
                if (!"guest-hook:phone".equals(greet.invoke(null, "phone")) || !Integer.valueOf(503).equals(delay.invoke(null, 3))) { stress = false; break; }
            }
            JSONObject hits = command("status", null);
            check(checks, "1000-real-paired-calls", stress && hits.getInt("javaHits") - before.getInt("javaHits") == 1000
                && hits.getInt("nativeHits") - before.getInt("nativeHits") == 1000, hits);
            JSONObject off = command("disable", null); pair(checks, "uninstalled", "guest-original:phone", 16);
            check(checks, "native-machine-code-restored", !off.getBoolean("nativeCodeChanged"), off.getBoolean("nativeCodeChanged"));
            command("enable", null); pair(checks, "reinstalled", "guest-hook:phone", 503);
            check(checks, "guest-apk-still-byte-identical", store.expectedHash.equals(GuestStore.sha256(apk)), GuestStore.sha256(apk));
            passed = true;
        } catch (Throwable error) {
            report.put("error", Log.getStackTraceString(error)); Log.e("TripoderGuest", "Guest self-test failed", error);
        } finally {
            if (loader != null) {
                try { report.put("finalAgent", command("disable", null)); }
                catch (Throwable error) { passed = false; report.put("cleanupError", error.toString()); }
            }
        }
        report.put("passed", passed).put("finishedAtMs", System.currentTimeMillis());
        save(new File(context.getFilesDir(), "guest-result.json"), report);
        if (context.getExternalFilesDir(null) != null) save(new File(context.getExternalFilesDir(null), "guest-result.json"), report);
        Log.i("TripoderGuest", "SELF_TEST passed=" + passed + " checks=" + checks.length() + " pid=" + Process.myPid());
        return report;
    }
    private static void save(File file, JSONObject report) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }
}
