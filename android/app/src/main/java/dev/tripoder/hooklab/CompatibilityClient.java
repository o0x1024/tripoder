package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import org.json.*;
import java.util.function.Consumer;

/** Bounded, one-shot binding. Process death is a failed preflight, never a passed launch. */
final class CompatibilityClient implements AutoCloseable {
    private final Context context;private final String packageName;private Consumer<JSONObject> callback;
    private final Handler main=new Handler(Looper.getMainLooper());private boolean bound,closed;
    private final Runnable timeout=()->fail("兼容性探测超时，入口加载未通过验证");
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(closed)return true;
        try{finish(new JSONObject(message.getData().getString("report","{}")));}catch(JSONException error){fail("兼容性探测返回了无效报告");}return true;
    }));
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){if(closed)return;Message request=Message.obtain();Bundle data=new Bundle();data.putString("packageName",packageName);request.setData(data);request.replyTo=replies;try{new Messenger(binder).send(request);}catch(RemoteException error){fail("探测进程未响应");}}
        public void onServiceDisconnected(ComponentName name){fail("探测进程已退出，入口加载未通过验证");}
        public void onBindingDied(ComponentName name){fail("探测连接失效，请重新检查");}
        public void onNullBinding(ComponentName name){fail("无法连接探测进程");}
    };
    CompatibilityClient(Context context,String packageName,Consumer<JSONObject> callback){this.context=context.getApplicationContext();this.packageName=packageName;this.callback=callback;}
    void start(){try{bound=context.bindService(new Intent(context,CompatibilityService.class),connection,Context.BIND_AUTO_CREATE);if(!bound)fail("无法启动探测进程");else main.postDelayed(timeout,45000);}catch(Exception error){fail("无法启动探测进程："+error.getMessage());}}
    private void fail(String reason){try{finish(new JSONObject().put("error",reason));}catch(JSONException ignored){}}
    private void finish(JSONObject result){if(closed)return;Consumer<JSONObject> done=callback;close();if(done!=null)done.accept(result);}
    public void close(){if(closed)return;closed=true;callback=null;main.removeCallbacks(timeout);if(bound){bound=false;context.unbindService(connection);}}
}
