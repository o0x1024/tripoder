package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import dev.tripoder.runtime.script.HookPreflightRejected;
import java.util.*;

/** Agent-driven component delivery and provider access, issued as the guest application itself so the engine's own routing applies. */
final class RuntimeComponentDelivery {
    private RuntimeComponentDelivery(){}
    private static final int MAX_EXTRAS=32,MAX_TEXT=2048,CELL_TEXT=1024,CELL_BLOB=256;

    static Map<String,Object> send(Map<String,Object> request){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null||engine.application==null)throw new IllegalStateException("COMPONENT_APPLICATION_NOT_READY");
        String kind=string(request,"kind",true,16);if(!Set.of("activity","service","broadcast").contains(kind))throw reject("COMPONENT_KIND");
        String component=string(request,"component",false,512);
        Intent intent=new Intent();
        if(!component.isEmpty()){
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null||name.getPackageName().isEmpty()||name.getClassName().isEmpty())throw reject("COMPONENT_NAME");
            intent.setComponent(name);
        }else if(!kind.equals("broadcast"))throw reject("COMPONENT_REQUIRED_FOR_"+kind.toUpperCase(Locale.ROOT));
        String action=string(request,"action",false,256);if(!action.isEmpty())intent.setAction(action);
        String data=string(request,"dataUri",false,MAX_TEXT),mime=string(request,"mimeType",false,128);
        if(!data.isEmpty()&&!mime.isEmpty())intent.setDataAndType(Uri.parse(data),mime);else if(!data.isEmpty())intent.setData(Uri.parse(data));else if(!mime.isEmpty())intent.setType(mime);
        Object categories=request.get("categories");
        if(categories!=null){if(!(categories instanceof List<?> list)||list.size()>8)throw reject("COMPONENT_CATEGORIES");for(Object category:list){if(!(category instanceof String text)||text.isEmpty()||text.length()>256)throw reject("COMPONENT_CATEGORIES");intent.addCategory(text);}}
        Object flags=request.get("flags");
        if(flags!=null){if(!(flags instanceof Double number)||number!=Math.rint(number)||number<0||number>Integer.MAX_VALUE)throw reject("COMPONENT_FLAGS");intent.addFlags(number.intValue());}
        Object extras=request.get("extras");
        if(extras!=null){intent.putExtras(bundle(extras,0));}
        if(kind.equals("activity"))intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{
            RuntimeMalwareProbeState.checkAndRecordIntent("component_send_intent:" + kind, intent);
            switch(kind){
                case "activity":engine.application.startActivity(intent);break;
                case "service":if(engine.application.startService(intent)==null)throw reject("COMPONENT_SERVICE_NOT_FOUND");break;
                default:engine.application.sendBroadcast(intent);
            }
        }catch(android.content.ActivityNotFoundException missing){throw reject("COMPONENT_ACTIVITY_NOT_FOUND:"+String.valueOf(missing.getMessage()));}
        catch(HookPreflightRejected rejected){throw rejected;}
        catch(RuntimeException failure){
            // The engine refuses these before anything is launched, so they are certain rejections rather than unknown outcomes.
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                String message=String.valueOf(cause.getMessage());
                for(String code:new String[]{"COMPONENT_NOT_DECLARED","ACTIVITY_COMPONENT_REQUIRED","PACKAGE_NOT_IN_SPACE","COMPONENT_NOT_EXPORTED","MALWARE_PROBE_HIGH_RISK_INTENT_BLOCKED"})if(message.contains(code))throw reject(message.substring(message.indexOf(code)));
            }
            throw failure;
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("kind",kind);out.put("component",component);out.put("action",action);out.put("dispatched",true);
        out.put("note","Dispatch accepted by the guest context only; it does not prove the component ran. Observe it with ui_tree, hooks, or a read-back.");
        return Collections.unmodifiableMap(out);
    }

    static Map<String,Object> provider(Map<String,Object> request){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null||engine.application==null)throw new IllegalStateException("PROVIDER_APPLICATION_NOT_READY");
        ContentResolver resolver=engine.application.getContentResolver();
        String operation=string(request,"operation",true,16);Uri uri=Uri.parse(string(request,"uri",true,MAX_TEXT));
        if(!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty())throw reject("PROVIDER_URI");
        Map<String,Object> out=new LinkedHashMap<>();out.put("operation",operation);
        switch(operation){
            case "query":{
                String[] projection=strings(request.get("projection"),32,128);String selection=nullable(request,"selection",1024);String[] args=strings(request.get("selectionArgs"),16,512);String sort=nullable(request,"sortOrder",128);
                int limit=bounded(request.get("limit"),1,100,20);
                try(Cursor cursor=resolver.query(uri,projection,selection,args,sort)){
                    if(cursor==null){out.put("cursor",false);out.put("columns",List.of());out.put("rows",List.of());out.put("truncated",false);break;}
                    String[] columns=cursor.getColumnNames();List<List<Object>> rows=new ArrayList<>();boolean truncated=false;
                    while(cursor.moveToNext()){if(rows.size()>=limit){truncated=true;break;}List<Object> row=new ArrayList<>();for(int i=0;i<columns.length;i++)row.add(cell(cursor,i));rows.add(Collections.unmodifiableList(row));}
                    out.put("cursor",true);out.put("columns",List.of(columns));out.put("rows",List.copyOf(rows));out.put("truncated",truncated);
                }
                break;
            }
            case "insert":{Uri created=resolver.insert(uri,values(request.get("values")));out.put("uri",created==null?null:created.toString());break;}
            case "update":out.put("rows",(double)resolver.update(uri,values(request.get("values")),nullable(request,"selection",1024),strings(request.get("selectionArgs"),16,512)));break;
            case "delete":out.put("rows",(double)resolver.delete(uri,nullable(request,"selection",1024),strings(request.get("selectionArgs"),16,512)));break;
            case "call":{
                Bundle result=resolver.call(uri,string(request,"method",true,128),nullable(request,"arg",MAX_TEXT),request.get("extras")==null?null:bundle(request.get("extras"),0));
                out.put("result",result==null?null:describe(result));break;
            }
            default:throw reject("PROVIDER_OPERATION");
        }
        return Collections.unmodifiableMap(out);
    }

    private static HookPreflightRejected reject(String code){return new HookPreflightRejected(code);}
    private static String string(Map<String,Object> source,String key,boolean required,int max){
        Object value=source.get(key);if(value==null){if(required)throw reject("FIELD_REQUIRED:"+key);return "";}
        if(!(value instanceof String text)||text.length()>max)throw reject("FIELD_INVALID:"+key);if(required&&text.isEmpty())throw reject("FIELD_REQUIRED:"+key);return text;
    }
    private static String nullable(Map<String,Object> source,String key,int max){Object value=source.get(key);if(value==null)return null;if(!(value instanceof String text)||text.length()>max)throw reject("FIELD_INVALID:"+key);return text;}
    private static int bounded(Object value,int min,int max,int fallback){if(value==null)return fallback;if(!(value instanceof Double number)||number!=Math.rint(number)||number<min||number>max)throw reject("FIELD_RANGE");return number.intValue();}
    private static String[] strings(Object value,int count,int max){
        if(value==null)return null;if(!(value instanceof List<?> list)||list.size()>count)throw reject("FIELD_LIST");
        String[] out=new String[list.size()];for(int i=0;i<out.length;i++){if(!(list.get(i) instanceof String text)||text.length()>max)throw reject("FIELD_LIST");out[i]=text;}return out;
    }
    /** Inferred typing: boolean, String, JSON integer (int, else long), other numbers as double; one nested object level becomes a Bundle. */
    private static Bundle bundle(Object value,int depth){
        if(!(value instanceof Map<?,?> map)||map.size()>MAX_EXTRAS||depth>1)throw reject("EXTRAS_INVALID");
        Bundle out=new Bundle();
        for(Map.Entry<?,?> entry:map.entrySet()){
            if(!(entry.getKey() instanceof String key)||key.isEmpty()||key.length()>128)throw reject("EXTRAS_KEY");Object item=entry.getValue();
            if(item instanceof String text){if(text.length()>MAX_TEXT)throw reject("EXTRAS_VALUE");out.putString(key,text);}
            else if(item instanceof Boolean flag)out.putBoolean(key,flag);
            else if(item instanceof Double number){if(number!=Math.rint(number)||Math.abs(number)>9.007199254740992E15)out.putDouble(key,number);else if(number>=Integer.MIN_VALUE&&number<=Integer.MAX_VALUE)out.putInt(key,number.intValue());else out.putLong(key,number.longValue());}
            else if(item instanceof Map<?,?>)out.putBundle(key,bundle(item,depth+1));
            else throw reject("EXTRAS_VALUE");
        }
        return out;
    }
    private static ContentValues values(Object value){
        if(!(value instanceof Map<?,?> map)||map.isEmpty()||map.size()>MAX_EXTRAS)throw reject("PROVIDER_VALUES");
        ContentValues out=new ContentValues();
        for(Map.Entry<?,?> entry:map.entrySet()){
            if(!(entry.getKey() instanceof String key)||key.isEmpty()||key.length()>128)throw reject("PROVIDER_VALUES");Object item=entry.getValue();
            if(item==null)out.putNull(key);else if(item instanceof String text){if(text.length()>MAX_TEXT)throw reject("PROVIDER_VALUES");out.put(key,text);}
            else if(item instanceof Boolean flag)out.put(key,flag);
            else if(item instanceof Double number){if(number==Math.rint(number)&&Math.abs(number)<=9.007199254740992E15){if(number>=Integer.MIN_VALUE&&number<=Integer.MAX_VALUE)out.put(key,number.intValue());else out.put(key,number.longValue());}else out.put(key,number);}
            else throw reject("PROVIDER_VALUES");
        }
        return out;
    }
    private static Object cell(Cursor cursor,int index){
        switch(cursor.getType(index)){
            case Cursor.FIELD_TYPE_NULL:return null;
            case Cursor.FIELD_TYPE_INTEGER:{long number=cursor.getLong(index);return Math.abs(number)<=9007199254740992L?(Object)(double)number:Long.toString(number);}
            case Cursor.FIELD_TYPE_FLOAT:return cursor.getDouble(index);
            case Cursor.FIELD_TYPE_BLOB:{byte[] blob=cursor.getBlob(index);return Map.of("blobHex",HexFormat.of().formatHex(blob,0,Math.min(blob.length,CELL_BLOB)),"blobBytes",(double)blob.length);}
            default:{String text=cursor.getString(index);return text.length()>CELL_TEXT?text.substring(0,CELL_TEXT):text;}
        }
    }
    private static Map<String,Object> describe(Bundle bundle){
        Map<String,Object> out=new LinkedHashMap<>();int count=0;
        for(String key:bundle.keySet()){
            if(++count>MAX_EXTRAS){out.put("…truncated",true);break;}
            Object value=bundle.get(key);
            if(value==null||value instanceof Boolean)out.put(key,value);
            else if(value instanceof Number number)out.put(key,number instanceof Long?Long.toString(number.longValue()):(Object)number.doubleValue());
            else if(value instanceof CharSequence text){String s=text.toString();out.put(key,s.length()>CELL_TEXT?s.substring(0,CELL_TEXT):s);}
            else out.put(key,"<"+value.getClass().getName()+">");
        }
        return out;
    }
}
