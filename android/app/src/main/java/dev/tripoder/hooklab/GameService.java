package dev.tripoder.hooklab;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Hosts the baseline runtime in an ordinary secondary application process. */
public final class GameService extends Service {
    static final int PREPARE=1,STOP=2;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.what==STOP){android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(message.what!=PREPARE||message.replyTo==null)return true;
        Messenger reply=message.replyTo;String project=message.getData().getString("projectId","");
        boolean fresh=message.getData().getBoolean("freshGameProcess",false);
        worker.execute(()->{
            JSONObject result=new JSONObject();
            try{
                GameRuntime runtime=GameRuntime.current;
                if(runtime!=null&&(!runtime.projectId.equals(project)||(fresh&&runtime.application!=null)))result.put("restartRequired",true);
                else{
                    if(runtime==null){runtime=new GameRuntime(this,project);GameRuntime.current=runtime;runtime.prepare();}
                    if(!runtime.prepared)throw new IllegalStateException("上一次初始化失败，请重新启动游戏进程");
                    result.put("ready",true).put("report",runtime.report);
                }
            }catch(Exception|LinkageError error){if(GameRuntime.current!=null)GameRuntime.current.failure(error);try{result.put("error",error.toString());}catch(JSONException ignored){}}
            Message response=Message.obtain();Bundle bundle=new Bundle();bundle.putString("result",result.toString());response.setData(bundle);try{reply.send(response);}catch(RemoteException ignored){}
        });return true;
    }));
    @Override public IBinder onBind(Intent intent){return inbox.getBinder();}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
