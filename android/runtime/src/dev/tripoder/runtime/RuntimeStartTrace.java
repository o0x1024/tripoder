package dev.tripoder.runtime;

import android.app.Activity;
import android.content.Intent;

/**
 * Observation-only, bounded trace of the activity starts a guest process performs. Every line is written by
 * the guest process itself, so a start that never appears here is a start the guest never made. The cap keeps
 * a chatty application from flooding the buffer: past {@link #MAX} lines the trace stops and says so once.
 */
final class RuntimeStartTrace {
    private RuntimeStartTrace(){}
    static final int MAX=200;
    private static final java.util.concurrent.atomic.AtomicInteger USED=new java.util.concurrent.atomic.AtomicInteger();
    static final boolean ON=Boolean.parseBoolean(System.getProperty("tripoder.trace.starts","true"));
    private static String target(Intent intent){
        if(intent==null)return "intent=null";
        android.content.ComponentName component=intent.getComponent();
        if(component!=null)return component.flattenToShortString();
        return "action="+intent.getAction()+";package="+intent.getPackage();
    }
    static void call(Intent intent,Activity activity){
        if(!ON)return;
        int n=USED.incrementAndGet();
        if(n>MAX){if(n==MAX+1)android.util.Log.i("TripoderStarts","trace capped at "+MAX+" lines");return;}
        android.util.Log.i("TripoderStarts","#"+n+" CALL target="+target(intent)+";caller="+(activity==null?"null":activity.getClass().getName())+";flags=0x"+Integer.toHexString(intent==null?0:intent.getFlags()));
    }
    static void handed(String outcome,Intent intent){
        if(!ON)return;
        int n=USED.incrementAndGet();
        if(n>MAX)return;
        android.util.Log.i("TripoderStarts","#"+n+" "+outcome+" target="+target(intent));
    }
    static void absent(Throwable cause,Intent intent){
        if(!ON)return;
        int n=USED.incrementAndGet();
        if(n>MAX)return;
        String message=cause==null?"null":cause.getClass().getName()+": "+cause.getMessage();
        if(message.length()>160)message=message.substring(0,160);
        android.util.Log.w("TripoderStarts","#"+n+" ABSENT "+message+" target="+target(intent));
    }
    static void absent(String reason,Intent intent){
        if(!ON)return;
        int n=USED.incrementAndGet();
        if(n>MAX)return;
        android.util.Log.w("TripoderStarts","#"+n+" ABSENT "+reason+" target="+target(intent));
    }
}
