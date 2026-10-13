package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.*;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GuestActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private GuestClient client;
    private TextView state, output;
    private LinearLayout controls;
    private EditText prefix, delay;
    private JSONObject lastReport;
    private static final int IMPORT = 71;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout column=ProductUi.screen(this,"客体与进程诊断","开发者工具 · 自检会临时替换当前客体方案");
        state = text("连接客体进程…", 20); column.addView(state);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); column.addView(controls);
        button("运行客体自检", () -> runTest(false));
        button("打开客体真实界面", () -> client.send(GuestService.EDITOR, bundle(), result -> {
            if (result.has("error")) { showError(result.optString("error")); return; }
            startActivity(new Intent(this, GuestStubActivity.class));
        }));
        button("客体文件与设备字段", () -> startActivity(new Intent(this, GuestToolsActivity.class)));
        button("15 秒后自检（可拔掉 USB）", () -> {
            busy(true); state.setText("15 秒后自主运行，可拔掉 USB"); main.postDelayed(() -> runTest(false), 15000);
        });
        button("重启客体并重新验证", () -> restartAndRun());
        button("导入测试 APK", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
            startActivityForResult(intent, IMPORT);
        });
        prefix=ProductUi.field(controls,"Java 返回前缀","","guest-ui:",false);
        delay=ProductUi.field(controls,"Native 增量 0–10000","","1200",false);
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        button("应用客体参数并调用", () -> {
            try {
                Bundle data = bundle(); data.putString("params", new JSONObject().put("message", prefix.getText().toString())
                    .put("delayMs", Integer.parseInt(delay.getText().toString())).toString());
                request(GuestService.UPDATE, data);
            } catch (Exception error) { showError(error.toString()); }
        });
        button("回滚客体参数", () -> request(GuestService.ROLLBACK, bundle()));
        button("卸载客体 Hook 并调用", () -> request(GuestService.DISABLE, bundle()));
        output = text("客体尚未运行。独立进程共享宿主 UID，不是权限隔离沙箱。", 14);
        output.setTextIsSelectable(true); column.addView(output);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        busy(true); client = new GuestClient(this);
        client.connect(() -> {
            if (getIntent().getBooleanExtra("component", false)) {
                client.send(GuestService.EDITOR, bundle(), result -> {
                    busy(false);
                    if (result.has("error")) { showError(result.optString("error")); return; }
                    startActivity(new Intent(this, GuestStubActivity.class).putExtra("componentTest", true)
                        .putExtra("hostPid", android.os.Process.myPid())
                        .putExtra("autoDelaySeconds", getIntent().getIntExtra("autoDelaySeconds", 0)));
                }); return;
            }
            if (!getIntent().hasExtra("autoDelaySeconds")) {
                request(GuestService.SAMPLE, bundle()); return;
            }
            int seconds = Math.max(0, Math.min(60, getIntent().getIntExtra("autoDelaySeconds", 0)));
            state.setText("客体进程已连接；" + seconds + " 秒后自检");
            main.postDelayed(() -> runTest(getIntent().getBooleanExtra("verifyRestart", false)), seconds * 1000L);
        });
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xff1b283c);
        view.setPadding(0, 8, 0, 8); return view;
    }
    private void button(String label, Runnable action) {
        ProductUi.add(controls,ProductUi.button(this,label,false,action),10);
    }
    private void busy(boolean active) { for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).setEnabled(!active); }
    private Bundle bundle() { Bundle data = new Bundle(); data.putInt("hostPid", android.os.Process.myPid()); return data; }
    private void showError(String message) { state.setText("失败"); output.setText(message); busy(false); }
    private void request(int operation, Bundle data) {
        busy(true);
        client.send(operation, data, result -> {
            busy(false);
            if (result.has("error")) { showError(result.optString("error")); return; }
            JSONObject agent = result.optJSONObject("agent");
            state.setText(agent != null && agent.optBoolean("enabled") ? "客体 Hook 已开启" : "客体 Hook 已卸载");
            output.setText("客体 PID: " + result.optInt("pid") + "\nJava → " + result.optString("java")
                + "\nNative → " + result.optInt("native") + "\nNative 指令变化 → " + (agent != null && agent.optBoolean("nativeCodeChanged")));
        });
    }
    private void runTest(boolean restartAfter) {
        busy(true); state.setText("正在客体进程中运行…");
        client.send(GuestService.RUN, bundle(), report -> {
            render(report);
            if (report.optBoolean("passed")) {
                lastReport = report;
                if (restartAfter) restartAndRun();
            } else lastReport = null;
        });
    }
    private void restartAndRun() {
        final JSONObject previous = lastReport;
        busy(true); state.setText("正在重启客体进程…");
        client.send(GuestService.RESTART, bundle(), response -> {
            if (response.has("error")) { showError(response.optString("error")); return; }
            client.close();
            main.postDelayed(() -> {
                client = new GuestClient(this);
                client.connect(() -> client.send(GuestService.RUN, bundle(), next -> {
                    render(next);
                    if (previous == null) {
                        if (next.optBoolean("passed")) lastReport = next;
                        return;
                    }
                    try {
                        boolean passed = previous.getBoolean("passed") && next.optBoolean("passed")
                            && previous.getInt("pid") != next.getInt("pid")
                            && !previous.getString("session").equals(next.getString("session"))
                            && previous.getInt("hostPid") == next.getInt("hostPid")
                            && next.getInt("persistentCounter") == previous.getInt("persistentCounter") + 1
                            && !previous.getJSONObject("finalAgent").getJSONObject("evidence").getString("processInstanceId")
                                .equals(next.getJSONObject("finalAgent").getJSONObject("evidence").getString("processInstanceId"));
                        JSONObject restart = new JSONObject().put("passed", passed).put("before", previous).put("after", next);
                        File directory = getExternalFilesDir(null);
                        if (directory != null) try (FileOutputStream stream = new FileOutputStream(new File(directory, "guest-restart-result.json"))) {
                            stream.write(restart.toString(2).getBytes(StandardCharsets.UTF_8));
                        }
                        if (!passed) throw new IllegalStateException("Guest restart invariants failed");
                        lastReport = next;
                        state.setText("PASS · 客体重启及 " + next.getJSONArray("checks").length() + " 项检查");
                        output.append("\n重启 PID: " + previous.getInt("pid") + " → " + next.getInt("pid")
                            + "\n宿主 PID 不变，客体数据保留，旧进程证据已更换。");
                    } catch (Exception error) { showError(error.toString()); }
                }));
            }, 750);
        });
    }
    private void render(JSONObject report) {
        busy(false);
        if (!report.optBoolean("passed")) { showError(report.optString("error", report.toString())); return; }
        JSONArray checks = report.optJSONArray("checks");
        state.setText("PASS · 客体 " + checks.length() + " 项检查");
        StringBuilder details = new StringBuilder("宿主 PID: " + report.optInt("hostPid") + "\n客体 PID: " + report.optInt("pid")
            + "\n共享 UID: " + report.optInt("uid") + "\n持久化计数: " + report.optInt("persistentCounter") + "\n");
        for (int i = 0; i < checks.length(); i++) details.append("✓ ").append(checks.optJSONObject(i).optString("name")).append('\n');
        output.setText(details.toString());
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request != IMPORT || result != RESULT_OK || intent == null || intent.getData() == null) return;
        busy(true); state.setText("校验并导入测试 APK…");
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(intent.getData())) {
                if (input == null) throw new IOException("Cannot open selected APK");
                new GuestStore(this).importApk(input);
                main.post(() -> runTest(false));
            } catch (Exception error) { main.post(() -> showError(error.toString())); }
        });
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null); if (client != null) client.close(); io.shutdown(); super.onDestroy();
    }
}
