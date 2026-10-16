package dev.tripoder.vmprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;

/** Explicit local validation UI. Never resolves or starts a host application. */
public final class VmActivity extends Activity {
    private TextView status;
    private GuestDisplay display;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 64, 24, 24);
        status = new TextView(this); status.setTextIsSelectable(true); status.setText("ARM64 Android 软件虚拟机验证\n无网络、无宿主应用桥接。请先准备固定摘要的镜像。"); layout.addView(status);
        button(layout, "准备并启动客体", this::start);
        button(layout, "读取客体系统证据", () -> command("echo TRIPODER_GUEST_EVIDENCE_BEGIN; id; uname -a; getprop sys.boot_completed; getprop ro.build.version.release; getprop ro.product.cpu.abi; service check activity; service check package; service check SurfaceFlinger; pm path android; dumpsys SurfaceFlinger | head -n 40; echo TRIPODER_GUEST_EVIDENCE_END"));
        button(layout, "保存客体画面", () -> background(() -> VmRun.current.snapshot()));
        button(layout, "停止虚拟机", () -> background(() -> VmRun.current.stop()));
        display = new GuestDisplay(this);
        layout.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        new File(getExternalFilesDir(null), "import").mkdirs();
        route(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); route(intent); }
    private void route(Intent intent) {
        String action = intent.getStringExtra("probeAction");
        if ("start".equals(action)) start();
        else if ("inspect".equals(action)) command("echo TRIPODER_GUEST_EVIDENCE_BEGIN; id; uname -a; getprop sys.boot_completed; getprop ro.build.version.release; getprop ro.product.cpu.abi; service check activity; service check package; service check SurfaceFlinger; pm path android; echo TRIPODER_GUEST_EVIDENCE_END");
        else if ("snapshot".equals(action)) background(() -> VmRun.current.snapshot());
        else if ("stop".equals(action)) background(() -> VmRun.current.stop());
    }
    private void button(LinearLayout layout, String title, Runnable run) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(view -> run.run()); layout.addView(button);
    }
    private void start() {
        if (VmRun.current != null) { update("本进程已经创建运行实例，请停止后重新打开验证应用。"); return; }
        try {
            VmRun.current = new VmRun(this, this::update);
            VmRun.current.onFrame = file -> {
                try { display.update(file); } catch (Exception error) { update("画面读取失败：" + error); }
            };
        }
        catch (Exception error) { update(error.toString()); return; }
        background(() -> VmRun.current.start(getApplicationContext()));
    }
    private void command(String text) { background(() -> VmRun.current.guestCommand(text)); }
    private interface Work { void run() throws Exception; }
    private void background(Work work) {
        new Thread(() -> { try { work.run(); } catch (Exception | LinkageError error) { update("验证失败：" + error); android.util.Log.e("TripoderVM", "Probe failed", error); } }, "vm-probe").start();
    }
    private void update(String text) { runOnUiThread(() -> status.setText(text)); }
    @Override protected void onDestroy() { if (display != null) display.close(); super.onDestroy(); }
}
