package com.misrc.poc;

import android.app.Service;
import android.content.*;
import android.os.*;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * 用 Service 触发（有完整上下文，允许 bindService），
 * 结果写入外部存储文件。
 */
public class Probe extends Service {

    static final String TAG = "MISRC";
    static final String OUT = "/sdcard/misrc_result.txt";

    static final String[][] TARGETS = {
        {"com.miui.securitycenter", "com.miui.permcenter.clipboard.SecurityClipboardService"},
        {"com.miui.securitycenter", "com.xiaomi.security.xsof.MiSafetyDetectService"},
        {"com.miui.securitycenter", "com.xiaomi.game.predownload.service.PreDownloadService"},
        {"com.xiaomi.xmsf", "com.xiaomi.xmsf.push.service.XMPushService"},
        {"com.xiaomi.aiservice", "com.xiaomi.aiservice.settings.AppLLMService"},
        {"com.miui.securitycenter", "com.miui.networkassistant.vpn.miui.MiuiVpnManageService"},
    };

    static final ArrayList<String> buf = new ArrayList<String>();
    static int idx = 0;
    Context ctx;
    ServiceConnection curConn;

    static void w(String s) {
        Log.i(TAG, s);
        synchronized (buf) { buf.add(s); }
    }

    static void flush() {
        try {
            FileOutputStream fo = new FileOutputStream(new File(OUT));
            OutputStreamWriter ow = new OutputStreamWriter(fo, "UTF-8");
            synchronized (buf) { for (String s : buf) ow.write(s + "\n"); }
            ow.flush(); ow.close(); fo.close();
        } catch (Throwable t) {
            Log.e(TAG, "flush 失败: " + t);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ctx = this;
        synchronized (buf) { buf.clear(); }
        idx = 0;
        w("=== MISRC 探测开始 ===");
        w("自身 uid=" + android.os.Process.myUid() + " pkg=" + getPackageName());
        w("SDK=" + Build.VERSION.SDK_INT);
        w("");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() { next(); }
        });
        return START_NOT_STICKY;
    }

    void next() {
        if (idx >= TARGETS.length) {
            w("");
            w("=== 全部完成 ===");
            flush();
            stopSelf();
            return;
        }
        final String pkg = TARGETS[idx][0], svc = TARGETS[idx][1];
        idx++;
        w("########## [" + idx + "/" + TARGETS.length + "] " + pkg);
        w("           " + svc);

        curConn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder binder) {
                String desc = "?";
                try { desc = binder.getInterfaceDescriptor(); } catch (Throwable t) {}
                w("  >> onBind 返回非 null，描述符=" + desc);
                probeBinder(binder, desc);
                try { unbindService(this); } catch (Throwable t) {}
                w("");
                advance();
            }
            public void onServiceDisconnected(ComponentName n) {}
        };

        Intent i = new Intent();
        i.setClassName(pkg, svc);
        boolean ok = false;
        try {
            ok = bindService(i, curConn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            w("  bindService 抛异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        w("  bindService=" + ok);

        if (!ok) {
            w("  （绑定失败，跳过）");
            w("");
            advance();
        } else {
            // 超时兜底：2.5 秒未连上则强制推进
            final int mark = idx;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    if (idx == mark) {
                        w("  （超时未连接，跳过）");
                        w("");
                        try { unbindService(curConn); } catch (Throwable t) {}
                        advance();
                    }
                }
            }, 2500);
        }
    }

    void advance() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() { next(); }
        }, 150);
    }

    static void probeBinder(IBinder binder, String desc) {
        int allowed = 0, denied = 0, empty = 0;
        for (int code = 1; code <= 40; code++) {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                if (desc != null && !"?".equals(desc)) d.writeInterfaceToken(desc);
                boolean t = binder.transact(code, d, r, 0);
                if (!t) { empty++; continue; }
                r.setDataPosition(0);
                int avail = r.dataAvail();
                if (avail >= 4) {
                    int flag = r.readInt();
                    if (flag != 0) {
                        String msg = null;
                        try { msg = r.readString(); } catch (Throwable x) {}
                        denied++;
                        if (denied <= 2) w("     code=" + code + " 拒绝: " + msg);
                    } else {
                        allowed++;
                        w("     code=" + code + " *** 放行 avail=" + avail);
                    }
                } else { empty++; }
            } catch (Throwable x) {
                if (code <= 2) w("     code=" + code + " 异常 " + x.getClass().getSimpleName());
            } finally { d.recycle(); r.recycle(); }
        }
        w("  >> 统计: 拒绝=" + denied + " 放行=" + allowed + " 空=" + empty);
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
