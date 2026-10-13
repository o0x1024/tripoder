package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DiagnosticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state, output;
    private EditText message, delay;
    private LinearLayout controls;
    private volatile boolean ready;
    private static final String session = UUID.randomUUID().toString();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("workbench", false)) {
            startActivity(new Intent(this, ScriptActivity.class).putExtra("selfTest", getIntent().getBooleanExtra("selfTest", false))
                .putExtra("autoDelaySeconds", getIntent().getIntExtra("autoDelaySeconds", 0)));
            finish(); return;
        }
        if (getIntent().getBooleanExtra("guest", false)) {
            startActivity(new Intent(this, GuestActivity.class)
                .putExtra("autoDelaySeconds", getIntent().getIntExtra("autoDelaySeconds", 0))
                .putExtra("component", getIntent().getBooleanExtra("component", false))
                .putExtra("verifyRestart", getIntent().getBooleanExtra("verifyRestart", false)));
            finish(); return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout column=ProductUi.screen(this,"宿主诊断","开发者工具 · 自检会临时替换测试方案");
        state = text("正在加载内嵌代理…", 20); column.addView(state);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); column.addView(controls);
        button("返回应用首页", this::finish);
        button("脚本与 AI 接口自检", () -> startActivity(new Intent(this, ScriptActivity.class).putExtra("selfTest", true)));
        button("运行完整自检", () -> submit(this::selfTest));
        button("打开独立 APK 客体实验", () -> startActivity(new Intent(this, GuestActivity.class)));
        button("打开 TypeScript 与 AI 工作台", () -> startActivity(new Intent(this, ScriptActivity.class)));
        button("15 秒后自检（可拔掉 USB）", () -> {
            state.setText("15 秒后在手机本地运行，可断开 USB");
            main.postDelayed(() -> submit(this::selfTest), 15000);
        });
        message=ProductUi.field(controls,"Java 返回前缀","","custom:",false);
        delay=ProductUi.field(controls,"Native 增量 0–10000","","900",false);
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        button("应用参数并开启 Hook", () -> {
            String value = message.getText().toString(), number = delay.getText().toString();
            submit(() -> {
                JSONObject params = new JSONObject().put("message", value).put("delayMs", Integer.parseInt(number));
                checked(new JSONObject().put("op", "update").put("params", params));
                checked(new JSONObject().put("op", "enable"));
                showCalls("已开启 Hook");
            });
        });
        button("调用目标函数", () -> submit(() -> showCalls("函数结果")));
        button("回滚上一组参数", () -> submit(() -> { command("rollback"); showCalls("已回滚参数"); }));
        button("卸载 Hook 并验证原函数", () -> submit(() -> { command("disable"); showCalls("已卸载 Hook"); }));
        output = text("手动运行自检会暂时替换测试方案。\n测试结果保存在本机应用目录。", 14);
        output.setTextIsSelectable(true); column.addView(output);
        enableControls(false);
        worker.execute(() -> {
            try {
                // Load targets before the agent; APK identity comes from the actual installed APK.
                TestTarget.nativeDelay(3);
                JSONObject identity = new JSONObject().put("session", session).put("target", new JSONObject()
                    .put("packageName", getPackageName()).put("versionCode", Long.toString(getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode()))
                    .put("apkSha256", sha256(getApplicationInfo().sourceDir)));
                AgentBridge.observedIdentity = identity.toString();
                System.loadLibrary("gadget");
                long deadline = SystemClock.elapsedRealtime() + 20000;
                while (!new JSONObject(AgentBridge.dispatch("{\"op\":\"status\"}")).optBoolean("ok")) {
                    if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("代理未就绪，查看 Frida 日志");
                    Thread.sleep(100);
                }
                ready = true;
                int seconds = Math.max(0, Math.min(60, getIntent().getIntExtra("autoDelaySeconds", 0)));
                main.post(() -> {
                    enableControls(true);
                    state.setText("诊断已就绪");
                    if (getIntent().hasExtra("autoDelaySeconds")) main.postDelayed(() -> submit(this::selfTest), seconds * 1000L);
                });
            } catch (Throwable error) { failure(error); }
        });
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(27, 40, 60)); view.setPadding(0, 8, 0, 8); return view;
    }
    private void button(String label, Runnable action) {
        ProductUi.add(controls,ProductUi.button(this,label,false,action),10);
    }
    private void enableControls(boolean enabled) {
        for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).setEnabled(enabled);
    }
    private interface Operation { void run() throws Exception; }
    private void submit(Operation operation) {
        if (!ready) return;
        enableControls(false);
        worker.execute(() -> {
            try { operation.run(); } catch (Throwable error) { failure(error); }
            finally { main.post(() -> enableControls(true)); }
        });
    }
    private void failure(Throwable error) {
        Log.e("Tripoder", "Operation failed", error);
        main.post(() -> { state.setText("失败：" + error.getMessage()); output.setText(Log.getStackTraceString(error)); });
    }
    private JSONObject checked(JSONObject input) throws Exception {
        JSONObject result = new JSONObject(AgentBridge.dispatch(input.toString()));
        if (!result.optBoolean("ok")) throw new IllegalStateException(result.toString());
        return result;
    }
    private JSONObject command(String op) throws Exception { return checked(new JSONObject().put("op", op)); }
    private void showCalls(String heading) throws Exception {
        String javaResult = TestTarget.greet("phone"); int nativeResult = TestTarget.nativeDelay(3);
        JSONObject status = command("status");
        String details = "Java → " + javaResult + "\nNative → " + nativeResult + "\n版本 → " + status.getInt("revision")
            + "\n命中 Java / Native → " + status.getInt("javaHits") + " / " + status.getInt("nativeHits")
            + "\nNative 指令已变化 → " + status.getBoolean("nativeCodeChanged");
        main.post(() -> { state.setText(heading); output.setText(details); });
    }
    private void check(JSONArray checks, String name, boolean pass, Object actual) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", pass).put("actual", actual));
        if (!pass) throw new IllegalStateException(name + ": " + actual);
    }
    private void pair(JSONArray checks, String phase, String javaExpected, int nativeExpected) throws Exception {
        String javaActual = TestTarget.greet("phone"); int nativeActual = TestTarget.nativeDelay(3);
        check(checks, phase + "/Java", javaExpected.equals(javaActual), javaActual);
        check(checks, phase + "/Native", nativeExpected == nativeActual, nativeActual);
    }
    private void selfTest() throws Exception {
        main.post(() -> state.setText("正在手机本地执行真实 Hook 自检…"));
        JSONObject report = new JSONObject(); JSONArray checks = new JSONArray();
        report.put("session", session).put("startedAtMs", System.currentTimeMillis()).put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid()).put("model", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("debuggable", (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("debuggerConnected", Debug.isDebuggerConnected()).put("checks", checks);
        boolean passed = false;
        try {
            check(checks, "ordinary-uid", android.os.Process.myUid() >= 10000, android.os.Process.myUid());
            check(checks, "non-debuggable", !report.getBoolean("debuggable"), report.getBoolean("debuggable"));
            check(checks, "no-debugger", !Debug.isDebuggerConnected(), Debug.isDebuggerConnected());
            command("disable"); pair(checks, "baseline", "original:phone", 10);
            // Calls warm the same ordinary Java and JNI paths used after installation.
            for (int i = 0; i < 20000; i++) { TestTarget.greet("warmup"); TestTarget.nativeDelay(i); }
            checked(new JSONObject().put("op", "update").put("params", new JSONObject().put("message", "hook:").put("delayMs", 500)));
            JSONObject initial = command("enable");
            pair(checks, "enabled", "hook:phone", 503);
            check(checks, "native-machine-code-patched", initial.getBoolean("nativeCodeChanged"), initial.getBoolean("nativeCodeChanged"));
            checked(new JSONObject().put("op", "update").put("params", new JSONObject().put("message", "live:").put("delayMs", 900)));
            pair(checks, "hot-update", "live:phone", 903);
            int revision = command("status").getInt("revision");
            JSONObject invalid = new JSONObject(AgentBridge.dispatch("{\"op\":\"update\",\"params\":{\"message\":\"bad:\",\"delayMs\":-1}}"));
            check(checks, "invalid-update-rejected", !invalid.getBoolean("ok"), invalid);
            check(checks, "failed-update-keeps-revision", command("status").getInt("revision") == revision, revision);
            pair(checks, "after-rejection", "live:phone", 903);
            command("rollback"); pair(checks, "rollback", "hook:phone", 503);
            boolean stress = true;
            for (int i = 0; i < 1000; i++) {
                if (!TestTarget.greet("phone").equals("hook:phone") || TestTarget.nativeDelay(3) != 503) { stress = false; break; }
            }
            check(checks, "1000-paired-hook-calls", stress, stress);
            JSONObject hits = command("status");
            check(checks, "both-callbacks-hit", hits.getInt("javaHits") >= 1004 && hits.getInt("nativeHits") >= 1004, hits);
            JSONObject off = command("disable"); pair(checks, "uninstalled", "original:phone", 10);
            check(checks, "native-machine-code-restored", !off.getBoolean("nativeCodeChanged"), off.getBoolean("nativeCodeChanged"));
            command("enable"); pair(checks, "reinstalled", "hook:phone", 503);
            command("disable"); pair(checks, "final-original", "original:phone", 10);
            passed = true;
        } catch (Throwable error) {
            report.put("error", Log.getStackTraceString(error));
            Log.e("Tripoder", "Self-test failed", error);
        } finally {
            try { report.put("finalAgent", command("disable")); }
            catch (Throwable cleanup) { passed = false; report.put("cleanupError", cleanup.toString()); }
        }
        report.put("passed", passed).put("finishedAtMs", System.currentTimeMillis());
        String json = report.toString(2);
        save(new File(getFilesDir(), "last-result.json"), json);
        File external = getExternalFilesDir(null);
        if (external != null) save(new File(external, "last-result.json"), json);
        Log.i("Tripoder", "SELF_TEST passed=" + passed + " checks=" + checks.length() + " session=" + session);
        StringBuilder readable = new StringBuilder();
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            readable.append(c.getBoolean("passed") ? "✓ " : "✗ ").append(c.getString("name")).append('\n');
        }
        readable.append("\nUID: ").append(report.getInt("uid")).append(" · debuggable: ").append(report.getBoolean("debuggable"));
        readable.append("\n结果: Android/data/").append(getPackageName()).append("/files/last-result.json");
        if (!passed) readable.append("\n").append(report.optString("error"));
        final boolean success = passed;
        main.post(() -> { state.setText((success ? "PASS" : "FAIL") + " · " + checks.length() + " 项检查"); output.setText(readable.toString()); });
    }
    private static void save(File file, String text) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
