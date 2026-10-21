package dev.tripoder.runtime;

import android.util.Log;
import java.util.*;
import org.json.*;

/** Bounded stack evidence on an actual startup timeout; never changes the outcome. */
final class RuntimeStartupDiagnostics {
    private static final ArrayDeque<Map<String,Object>> RECENT_FAILURES=new ArrayDeque<>();
    static void failure(String stage,Throwable error){
        if(RuntimeEngine.current==null)return;
        try{
            Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());JSONArray causes=new JSONArray();List<Map<String,Object>> causeList=new ArrayList<>();
            for(Throwable current=error;current!=null&&causes.length()<6&&visited.add(current);current=current.getCause()){
                JSONArray frames=new JSONArray();List<String> frameList=new ArrayList<>();StackTraceElement[] trace=current.getStackTrace();
                for(int i=0;i<Math.min(24,trace.length);i++){frames.put(trace[i].toString());frameList.add(trace[i].toString());}
                String message=current.getMessage();if(message!=null&&message.length()>400)message=message.substring(0,400);
                causes.put(new JSONObject().put("type",current.getClass().getName()).put("message",message==null?JSONObject.NULL:message).put("frames",frames));
                Map<String,Object> c=new LinkedHashMap<>();c.put("type",current.getClass().getName());c.put("message",message);c.put("frames",List.copyOf(frameList));causeList.add(Collections.unmodifiableMap(c));
            }
            synchronized(RECENT_FAILURES){
                if(RECENT_FAILURES.size()>=8)RECENT_FAILURES.pollFirst();
                RECENT_FAILURES.addLast(Map.of("stage",stage,"thread",Thread.currentThread().getName(),"causes",List.copyOf(causeList)));
            }
            Log.e("TripoderStartup",new JSONObject().put("stage",stage).put("thread",Thread.currentThread().getName()).put("causes",causes).toString());
        }catch(Exception diagnosticError){Log.e("TripoderStartup","FAILURE_DIAGNOSTICS_FAILED",diagnosticError);}
        if("uncaught".equals(stage)&&android.os.Looper.myLooper()==android.os.Looper.getMainLooper())views();
    }
    static List<Map<String,Object>> recentFailures(){
        synchronized(RECENT_FAILURES){return List.copyOf(RECENT_FAILURES);}
    }
    static Map<String,Object> dumpUiHierarchy(boolean includeInvisible)throws Exception{
        return onMain(()->{
            RuntimeEngine engine=RuntimeEngine.current;if(engine==null)return Map.of("activities",List.of());
            List<Map<String,Object>> activities=new ArrayList<>();
            for(android.app.Activity activity:engine.diagnosticActivities()){
                if(activity.isDestroyed())continue;
                android.view.Window window=activity.getWindow();android.view.View root=window==null?null:window.peekDecorView();
                List<Map<String,Object>> nodes=new ArrayList<>();
                if(root!=null){
                    ArrayDeque<Object[]> pending=new ArrayDeque<>();pending.add(new Object[]{root,0});
                    while(!pending.isEmpty()&&nodes.size()<256){
                        Object[] item=pending.removeFirst();android.view.View view=(android.view.View)item[0];int depth=(Integer)item[1];
                        boolean visible=view.getVisibility()==android.view.View.VISIBLE&&view.isShown();
                        if(!includeInvisible&&!visible)continue;
                        String id="";int raw=view.getId();
                        if(raw!=android.view.View.NO_ID){try{id=view.getResources().getResourceEntryName(raw);}catch(RuntimeException missing){id="0x"+Integer.toHexString(raw);}}
                        String text="";if(view instanceof android.widget.TextView tv&&tv.getText()!=null){text=tv.getText().toString();if(text.length()>200)text=text.substring(0,200);}
                        CharSequence desc=view.getContentDescription();String contentDesc=desc==null?"":desc.toString();if(contentDesc.length()>200)contentDesc=contentDesc.substring(0,200);
                        int[] loc=new int[2];view.getLocationOnScreen(loc);
                        List<Double> bounds=List.of((double)loc[0],(double)loc[1],(double)(loc[0]+view.getWidth()),(double)(loc[1]+view.getHeight()));
                        Map<String,Object> node=new LinkedHashMap<>();
                        node.put("depth",(double)depth);node.put("className",view.getClass().getName());node.put("id",id);node.put("text",text);
                        node.put("contentDescription",contentDesc);node.put("clickable",view.isClickable());node.put("enabled",view.isEnabled());
                        node.put("visible",visible);node.put("bounds",bounds);nodes.add(Collections.unmodifiableMap(node));
                        if(view instanceof android.view.ViewGroup group){for(int i=group.getChildCount()-1;i>=0;i--)pending.addFirst(new Object[]{group.getChildAt(i),depth+1});}
                    }
                }
                activities.add(Map.of("activity",activity.getClass().getName(),"taskId",(double)activity.getTaskId(),"nodes",List.copyOf(nodes)));
            }
            return Map.of("activities",List.copyOf(activities));
        });
    }
    static Map<String,Object> dispatchUiAction(Map<String,Object> action)throws Exception{
        return onMain(()->{
            RuntimeEngine engine=RuntimeEngine.current;if(engine==null)throw new IllegalStateException("UI_ENGINE_MISSING");
            android.app.Activity target=null;
            for(android.app.Activity activity:engine.diagnosticActivities())if(!activity.isDestroyed()&&activity.getWindow()!=null&&activity.getWindow().peekDecorView()!=null)target=activity;
            if(target==null)throw new IllegalStateException("UI_ACTIVE_ACTIVITY_MISSING");
            android.view.View decor=target.getWindow().peekDecorView();
            Object rawKind=action.containsKey("kind")?action.get("kind"):action.get("type");
            if(!(rawKind instanceof String kind))throw new IllegalArgumentException("UI_ACTION_KIND");
            switch(kind){
                case "tap":{
                    Object rx=action.get("x"),ry=action.get("y");
                    if(!(rx instanceof Number)||!(ry instanceof Number))throw new IllegalArgumentException("UI_TAP_COORDINATES");
                    float x=((Number)rx).floatValue(),y=((Number)ry).floatValue();
                    long now=android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                    android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+25,android.view.MotionEvent.ACTION_UP,x,y,0);
                    boolean handled;try{handled=decor.dispatchTouchEvent(down)|decor.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
                    return Map.of("dispatched",true,"handled",handled,"activity",target.getClass().getName(),"kind","tap");
                }
                case "swipe":{
                    Object sx=action.get("startX"),sy=action.get("startY"),ex=action.get("endX"),ey=action.get("endY");
                    if(!(sx instanceof Number)||!(sy instanceof Number)||!(ex instanceof Number)||!(ey instanceof Number))
                        throw new IllegalArgumentException("UI_SWIPE_COORDINATES");
                    float x0=((Number)sx).floatValue(),y0=((Number)sy).floatValue(),x1=((Number)ex).floatValue(),y1=((Number)ey).floatValue();
                    long now=android.os.SystemClock.uptimeMillis();
                    int steps=8;boolean handled=false;
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x0,y0,0);
                    try{
                        handled|=decor.dispatchTouchEvent(down);
                        for(int i=1;i<=steps;i++){
                            float t=i/(float)steps;
                            android.view.MotionEvent move=android.view.MotionEvent.obtain(now,now+i*16L,android.view.MotionEvent.ACTION_MOVE,x0+(x1-x0)*t,y0+(y1-y0)*t,0);
                            try{handled|=decor.dispatchTouchEvent(move);}finally{move.recycle();}
                        }
                        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+(steps+1)*16L,android.view.MotionEvent.ACTION_UP,x1,y1,0);
                        try{handled|=decor.dispatchTouchEvent(up);}finally{up.recycle();}
                    }finally{down.recycle();}
                    return Map.of("dispatched",true,"handled",handled,"activity",target.getClass().getName(),"kind","swipe");
                }
                case "clickText":{
                    Object rawText=action.get("text");if(!(rawText instanceof String text)||text.isEmpty())throw new IllegalArgumentException("UI_CLICK_TEXT");
                    android.view.View matched=findViewByText(decor,text);if(matched==null)throw new IllegalStateException("UI_TEXT_NOT_FOUND:"+text);
                    android.view.View cur=matched;boolean clicked=false;
                    for(int i=0;i<5&&cur!=null;i++){
                        if(cur.isClickable()&&cur.isEnabled()){clicked=cur.performClick();break;}
                        cur=cur.getParent() instanceof android.view.View p?p:null;
                    }
                    if(!clicked&&matched.isEnabled())clicked=matched.performClick();
                    return Map.of("dispatched",true,"clicked",clicked,"activity",target.getClass().getName(),"kind","clickText","matchedClass",matched.getClass().getName());
                }
                case "setText":{
                    Object rawText=action.get("text");if(!(rawText instanceof String text)||text.length()>4096)throw new IllegalArgumentException("UI_SET_TEXT_VALUE");
                    String targetId=action.get("id") instanceof String s?s:"";
                    String matchHint=action.get("match") instanceof String s?s:"";
                    android.widget.TextView field=findEditableView(decor,targetId,matchHint);
                    if(field==null)throw new IllegalStateException("UI_EDITABLE_VIEW_NOT_FOUND");
                    field.requestFocus();field.setText(text);
                    if(field instanceof android.widget.EditText et)et.setSelection(text.length());
                    return Map.of("dispatched",true,"activity",target.getClass().getName(),"kind","setText","matchedClass",field.getClass().getName());
                }
                case "key":{
                    Object rawKey=action.get("keyCode");if(!(rawKey instanceof Number))throw new IllegalArgumentException("UI_KEY_CODE");
                    int keyCode=((Number)rawKey).intValue();
                    boolean handled=target.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,keyCode))
                        |target.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,keyCode));
                    return Map.of("dispatched",true,"handled",handled,"activity",target.getClass().getName(),"kind","key");
                }
                default:throw new IllegalArgumentException("UI_ACTION_UNSUPPORTED:"+kind);
            }
        });
    }
    private static android.widget.TextView findEditableView(android.view.View view,String wantedId,String wantedText){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        if(view instanceof android.widget.EditText tv&&tv.isEnabled()){
            if(wantedId.isEmpty()&&wantedText.isEmpty())return tv;
            if(!wantedId.isEmpty()&&view.getId()!=android.view.View.NO_ID){
                String resName="";try{resName=view.getResources().getResourceEntryName(view.getId());}catch(RuntimeException ignored){resName="0x"+Integer.toHexString(view.getId());}
                if(wantedId.equals(resName))return tv;
            }
            if(!wantedText.isEmpty()){
                if(tv.getText()!=null&&wantedText.contentEquals(tv.getText()))return tv;
                if(tv.getHint()!=null&&wantedText.contentEquals(tv.getHint()))return tv;
            }
        }
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.widget.TextView found=findEditableView(group.getChildAt(i),wantedId,wantedText);if(found!=null)return found;}
        }
        return null;
    }
    private static android.view.View findViewByText(android.view.View view,String wanted){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        if(view instanceof android.widget.TextView tv&&tv.getText()!=null&&wanted.contentEquals(tv.getText()))return view;
        if(view.getContentDescription()!=null&&wanted.contentEquals(view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findViewByText(group.getChildAt(i),wanted);if(found!=null)return found;}
        }
        return null;
    }
    private static <T> T onMain(java.util.concurrent.Callable<T> task)throws Exception{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return task.call();
        java.util.concurrent.FutureTask<T> future=new java.util.concurrent.FutureTask<>(task);
        android.os.Handler.createAsync(android.os.Looper.getMainLooper()).post(future);
        try{return future.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException timeout){future.cancel(false);throw timeout;}
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
