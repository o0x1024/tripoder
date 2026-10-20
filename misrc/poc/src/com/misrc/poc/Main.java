package com.misrc.poc;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.util.Log;

public class Main extends Activity {
    static final String TAG = "MISRC";
    // 被测组件
    static final String[][] TARGETS = {
        {"com.miui.securitycenter", "com.miui.permcenter.clipboard.SecurityClipboardService"},
        {"com.miui.securitycenter", "com.xiaomi.security.xsof.MiSafetyDetectService"},
        {"com.miui.securitycenter", "com.xiaomi.game.predownload.service.PreDownloadService"},
        {"com.xiaomi.xmsf", "com.xiaomi.xmsf.push.service.XMPushService"},
        {"com.xiaomi.aiservice", "com.xiaomi.aiservice.settings.AppLLMService"},
    };
    int idx = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        next();
    }

    void next() {
        if (idx >= TARGETS.length) { Log.i(TAG, "全部测试完成"); finish(); return; }
        String pkg = TARGETS[idx][0], svc = TARGETS[idx][1];
        idx++;
        Log.i(TAG, "########## 测试 " + pkg + "/" + svc);
        Intent i = new Intent();
        i.setClassName(pkg, svc);
        boolean ok;
        try { ok = bindService(i, conn, Context.BIND_AUTO_CREATE); }
        catch (Throwable t) { Log.w(TAG, "bindService 抛异常: " + t); ok = false; }
        Log.i(TAG, "bindService=" + ok);
        if (!ok) { new Handler().postDelayed(new Runnable(){public void run(){ next(); }}, 500); }
    }

    ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder binder) {
            String real = "?";
            try { real = binder.getInterfaceDescriptor(); } catch (Throwable t) {}
            Log.i(TAG, "  onBind 返回非null! 描述符=" + real);
            int allowed = 0, denied = 0;
            for (int code = 1; code <= 40; code++) {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    if (real != null) d.writeInterfaceToken(real);
                    boolean t = binder.transact(code, d, r, 0);
                    r.setDataPosition(0);
                    if (!t) continue;
                    int avail = r.dataAvail();
                    if (avail >= 4) {
                        int flag = r.readInt();
                        if (flag != 0) {
                            String msg = null;
                            try { msg = r.readString(); } catch (Throwable x) {}
                            denied++;
                            if (code <= 3) Log.i(TAG, "    code=" + code + " 拒绝: " + msg);
                        } else { allowed++; Log.i(TAG, "    code=" + code + " *** 成功返回数据 avail=" + avail); }
                    }
                } catch (Throwable x) {
                    if (code <= 3) Log.w(TAG, "    code=" + code + " " + x.getClass().getSimpleName());
                } finally { d.recycle(); r.recycle(); }
            }
            Log.i(TAG, "  统计: 拒绝=" + denied + " 放行=" + allowed);
            try { unbindService(this); } catch (Throwable t) {}
            new Handler().postDelayed(new Runnable(){public void run(){ next(); }}, 400);
        }
        public void onServiceDisconnected(ComponentName n) {}
    };
}
