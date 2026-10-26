package dev.tripoder.runtime;

import android.util.Log;
import java.util.*;
import org.json.*;

/** Bounded stack evidence on an actual startup timeout; never changes the outcome. */
final class RuntimeStartupDiagnostics {
    private static final ArrayDeque<Map<String,Object>> RECENT_FAILURES=new ArrayDeque<>();
    /** QA opt-in only: observe a stalled constructor/native initializer before an
     * application watchdog can end the process. Never change its deadline or exit. */
    static Thread watchStartup(java.util.function.Supplier<String> stage){
        Thread main=android.os.Looper.getMainLooper().getThread();long started=android.os.SystemClock.elapsedRealtime();
        Thread observer=new Thread(()->{
            for(long milestone:new long[]{2000,5000,8000,15000,22000,27000}){
                long remaining=milestone-(android.os.SystemClock.elapsedRealtime()-started);
                if(remaining>0)try{Thread.sleep(remaining);}catch(InterruptedException finished){return;}
                if(Thread.currentThread().isInterrupted())return;
                StackTraceElement[] frames=main.getStackTrace();int limit=Math.min(frames.length,32);
                Log.i("TripoderStartupSample","elapsedMs="+(android.os.SystemClock.elapsedRealtime()-started)+";stage="+stage.get()+";state="+main.getState());
                for(int offset=0;offset<limit;offset+=4)Log.i("TripoderStartupSample","frames="+Arrays.toString(Arrays.copyOfRange(frames,offset,Math.min(offset+4,limit))));
            }
        },"Tripoder-StartupEvidence");observer.setDaemon(true);observer.start();return observer;
    }
    static void failure(String stage,Throwable error){
        if(RuntimeEngine.current==null)return;
        try{
            Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());JSONArray causes=new JSONArray();List<Map<String,Object>> causeList=new ArrayList<>();
            for(Throwable current=error;current!=null&&causes.length()<6&&visited.add(current);current=current.getCause()){
                JSONArray frames=new JSONArray();List<String> frameList=new ArrayList<>();StackTraceElement[] trace=current.getStackTrace();
                for(int i=0;i<Math.min(24,trace.length);i++){frames.put(trace[i].toString());frameList.add(trace[i].toString());}
                String message=current.getMessage();if(message!=null&&message.length()>400)message=message.substring(0,400);
                causes.put(new JSONObject().put("type",current.getClass().getName()).put("message",message==null?JSONObject.NULL:message));
                // logd truncates a long entry near 4 KiB. Keep the causal summary
                // separate so a deep wrapper stack cannot erase the actual cause.
                for(int offset=0;offset<frameList.size();offset+=4)Log.e("TripoderStartupFrames",stage+" cause="+causes.length()+" "+frameList.subList(offset,Math.min(offset+4,frameList.size())));
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
    static Map<String,Object> dumpUiHierarchy(boolean includeInvisible,int limit)throws Exception{
        int nodeLimit=limit>0?Math.min(limit,4096):256;
        return onMain(()->{
            RuntimeEngine engine=RuntimeEngine.current;if(engine==null)return Map.of("windows",List.of(),"windowEnumerationSupported",false);
            // Activity decor views the engine already tracks (identity-matched below against
            // whatever WindowManagerGlobal reports, so each root is only emitted once).
            Map<android.view.View,android.app.Activity> byRoot=new IdentityHashMap<>();
            for(android.app.Activity activity:engine.diagnosticActivities()){
                if(activity.isDestroyed())continue;
                android.view.Window window=activity.getWindow();android.view.View root=window==null?null:window.peekDecorView();
                if(root!=null)byRoot.put(root,activity);
            }
            List<Map<String,Object>> windows=new ArrayList<>();
            List<android.view.View> allRoots;List<Object> allParams;
            boolean enumerationSupported=true;
            try{
                // Precedent for this exact reflection pattern (installWindowSessionProxy) already
                // exists in RuntimeEngine; this only reads the live roots/params lists, never mutates them.
                Class<?> wmgClass=Class.forName("android.view.WindowManagerGlobal");
                Object wmg=wmgClass.getMethod("getInstance").invoke(null);
                java.lang.reflect.Field viewsField=wmgClass.getDeclaredField("mViews");viewsField.setAccessible(true);
                java.lang.reflect.Field paramsField=wmgClass.getDeclaredField("mParams");paramsField.setAccessible(true);
                @SuppressWarnings("unchecked") List<android.view.View> views=new ArrayList<>((List<android.view.View>)viewsField.get(wmg));
                @SuppressWarnings("unchecked") List<Object> params=new ArrayList<>((List<Object>)paramsField.get(wmg));
                allRoots=views;allParams=params;
            }catch(ReflectiveOperationException|RuntimeException unsupported){
                // Falls back to only the engine-tracked Activity roots; dialogs/popups are not
                // enumerable on this build/ROM. Explicit, not a silent guess.
                enumerationSupported=false;allRoots=new ArrayList<>(byRoot.keySet());allParams=null;
            }
            for(int i=0;i<allRoots.size();i++){
                android.view.View root=allRoots.get(i);if(root==null||!root.isAttachedToWindow())continue;
                android.app.Activity owner=byRoot.get(root);
                Integer windowType=null;
                if(allParams!=null&&i<allParams.size()&&allParams.get(i) instanceof android.view.WindowManager.LayoutParams lp)windowType=lp.type;
                List<Map<String,Object>> nodes=new ArrayList<>();boolean truncated=false;
                ArrayDeque<Object[]> pending=new ArrayDeque<>();pending.add(new Object[]{root,0});
                while(!pending.isEmpty()){
                    if(nodes.size()>=nodeLimit){truncated=!pending.isEmpty();break;}
                    Object[] item=pending.removeFirst();android.view.View view=(android.view.View)item[0];int depth=(Integer)item[1];
                    boolean visible=view.getVisibility()==android.view.View.VISIBLE&&view.isShown();
                    if(!includeInvisible&&!visible){
                        if(view instanceof android.view.ViewGroup group)for(int c=group.getChildCount()-1;c>=0;c--)pending.addFirst(new Object[]{group.getChildAt(c),depth+1});
                        continue;
                    }
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
                    if(view instanceof android.view.ViewGroup group){for(int c=group.getChildCount()-1;c>=0;c--)pending.addFirst(new Object[]{group.getChildAt(c),depth+1});}
                }
                Map<String,Object> win=new LinkedHashMap<>();
                win.put("activity",owner==null?null:owner.getClass().getName());
                win.put("taskId",owner==null?null:(double)owner.getTaskId());
                win.put("windowType",windowType==null?null:(double)windowType);
                win.put("isActivityDecorView",owner!=null);
                win.put("nodes",List.copyOf(nodes));win.put("truncated",truncated);
                windows.add(Collections.unmodifiableMap(win));
            }
            return Map.of("windows",List.copyOf(windows),"windowEnumerationSupported",enumerationSupported);
        });
    }
    static Map<String,Object> dispatchUiAction(Map<String,Object> action)throws Exception{
        Object rawTopKind=action.containsKey("kind")?action.get("kind"):action.get("type");
        if("waitForElement".equals(rawTopKind))return waitForElement(action);
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
                case "clickTextContains":{
                    Object rawText=action.get("text");if(!(rawText instanceof String text)||text.isEmpty())throw new IllegalArgumentException("UI_CLICK_TEXT_CONTAINS");
                    android.view.View matched=findViewByTextContains(decor,text);if(matched==null)throw new IllegalStateException("UI_TEXT_NOT_FOUND:"+text);
                    boolean clicked=clickAscending(matched);
                    return Map.of("dispatched",true,"clicked",clicked,"activity",target.getClass().getName(),"kind","clickTextContains","matchedClass",matched.getClass().getName());
                }
                case "clickById":{
                    Object rawId=action.get("id");if(!(rawId instanceof String id)||id.isEmpty())throw new IllegalArgumentException("UI_CLICK_ID");
                    android.view.View matched=findViewByResourceId(decor,id);if(matched==null)throw new IllegalStateException("UI_ID_NOT_FOUND:"+id);
                    boolean clicked=clickAscending(matched);
                    return Map.of("dispatched",true,"clicked",clicked,"activity",target.getClass().getName(),"kind","clickById","matchedClass",matched.getClass().getName());
                }
                case "clickByDescription":{
                    Object rawDesc=action.get("description");if(!(rawDesc instanceof String desc)||desc.isEmpty())throw new IllegalArgumentException("UI_CLICK_DESCRIPTION");
                    android.view.View matched=findViewByExactDescription(decor,desc);if(matched==null)throw new IllegalStateException("UI_DESCRIPTION_NOT_FOUND:"+desc);
                    boolean clicked=clickAscending(matched);
                    return Map.of("dispatched",true,"clicked",clicked,"activity",target.getClass().getName(),"kind","clickByDescription","matchedClass",matched.getClass().getName());
                }
                case "longPress":{
                    Object rx=action.get("x"),ry=action.get("y");
                    if(!(rx instanceof Number)||!(ry instanceof Number))throw new IllegalArgumentException("UI_LONG_PRESS_COORDINATES");
                    float x=((Number)rx).floatValue(),y=((Number)ry).floatValue();
                    Object rawDuration=action.get("durationMs");long duration=rawDuration instanceof Number n?n.longValue():600L;
                    if(duration<300||duration>5000)throw new IllegalArgumentException("UI_LONG_PRESS_DURATION");
                    long now=android.os.SystemClock.uptimeMillis();
                    // Synthetic event timestamps encode the press duration; the main thread is never
                    // actually slept, matching the existing tap/swipe approach in this method.
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                    android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+duration,android.view.MotionEvent.ACTION_UP,x,y,0);
                    boolean handled;try{handled=decor.dispatchTouchEvent(down)|decor.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
                    return Map.of("dispatched",true,"handled",handled,"activity",target.getClass().getName(),"kind","longPress");
                }
                case "doubleTap":{
                    Object rx=action.get("x"),ry=action.get("y");
                    if(!(rx instanceof Number)||!(ry instanceof Number))throw new IllegalArgumentException("UI_DOUBLE_TAP_COORDINATES");
                    float x=((Number)rx).floatValue(),y=((Number)ry).floatValue();
                    long now=android.os.SystemClock.uptimeMillis();boolean handled=false;
                    android.view.MotionEvent d1=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                    android.view.MotionEvent u1=android.view.MotionEvent.obtain(now,now+25,android.view.MotionEvent.ACTION_UP,x,y,0);
                    long second=now+120;// within the platform double-tap timeout window
                    android.view.MotionEvent d2=android.view.MotionEvent.obtain(second,second,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                    android.view.MotionEvent u2=android.view.MotionEvent.obtain(second,second+25,android.view.MotionEvent.ACTION_UP,x,y,0);
                    try{handled=decor.dispatchTouchEvent(d1)|decor.dispatchTouchEvent(u1)|decor.dispatchTouchEvent(d2)|decor.dispatchTouchEvent(u2);}
                    finally{d1.recycle();u1.recycle();d2.recycle();u2.recycle();}
                    return Map.of("dispatched",true,"handled",handled,"activity",target.getClass().getName(),"kind","doubleTap");
                }
                case "scrollIntoView":{
                    Object rawText=action.get("text");if(!(rawText instanceof String text)||text.isEmpty())throw new IllegalArgumentException("UI_SCROLL_INTO_VIEW_TEXT");
                    if(findViewByTextContains(decor,text)!=null)
                        return Map.of("dispatched",true,"scrolled",false,"found",true,"activity",target.getClass().getName(),"kind","scrollIntoView");
                    // Only the two platform scroll containers are handled; androidx RecyclerView/
                    // NestedScrollView belong to the guest's own classpath and are not resolvable
                    // here. Reporting an explicit unsupported outcome is preferred over guessing.
                    android.view.View container=findScrollContainer(decor);
                    if(container==null)throw new IllegalStateException("UI_SCROLL_CONTAINER_UNSUPPORTED");
                    boolean found=false;
                    for(int step=0;step<10&&!found;step++){
                        if(container instanceof android.widget.HorizontalScrollView h)h.scrollBy(h.getWidth()/2,0);
                        else if(container instanceof android.widget.ScrollView s)s.scrollBy(0,s.getHeight()/2);
                        found=findViewByTextContains(decor,text)!=null;
                    }
                    return Map.of("dispatched",true,"scrolled",true,"found",found,"activity",target.getClass().getName(),"kind","scrollIntoView");
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
    private static android.view.View findViewByTextContains(android.view.View view,String wanted){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        if(view instanceof android.widget.TextView tv&&tv.getText()!=null&&tv.getText().toString().contains(wanted))return view;
        CharSequence desc=view.getContentDescription();if(desc!=null&&desc.toString().contains(wanted))return view;
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findViewByTextContains(group.getChildAt(i),wanted);if(found!=null)return found;}
        }
        return null;
    }
    private static android.view.View findViewByExactDescription(android.view.View view,String wanted){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        if(view.getContentDescription()!=null&&wanted.contentEquals(view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findViewByExactDescription(group.getChildAt(i),wanted);if(found!=null)return found;}
        }
        return null;
    }
    private static android.view.View findViewByResourceId(android.view.View view,String wantedId){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        int raw=view.getId();
        if(raw!=android.view.View.NO_ID){
            String resName;try{resName=view.getResources().getResourceEntryName(raw);}catch(RuntimeException missing){resName=null;}
            if(wantedId.equals(resName))return view;
        }
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findViewByResourceId(group.getChildAt(i),wantedId);if(found!=null)return found;}
        }
        return null;
    }
    /** Only the two platform scroll containers; androidx list/scroll widgets belong to the
     * guest's own classpath and cannot be resolved by this host class, so they are left unsupported
     * rather than guessed at. */
    private static android.view.View findScrollContainer(android.view.View view){
        if(view==null||view.getVisibility()!=android.view.View.VISIBLE)return null;
        if(view instanceof android.widget.ScrollView||view instanceof android.widget.HorizontalScrollView)return view;
        if(view instanceof android.view.ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findScrollContainer(group.getChildAt(i));if(found!=null)return found;}
        }
        return null;
    }
    /** Walks up to 5 ancestors looking for a clickable, enabled target; falls back to the matched
     * view itself (mirrors the existing clickText behavior). */
    private static boolean clickAscending(android.view.View matched){
        android.view.View cur=matched;
        for(int i=0;i<5&&cur!=null;i++){
            if(cur.isClickable()&&cur.isEnabled())return cur.performClick();
            cur=cur.getParent() instanceof android.view.View p?p:null;
        }
        return matched.isEnabled()&&matched.performClick();
    }
    /** Polls off the main thread so the guest UI keeps pumping its message queue between checks;
     * only the bounded per-check lookup itself runs on main. */
    private static Map<String,Object> waitForElement(Map<String,Object> action)throws Exception{
        Object rawText=action.get("text");if(!(rawText instanceof String text)||text.isEmpty())throw new IllegalArgumentException("UI_WAIT_FOR_ELEMENT_TEXT");
        Object rawTimeout=action.get("timeoutMs");long timeoutMs=rawTimeout instanceof Number n?n.longValue():5000L;
        if(timeoutMs<100||timeoutMs>15000)throw new IllegalArgumentException("UI_WAIT_FOR_ELEMENT_TIMEOUT");
        long deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs;
        while(true){
            boolean found=onMain(()->{
                RuntimeEngine engine=RuntimeEngine.current;if(engine==null)return false;
                for(android.app.Activity activity:engine.diagnosticActivities()){
                    if(activity.isDestroyed()||activity.getWindow()==null)continue;
                    android.view.View decor=activity.getWindow().peekDecorView();
                    if(decor!=null&&findViewByTextContains(decor,text)!=null)return true;
                }
                return false;
            });
            if(found)return Map.of("dispatched",true,"found",true,"kind","waitForElement");
            if(android.os.SystemClock.elapsedRealtime()>=deadline)return Map.of("dispatched",true,"found",false,"kind","waitForElement","outcome","WAIT_TIMEOUT");
            Thread.sleep(Math.min(150L,deadline-android.os.SystemClock.elapsedRealtime()));
        }
    }
    /** Bounded foreground-window PNG: PixelCopy (includes SurfaceView/GL content), else View.draw. Inline because the guest response channel is capped at 128 KiB. */
    static Map<String,Object> screenshot(int maxDimension)throws Exception{
        final int PNG_BUDGET=88*1024;
        Object[] picked=onMain(()->{
            RuntimeEngine engine=RuntimeEngine.current;if(engine==null)return null;
            android.app.Activity chosen=null;
            for(android.app.Activity activity:engine.diagnosticActivities()){
                if(activity.isDestroyed()||activity.isFinishing())continue;
                android.view.Window window=activity.getWindow();android.view.View decor=window==null?null:window.peekDecorView();
                if(decor==null||!decor.isAttachedToWindow()||decor.getWidth()<=0||decor.getHeight()<=0)continue;
                if(chosen==null||decor.hasWindowFocus())chosen=activity;
            }
            return chosen==null?null:new Object[]{chosen,chosen.getWindow().getDecorView()};
        });
        if(picked==null)throw new IllegalStateException("SCREENSHOT_NO_FOREGROUND_WINDOW");
        android.app.Activity activity=(android.app.Activity)picked[0];android.view.View decor=(android.view.View)picked[1];
        int width=decor.getWidth(),height=decor.getHeight();
        android.graphics.Bitmap full=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        String method="PIXEL_COPY";
        android.os.HandlerThread thread=new android.os.HandlerThread("tripoder-pixelcopy");thread.start();
        try{
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);int[] status={-1};
            android.view.PixelCopy.request(activity.getWindow(),full,code->{status[0]=code;done.countDown();},new android.os.Handler(thread.getLooper()));
            if(!done.await(5,java.util.concurrent.TimeUnit.SECONDS)||status[0]!=android.view.PixelCopy.SUCCESS)method="VIEW_DRAW";
        }catch(RuntimeException unsupported){method="VIEW_DRAW";}
        finally{thread.quitSafely();}
        if(method.equals("VIEW_DRAW"))onMain(()->{decor.draw(new android.graphics.Canvas(full));return null;});
        int composited=0,missed=0;
        List<android.view.SurfaceView> surfaces=onMain(()->{List<android.view.SurfaceView> found=new ArrayList<>();collectSurfaceViews(decor,found);return found;});
        for(android.view.SurfaceView surface:surfaces){
            int[] at=onMain(()->{int[] loc=new int[2];surface.getLocationInWindow(loc);return new int[]{loc[0],loc[1],surface.getWidth(),surface.getHeight()};});
            if(at[2]<=0||at[3]<=0){continue;}
            android.graphics.Bitmap layer=android.graphics.Bitmap.createBitmap(at[2],at[3],android.graphics.Bitmap.Config.ARGB_8888);
            android.os.HandlerThread layerThread=new android.os.HandlerThread("tripoder-pixelcopy-surface");layerThread.start();
            try{
                java.util.concurrent.CountDownLatch layerDone=new java.util.concurrent.CountDownLatch(1);int[] layerStatus={-1};
                android.view.PixelCopy.request(surface,layer,code->{layerStatus[0]=code;layerDone.countDown();},new android.os.Handler(layerThread.getLooper()));
                if(layerDone.await(5,java.util.concurrent.TimeUnit.SECONDS)&&layerStatus[0]==android.view.PixelCopy.SUCCESS){
                    new android.graphics.Canvas(full).drawBitmap(layer,at[0],at[1],null);composited++;
                }else missed++;
            }catch(RuntimeException unsupported){missed++;}
            finally{layerThread.quitSafely();layer.recycle();}
        }
        double scale=Math.min(1.0,(double)maxDimension/Math.max(width,height));
        while(true){
            int w=Math.max(1,(int)Math.round(width*scale)),h=Math.max(1,(int)Math.round(height*scale));
            android.graphics.Bitmap scaled=scale>=1.0?full:android.graphics.Bitmap.createScaledBitmap(full,w,h,true);
            java.io.ByteArrayOutputStream png=new java.io.ByteArrayOutputStream();scaled.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png);
            byte[] bytes=png.toByteArray();
            if(bytes.length<=PNG_BUDGET){
                String sha;try{sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
                Map<String,Object> out=new LinkedHashMap<>();
                out.put("format","png");out.put("method",method);out.put("activity",activity.getClass().getName());
                out.put("originalWidth",(double)width);out.put("originalHeight",(double)height);out.put("width",(double)w);out.put("height",(double)h);
                out.put("byteLength",(double)bytes.length);out.put("sha256",sha);
                out.put("surfaceViewsComposited",(double)composited);out.put("surfaceViewsFailed",(double)missed);
                out.put("note",(method.equals("VIEW_DRAW")?"View.draw fallback of the foreground Activity window; ":"PixelCopy of the foreground Activity window; ")+"each SurfaceView is PixelCopied separately and composited at its window position. SurfaceViews counted in surfaceViewsFailed (or any non-SurfaceView video/GL layer) may be blank.");
                out.put("dataBase64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
                return Collections.unmodifiableMap(out);
            }
            if(scaled!=full)scaled.recycle();
            scale*=0.75;
            if(Math.max(width,height)*scale<64)throw new IllegalStateException("SCREENSHOT_TOO_LARGE");
        }
    }
    private static void collectSurfaceViews(android.view.View view,List<android.view.SurfaceView> out){
        if(view.getVisibility()!=android.view.View.VISIBLE)return;
        if(view instanceof android.view.SurfaceView surface)out.add(surface);
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)collectSurfaceViews(group.getChildAt(i),out);
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
