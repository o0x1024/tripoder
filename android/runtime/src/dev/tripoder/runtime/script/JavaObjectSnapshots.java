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
    /** Shallow container snapshot without executing guest-overridden virtual methods or toString. */
    public static Map<String,Object> snapshotContainer(Object reference,int maxEntries){
        require(maxEntries>=1&&maxEntries<=64,"HOOK_SNAPSHOT_LIMIT:maxEntries");
        Object object=JavaObjectScope.object(reference);
        return snapshotLiveContainer(object,maxEntries);
    }
    public static Map<String,Object> snapshotLiveContainer(Object object,int maxEntries){
        require(maxEntries>=1&&maxEntries<=64,"HOOK_SNAPSHOT_LIMIT:maxEntries");
        require(object!=null,"HOOK_OBJECT_REFERENCE_REQUIRED");
        Class<?> cls=object.getClass();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("className",cls.getName());
        if(object instanceof android.content.Intent intent){
            out.put("containerKind","Intent");
            out.put("action",intent.getAction()==null?"":bounded(intent.getAction(),256));
            out.put("dataString",intent.getDataString()==null?"":bounded(intent.getDataString(),512));
            android.content.ComponentName comp=intent.getComponent();
            out.put("component",comp==null?"":bounded(comp.flattenToString(),256));
            out.put("flags",(double)intent.getFlags());
            Set<String> cats=intent.getCategories();
            List<String> catList=new ArrayList<>();
            if(cats!=null){for(String c:cats){if(catList.size()>=16)break;if(c!=null)catList.add(bounded(c,128));}}
            out.put("categories",List.copyOf(catList));
            android.os.Bundle extras=intent.getExtras();
            out.put("extras",extras==null?Map.of():bundleEntries(extras,maxEntries));
            return Collections.unmodifiableMap(out);
        }
        if(object instanceof android.os.BaseBundle bundle){
            out.put("containerKind","Bundle");
            Map<String,Object> entries=bundleEntries(bundle,maxEntries);
            out.put("size",(double)bundle.size());
            out.put("entries",entries);
            out.put("truncated",bundle.size()>maxEntries);
            return Collections.unmodifiableMap(out);
        }
        if(object instanceof android.content.ContentValues values){
            out.put("containerKind","ContentValues");
            Map<String,Object> entries=new LinkedHashMap<>();
            int count=0;
            for(String key:values.keySet()){
                if(count>=maxEntries)break;
                if(key!=null){entries.put(bounded(key,128),shallowValue(values.get(key)));count++;}
            }
            out.put("size",(double)values.size());
            out.put("entries",Collections.unmodifiableMap(entries));
            out.put("truncated",values.size()>maxEntries);
            return Collections.unmodifiableMap(out);
        }
        if(object instanceof android.net.Uri uri){
            out.put("containerKind","Uri");
            out.put("uri",bounded(String.valueOf(uri),1024));
            out.put("scheme",uri.getScheme()==null?"":bounded(uri.getScheme(),64));
            out.put("authority",uri.getAuthority()==null?"":bounded(uri.getAuthority(),256));
            out.put("path",uri.getPath()==null?"":bounded(uri.getPath(),512));
            out.put("query",uri.getQuery()==null?"":bounded(uri.getQuery(),512));
            return Collections.unmodifiableMap(out);
        }
        if(object instanceof org.json.JSONObject json&&cls==org.json.JSONObject.class){
            out.put("containerKind","JSONObject");
            Map<String,Object> entries=new LinkedHashMap<>();
            int total=json.length(),count=0;
            Iterator<String> it=json.keys();
            while(it.hasNext()){
                String key=it.next();
                if(count<maxEntries&&key!=null){entries.put(bounded(key,128),shallowValue(json.opt(key)));count++;}
            }
            out.put("size",(double)total);
            out.put("entries",Collections.unmodifiableMap(entries));
            out.put("truncated",total>maxEntries);
            return Collections.unmodifiableMap(out);
        }
        if(cls.isArray()){
            out.put("containerKind","Array");
            int length=java.lang.reflect.Array.getLength(object);
            out.put("size",(double)length);
            if(object instanceof byte[] bytes){
                int take=Math.min(length,Math.min(maxEntries*16,1024));
                out.put("hex",HexFormat.of().formatHex(bytes,0,take));
                out.put("truncated",length>take);
            }else{
                List<Object> items=new ArrayList<>(Math.min(length,maxEntries));
                for(int i=0;i<Math.min(length,maxEntries);i++)items.add(shallowValue(java.lang.reflect.Array.get(object,i)));
                out.put("elements",Collections.unmodifiableList(items));
                out.put("truncated",length>maxEntries);
            }
            return Collections.unmodifiableMap(out);
        }
        if(cls.getClassLoader()==null&&object instanceof Map<?,?> map){
            out.put("containerKind","Map");
            Map<String,Object> entries=new LinkedHashMap<>();
            int total=map.size(),count=0;
            for(Map.Entry<?,?> e:map.entrySet()){
                if(count>=maxEntries)break;
                String k=shallowKey(e.getKey());
                entries.put(k,shallowValue(e.getValue()));
                count++;
            }
            out.put("size",(double)total);
            out.put("entries",Collections.unmodifiableMap(entries));
            out.put("truncated",total>maxEntries);
            return Collections.unmodifiableMap(out);
        }
        if(cls.getClassLoader()==null&&object instanceof Collection<?> col){
            out.put("containerKind","Collection");
            List<Object> items=new ArrayList<>();
            int total=col.size(),count=0;
            for(Object item:col){
                if(count>=maxEntries)break;
                items.add(shallowValue(item));
                count++;
            }
            out.put("size",(double)total);
            out.put("elements",Collections.unmodifiableList(items));
            out.put("truncated",total>maxEntries);
            return Collections.unmodifiableMap(out);
        }
        // Generic object shallow field summary (non-virtual declared fields up to maxEntries)
        out.put("containerKind","ObjectFields");
        Map<String,Object> fields=new LinkedHashMap<>();
        int count=0;boolean truncated=false;
        for(Class<?> cursor=cls;cursor!=null&&cursor!=Object.class;cursor=cursor.getSuperclass()){
            for(java.lang.reflect.Field f:cursor.getDeclaredFields()){
                if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
                if(count>=maxEntries){truncated=true;break;}
                try{
                    f.setAccessible(true);
                    fields.putIfAbsent(f.getName(),shallowValue(f.get(object)));
                    count=fields.size();
                }catch(Throwable ignored){}
            }
            if(truncated)break;
        }
        out.put("size",(double)fields.size());
        out.put("entries",Collections.unmodifiableMap(fields));
        out.put("truncated",truncated);
        return Collections.unmodifiableMap(out);
    }
    private static Map<String,Object> bundleEntries(android.os.BaseBundle bundle,int maxEntries){
        Map<String,Object> entries=new LinkedHashMap<>();
        int count=0;
        for(String key:bundle.keySet()){
            if(count>=maxEntries)break;
            if(key!=null){entries.put(bounded(key,128),shallowValue(bundle.get(key)));count++;}
        }
        return Collections.unmodifiableMap(entries);
    }
    private static String shallowKey(Object key){
        if(key==null)return "null";
        if(key instanceof String s)return bounded(s,128);
        if(key instanceof Number||key instanceof Boolean||key instanceof Character)return String.valueOf(key);
        return key.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(key));
    }
    public static Object shallowValue(Object val){
        if(val==null||val==org.json.JSONObject.NULL)return null;
        if(val instanceof String s)return bounded(s,512);
        if(val instanceof Boolean b)return b;
        if(val instanceof Character c)return String.valueOf(c);
        if(val instanceof Long l)return Long.toString(l);
        if(val instanceof Number n){double d=n.doubleValue();return Double.isFinite(d)?d:String.valueOf(n);}
        if(val instanceof byte[] b){int take=Math.min(b.length,256);return HexFormat.of().formatHex(b,0,take);}
        return Map.of("className",val.getClass().getName(),"identityHash",(double)System.identityHashCode(val));
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
