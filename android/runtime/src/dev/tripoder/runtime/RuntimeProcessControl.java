package dev.tripoder.runtime;

import android.content.Context;
import android.os.*;

/** One process control identity shared by the warm service and cold component entry. */
final class RuntimeProcessControl extends RuntimeWire.Endpoint {
    private static RuntimeProcessControl instance;
    private final Context context;
    private volatile int controllerPid;
    private String abortCapability;
    private RuntimeProcessControl(Context context){this.context=context;}
    static synchronized RuntimeProcessControl get(Context context){if(instance==null)instance=new RuntimeProcessControl(context.getApplicationContext());return instance;}
    synchronized void claim(int pid){if(pid<=0||controllerPid!=0)throw new SecurityException("WORKER_ALREADY_ATTACHED");controllerPid=pid;}
    synchronized void configureAbort(String value){if(value==null)return;if(value.equals(abortCapability))return;if(controllerPid!=0||abortCapability!=null||!value.matches("[0-9a-f]{64}"))throw new SecurityException("WORKER_ABORT_CAPABILITY_INVALID");abortCapability=value;}
    @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("WORKER_UID_MISMATCH");
        if(code==RuntimeWire.LOAD){claim(pid);try{RuntimeEngine engine=new RuntimeEngine(context,input);Bundle out=new Bundle();out.putInt("pid",android.os.Process.myPid());out.putInt("uid",android.os.Process.myUid());out.putString("generation",engine.generation);return out;}catch(Exception error){android.util.Log.e("TripoderRuntime","Worker initialization failed",error);terminate();throw error;}}
        synchronized(this){if(code==RuntimeWire.STOP&&controllerPid==0&&abortCapability!=null&&abortCapability.equals(input.getString("bootstrapAbort")))controllerPid=pid;}
        if(pid!=controllerPid||controllerPid==0)throw new SecurityException("WORKER_CALLER_MISMATCH");
        if(code==RuntimeWire.START_APPLICATION){try{RuntimeEngine.current.startApplication();return new Bundle();}catch(Exception error){android.util.Log.e("TripoderRuntime","Worker initialization failed",error);terminate();throw error;}}
        if(code==RuntimeWire.STOP){if(RuntimeEngine.current!=null)RuntimeEngine.current.storage.flush();terminate();return new Bundle();}
        if(code==RuntimeWire.CHECK)return RuntimeEngine.current.check(input);
        if(code==RuntimeWire.HOOK){
            String request=input.getString("request");if(request==null||request.length()>28000||!input.keySet().equals(java.util.Set.of("request","generation")))throw new IllegalArgumentException("HOOK_REQUEST_INVALID");
            if(!RuntimeEngine.current.generation.equals(RuntimeWire.required(input,"generation")))throw new SecurityException("HOOK_PROCESS_STALE");
            Bundle out=new Bundle();out.putString("result",RuntimeHookBridge.execute(request));out.putInt("pid",android.os.Process.myPid());out.putString("generation",RuntimeEngine.current.generation);return out;
        }
        throw new IllegalArgumentException("WORKER_OPERATION_UNSUPPORTED");
    }
    private static void terminate(){new Thread(()->{SystemClock.sleep(100);android.os.Process.killProcess(android.os.Process.myPid());},"runtime-terminate").start();}
}
