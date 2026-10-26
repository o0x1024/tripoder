package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Framework reads are nonvirtual: guest overrides/toString are never executed. */
public final class JavaObjectSnapshots {
    private static native String message(Throwable value);
    private static native Throwable cause(Throwable value);
    private static native StackTraceElement[] trace(Throwable value);
    private static native Throwable[] suppressed(Throwable value);
    private static native String threadName(Thread value);
    private static native long threadId(Thread value);
    private static native Thread.State threadState(Thread value);
    public static Map<String,Object> thread(Object reference){
        Object value=JavaObjectScope.object(reference);require(value instanceof Thread,"HOOK_OBJECT_TYPE_MISMATCH:java.lang.Thread");Thread thread=(Thread)value;
        String name=threadName(thread);return Map.of("className",thread.getClass().getName(),"name",bounded(name,256),"nameTruncated",name.length()>256,"id",Long.toString(threadId(thread)),"state",threadState(thread).name());
    }
    public static Map<String,Object> throwable(Object reference,int maxFrames,int maxCauses){
        require(maxFrames>=1&&maxFrames<=32&&maxCauses>=1&&maxCauses<=6,"HOOK_SNAPSHOT_LIMIT");Object value=JavaObjectScope.object(reference);
        require(value instanceof Throwable,"HOOK_OBJECT_TYPE_MISMATCH:java.lang.Throwable");
        List<Map<String,Object>> causes=new ArrayList<>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());Throwable current=(Throwable)value;int remaining=maxFrames;boolean truncated=false,cycle=false;
        while(current!=null&&causes.size()<maxCauses){if(!seen.add(current)){cycle=true;break;}
            String raw=message(current);StackTraceElement[] stack=trace(current);List<String> frames=new ArrayList<>();int count=Math.min(remaining,stack.length);
            boolean frameTextTruncated=false;for(int i=0;i<count;i++){String frame=stack[i].toString();frameTextTruncated|=frame.length()>192;frames.add(bounded(frame,192));}remaining-=count;
            Map<String,Object> item=new LinkedHashMap<>();item.put("className",current.getClass().getName());item.put("message",raw==null?null:bounded(raw,512));item.put("frames",List.copyOf(frames));item.put("framesTotal",(double)stack.length);
            item.put("messageTruncated",raw!=null&&raw.length()>512);item.put("framesTruncated",count<stack.length);item.put("frameTextTruncated",frameTextTruncated);
            Throwable[] suppressed=suppressed(current);List<Map<String,Object>> summaries=new ArrayList<>();
            for(int i=0;i<Math.min(4,suppressed.length);i++){Throwable error=suppressed[i];String detail=message(error);Map<String,Object> summary=new LinkedHashMap<>();summary.put("className",error.getClass().getName());summary.put("message",detail==null?null:bounded(detail,256));summary.put("messageTruncated",detail!=null&&detail.length()>256);summaries.add(Collections.unmodifiableMap(summary));}
            item.put("suppressed",List.copyOf(summaries));item.put("suppressedTruncated",suppressed.length>4);causes.add(Collections.unmodifiableMap(item));current=cause(current);
        }
        truncated=current!=null&&!cycle;
        Map<String,Object> out=new LinkedHashMap<>();out.put("causes",List.copyOf(causes));out.put("causesTruncated",truncated);out.put("causeCycle",cycle);
        return Collections.unmodifiableMap(out);
    }
    /** Direct field access, never a guest-overridden getter: Field.get bypasses virtual dispatch entirely. */
    public static Object readField(Object reference,String fieldName){
        String name=ScriptValues.key(fieldName);Object object=JavaObjectScope.object(reference);
        java.lang.reflect.Field field=declared(object.getClass(),name);require(field!=null,"HOOK_FIELD_NOT_FOUND:"+name);
        JavaHookValues.requireInvocationType(field.getType(),true);
        field.setAccessible(true);Object raw;try{raw=field.get(object);}catch(IllegalAccessException impossible){throw new AssertionError(impossible);}
        return JavaHookValues.decode(field.getType(),raw);
    }
    private static java.lang.reflect.Field declared(Class<?> type,String name){
        for(Class<?> cursor=type;cursor!=null;cursor=cursor.getSuperclass()){
            try{return cursor.getDeclaredField(name);}catch(NoSuchFieldException missing){/* keep walking up */}
        }
        return null;
    }
    private static String bounded(String value,int limit){return value.length()>limit?value.substring(0,limit):value;}
    private JavaObjectSnapshots(){}
}
