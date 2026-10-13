package dev.tripoder.hooklab;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;

public final class GuestService extends Service {
    static final int RUN = 1, SAMPLE = 2, UPDATE = 3, DISABLE = 4, ROLLBACK = 5, RESTART = 6;
    static final int EDITOR = 7, CHECK_SCRIPT = 8, DEPLOY_SCRIPT = 9, SCRIPT_TEST = 10;
    // JNI libraries belong to their loading ClassLoader for the process lifetime.
    // Preserve both runtime and serial queue across Service unbind/rebind. Explicit
    // guest restart (or OS process death) is the boundary that releases them.
    private static HandlerThread thread;
    private static GuestRuntime processRuntime;
    static GuestRuntime peekRuntime() { return processRuntime; }
    static final int FILE = 11, ENVIRONMENT = 12, SELECT_PROJECT = 13;
    private Messenger messenger;
    @Override public void onCreate() {
        super.onCreate();
        if (thread == null) {
            thread = new HandlerThread("guest-operations"); thread.start();
            processRuntime = new GuestRuntime(getApplicationContext());
        }
        GuestRuntime runtime = processRuntime;
        messenger = new Messenger(new Handler(thread.getLooper(), request -> {
            JSONObject response;
            boolean restart = false;
            try {
                String project=request.getData().getString("projectId",ProjectStore.DEFAULT);
                if(request.what!=SELECT_PROJECT)runtime.requireProject(project);
                switch (request.what) {
                    case SELECT_PROJECT: response=runtime.selectProject(project);break;
                    case RUN: response = runtime.selfTest(request.getData().getInt("hostPid")); break;
                    case EDITOR: response = runtime.editorState(); break;
                    case CHECK_SCRIPT: case DEPLOY_SCRIPT:
                        response = runtime.script(request.what == CHECK_SCRIPT ? "check" : "deploy",
                            request.getData().getString("source"), request.getData().getString("session"), true); break;
                    case SCRIPT_TEST: response = runtime.scriptTest(request.getData().getInt("hostPid")); break;
                    case FILE: response = runtime.files(request.getData().getString("operation"), request.getData().getString("path", ""), request.getData().getString("content", "")); break;
                    case ENVIRONMENT: response = runtime.environment(request.getData().getString("profile", "{}")); break;
                    case SAMPLE: response = runtime.sample(); break;
                    case UPDATE:
                        runtime.command("update", new JSONObject(request.getData().getString("params")));
                        runtime.command("enable", null); response = runtime.sample(); break;
                    case DISABLE: runtime.command("disable", null); response = runtime.sample(); break;
                    case ROLLBACK: runtime.command("rollback", null); response = runtime.sample(); break;
                    case RESTART:
                        if (!Application.getProcessName().endsWith(":guest")) throw new IllegalStateException("Refusing to terminate the host");
                        response = new JSONObject().put("restarting", true).put("pid", android.os.Process.myPid()); restart = true; break;
                    default: throw new IllegalArgumentException("Unknown guest command");
                }
            } catch (Throwable error) {
                response = new JSONObject();
                try { response.put("error", android.util.Log.getStackTraceString(error)); } catch (Exception ignored) { }
            }
            if (request.replyTo != null) {
                Message reply = Message.obtain(null, request.what); reply.arg1 = request.arg1;
                Bundle bundle = new Bundle(); bundle.putString("json", response.toString()); reply.setData(bundle);
                try { request.replyTo.send(reply); } catch (RemoteException ignored) { }
            }
            if (restart) new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
            return true;
        }));
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
}
