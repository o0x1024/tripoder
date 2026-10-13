package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Main-thread client; bounded request lifetime, correlation IDs and explicit unbinding. */
final class GuestClient implements AutoCloseable {
    private final Context context;
    private final String projectId;
    private String selectionError;
    private int restartAttempts;
    private boolean closed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Consumer<JSONObject>> pending = new HashMap<>();
    private final Map<Integer, Runnable> timeouts = new HashMap<>();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Consumer<JSONObject> callback = pending.remove(message.arg1);
        Runnable timeout = timeouts.remove(message.arg1); if (timeout != null) main.removeCallbacks(timeout);
        if (callback != null) {
            JSONObject result;
            try { result = new JSONObject(message.getData().getString("json")); }
            catch (Exception error) { result = error(error.toString()); }
            callback.accept(result);
        }
        return true;
    }));
    private Messenger service;
    private boolean bound;
    private int sequence;
    private Runnable connected;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if(closed||connected==null){if(bound){context.unbindService(connection);bound=false;}service=null;return;}
            service = new Messenger(binder);
            Bundle selection=new Bundle();selection.putString("projectId",projectId);
            send(GuestService.SELECT_PROJECT,selection,result->{
                if(result.optBoolean("restartRequired")&&restartAttempts++<2){
                    // Restart uses the currently selected owner's ID, not the incoming project.
                    Bundle restart=new Bundle();restart.putString("projectId",result.optString("activeProject",ProjectStore.DEFAULT));
                    send(GuestService.RESTART,restart,ignored->{
                        if(bound){context.unbindService(connection);bound=false;}service=null;
                        main.postDelayed(()->{if(!closed)bind();},700);
                    });return;
                }
                selectionError=result.has("error")?result.optString("error"):result.optBoolean("restartRequired")?"切换项目失败，请重新打开":null;
                Runnable callback=connected;connected=null;if(callback!=null)callback.run();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { service=null;selectionError="客体进程已结束，请重新打开项目";if(bound){context.unbindService(connection);bound=false;}failPending(selectionError); }
        @Override public void onBindingDied(ComponentName name) { service = null; failPending("Guest binding died; reopen the guest screen"); }
        @Override public void onNullBinding(ComponentName name) { service = null; failPending("Guest returned no binder"); }
    };
    GuestClient(Context context) { this(context,context instanceof android.app.Activity?ProjectStore.id((android.app.Activity)context):ProjectStore.DEFAULT); }
    GuestClient(Context context,String projectId){this.context=context;this.projectId=ProjectStore.validId(projectId);}
    void connect(Runnable callback) {
        closed=false;connected=callback;selectionError=null;bind();
    }
    private void bind(){
        bound = context.bindService(new Intent(context, GuestService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) throw new IllegalStateException("Cannot bind guest process");
    }
    void send(int operation, Bundle data, Consumer<JSONObject> callback) {
        if(selectionError!=null&&operation!=GuestService.SELECT_PROJECT){callback.accept(error(selectionError));return;}
        if(!data.containsKey("projectId"))data.putString("projectId",projectId);
        if (service == null) { callback.accept(error("Guest service is not connected")); return; }
        int id = ++sequence;
        Message message = Message.obtain(null, operation); message.arg1 = id; message.replyTo = replies; message.setData(data);
        pending.put(id, callback);
        Runnable timeout = () -> {
            Consumer<JSONObject> expired = pending.remove(id); timeouts.remove(id);
            if (expired != null) expired.accept(error("Guest request timed out"));
        };
        timeouts.put(id, timeout); main.postDelayed(timeout, 30000);
        try { service.send(message); }
        catch (RemoteException error) {
            pending.remove(id); timeouts.remove(id); main.removeCallbacks(timeout); callback.accept(error(error.toString()));
        }
    }
    private void failPending(String reason) {
        Map<Integer, Consumer<JSONObject>> callbacks = new HashMap<>(pending); pending.clear();
        for (Runnable timeout : timeouts.values()) main.removeCallbacks(timeout);
        timeouts.clear();
        for (Consumer<JSONObject> callback : callbacks.values()) callback.accept(error(reason));
    }
    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("error", message); } catch (Exception ignored) { }
        return result;
    }
    @Override public void close() {
        closed=true;main.removeCallbacksAndMessages(null); pending.clear(); timeouts.clear(); connected = null; service = null;
        if (bound) { context.unbindService(connection); bound = false; }
    }
}
