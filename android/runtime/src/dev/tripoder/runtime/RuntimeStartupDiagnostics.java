package dev.tripoder.runtime;

import android.util.Log;
import java.util.*;
import org.json.*;

/** Bounded stack evidence on an actual startup timeout; never changes the outcome. */
final class RuntimeStartupDiagnostics {
    static void failure(String stage,Throwable error){
        if(RuntimeEngine.current==null)return;
        try{
            Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());JSONArray causes=new JSONArray();
            for(Throwable current=error;current!=null&&causes.length()<6&&visited.add(current);current=current.getCause()){
                JSONArray frames=new JSONArray();StackTraceElement[] trace=current.getStackTrace();for(int i=0;i<Math.min(24,trace.length);i++)frames.put(trace[i].toString());
                String message=current.getMessage();if(message!=null&&message.length()>400)message=message.substring(0,400);
                causes.put(new JSONObject().put("type",current.getClass().getName()).put("message",message==null?JSONObject.NULL:message).put("frames",frames));
            }
            Log.e("TripoderStartup",new JSONObject().put("stage",stage).put("thread",Thread.currentThread().getName()).put("causes",causes).toString());
        }catch(Exception diagnosticError){Log.e("TripoderStartup","FAILURE_DIAGNOSTICS_FAILED",diagnosticError);}
        if("uncaught".equals(stage)&&android.os.Looper.myLooper()==android.os.Looper.getMainLooper())views();
    }
    /** Read-only, bounded view-tree evidence of the guest Activities at a main-thread fatal. */
    private static void views(){
        try{
            for(android.app.Activity activity:RuntimeEngine.current.diagnosticActivities()){
                android.view.Window window=activity.getWindow();android.view.View root=window==null?null:window.peekDecorView();
                StringBuilder out=new StringBuilder(activity.getClass().getName()).append(" destroyed=").append(activity.isDestroyed()).append(" hash=").append(Integer.toHexString(System.identityHashCode(activity)));
                Log.e("TripoderViews","ACTIVITY "+out);
                if(root==null)continue;
                ArrayDeque<Object[]> pending=new ArrayDeque<>();pending.add(new Object[]{root,0});int seen=0;
                while(!pending.isEmpty()&&seen<400){
                    Object[] item=pending.removeFirst();android.view.View view=(android.view.View)item[0];int depth=(Integer)item[1];seen++;
                    String id="";int raw=view.getId();
                    if(raw!=android.view.View.NO_ID){id=Integer.toHexString(raw);try{id+="/"+view.getResources().getResourceEntryName(raw);}catch(RuntimeException missing){id+="/?";}}
                    Log.e("TripoderViews",depth+" "+view.getClass().getName()+" "+id+" vis="+view.getVisibility());
                    if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--)pending.addFirst(new Object[]{group.getChildAt(i),depth+1});}
                }
            }
        }catch(Throwable error){Log.e("TripoderViews","VIEW_DIAGNOSTICS_FAILED",error);}
    }
    static void timeout(){
        try{
            Map<Thread,StackTraceElement[]> observed=Thread.getAllStackTraces();List<Thread> threads=new ArrayList<>(observed.keySet());
            threads.sort(Comparator.comparingInt((Thread t)->t.getName().equals("main")?0:t.getState()==Thread.State.BLOCKED?1:2).thenComparing(Thread::getName));
            Log.e("TripoderStartup","STARTUP_TIMEOUT_STACKS:pid="+android.os.Process.myPid()+";observed="+threads.size());
            for(Thread thread:threads.subList(0,Math.min(64,threads.size()))){
                JSONArray frames=new JSONArray();StackTraceElement[] trace=observed.get(thread);for(int i=0;i<Math.min(32,trace.length);i++)frames.put(trace[i].toString());
                Log.e("TripoderStartup",new JSONObject().put("thread",thread.getName()).put("id",thread.getId()).put("state",thread.getState().name()).put("frames",frames).toString());
            }
        }catch(Exception error){Log.e("TripoderStartup","STARTUP_DIAGNOSTICS_FAILED",error);}
    }
    private RuntimeStartupDiagnostics(){}
}
