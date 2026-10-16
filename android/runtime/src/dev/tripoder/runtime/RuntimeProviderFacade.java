package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** One logical acquisition owns exactly one real Android stable/unstable client reference. */
public final class RuntimeProviderFacade extends RuntimeProviderBase implements AutoCloseable {
    private final RuntimeEngine engine;
    private final Bundle reference;
    private final ContentProviderClient client;
    private boolean closed,released;private int uses;
    RuntimeProviderFacade(RuntimeEngine engine,Bundle reference,ContentProviderClient client){this.engine=engine;this.reference=reference;this.client=client;ProviderInfo info=new ProviderInfo();info.authority=reference.getString("authority");info.exported=false;attachInfo(engine.host,info);}
    private Bundle request(){Bundle b=engine.auth();b.putString("cap",reference.getString("cap"));return b;}
    void awaitReady()throws Exception{
        Bundle wait=request();wait.putBoolean("begin",true);boolean edge=engine.brokerCall(RuntimeWire.PROVIDER_WAIT,wait).getBoolean("edge");
        try{invoke("ready",Uri.parse("content://"+reference.getString("authority")),new Bundle(),null);}
        finally{if(edge){wait.putBoolean("begin",false);engine.brokerCall(RuntimeWire.PROVIDER_WAIT,wait);}}
    }
    static Bundle primitive(Bundle input){
        if(input==null)return null;if(input.size()>64)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");Bundle copy=new Bundle();
        for(String key:input.keySet()){if(key.length()>200)throw new IllegalArgumentException("PROVIDER_KEY_TOO_LONG");Object v=input.get(key);
            if(v==null)copy.putString(key,null);else if(v instanceof String){if(((String)v).length()>8192)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putString(key,(String)v);}else if(v instanceof Integer)copy.putInt(key,(Integer)v);else if(v instanceof Long)copy.putLong(key,(Long)v);else if(v instanceof Boolean)copy.putBoolean(key,(Boolean)v);
            else if(v instanceof String[]){String[] array=(String[])v;if(array.length>64)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");for(String value:array)if(value!=null&&value.length()>8192)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putStringArray(key,array.clone());}else throw new IllegalArgumentException("PROVIDER_EXTRA_TYPE_UNSUPPORTED");
        }Parcel p=Parcel.obtain();try{p.writeBundle(copy);if(p.dataSize()>32768)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");}finally{p.recycle();}return copy;
    }
    static String digest(Bundle b)throws Exception{Parcel p=Parcel.obtain();try{p.writeBundle(b);return ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(p.marshall()));}finally{p.recycle();}}
    private Object invoke(String kind,Uri uri,Bundle payload,String[] projection)throws Exception{
        synchronized(this){if(closed)throw new IllegalStateException("PROVIDER_CLIENT_CLOSED");uses++;}
        try{return invokeHeld(kind,uri,payload,projection);}finally{endUse();}
    }
    private Object invokeHeld(String kind,Uri uri,Bundle payload,String[] projection)throws Exception{
        if(uri==null||!"content".equals(uri.getScheme())||!reference.getString("authority").equals(uri.getAuthority()))throw new SecurityException("PROVIDER_URI_MISMATCH");
        Bundle envelope=new Bundle();envelope.putBundle("payload",payload);envelope.putStringArray("projection",projection);String digest=digest(envelope);
        Bundle begin=request();begin.putString("kind",kind);begin.putString("uri",uri.toString());begin.putString("digest",digest);begin.putString("operation",UUID.randomUUID().toString());if(kind.equals("applyBatch"))RuntimeProviderBatch.summary(begin,payload,reference.getString("authority"));engine.brokerCall(RuntimeWire.PROVIDER_BEGIN,begin);
        envelope.putString("cap",reference.getString("cap"));envelope.putString("operation",begin.getString("operation"));envelope.putString("uri",uri.toString());envelope.putString("kind",kind);
        long identity=Binder.clearCallingIdentity();try{
            Object result;
            if(kind.equals("query")){Uri wire=new Uri.Builder().scheme("content").authority(reference.getString("physical")).appendPath("v1").appendPath(reference.getString("cap")).appendPath(begin.getString("operation")).appendQueryParameter("uri",uri.toString()).build();result=retain(client.query(wire,projection,payload,null));}
            else {Bundle response=client.call("tripoder.dispatch",null,envelope);result=kind.equals("getType")?response.getString("type"):response;}
            // This proves the source adapter received the response, not that application code consumed it.
            Bundle receipt=request();receipt.putString("operation",begin.getString("operation"));
            try{engine.brokerCall(RuntimeWire.PROVIDER_RECEIVED,receipt);}catch(Exception error){if(result instanceof Cursor)try{((Cursor)result).close();}catch(Exception close){error.addSuppressed(close);}throw error;}
            return result;
        }finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal){if(signal!=null)throw unsupported();Bundle payload=primitive(args);String[] columns=projection==null?null:projection.clone();if(columns!=null){if(columns.length>128)throw new IllegalArgumentException("PROVIDER_PROJECTION_TOO_LARGE");for(String column:columns)if(column==null||column.length()>512)throw new IllegalArgumentException("PROVIDER_PROJECTION_INVALID");}try{return (Cursor)invoke("query",uri,payload,columns);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("PROVIDER_QUERY_FAILED",e);}}
    @Override public String getType(Uri uri){try{return (String)invoke("getType",uri,new Bundle(),null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("PROVIDER_TYPE_FAILED",e);}}
    private Bundle write(String kind,Uri uri,Bundle payload){try{return (Bundle)invoke(kind,uri,payload,null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("PROVIDER_WRITE_FAILED:"+kind,e);}}
    @Override public Uri insert(Uri uri,ContentValues values,Bundle extras){String inserted=write("insert",uri,RuntimeProviderValues.single(values,extras)).getString("uri");return inserted==null?null:Uri.parse(inserted);}
    @Override public int update(Uri uri,ContentValues values,Bundle extras){return write("update",uri,RuntimeProviderValues.single(values,extras)).getInt("count");}
    @Override public int delete(Uri uri,Bundle extras){return write("delete",uri,RuntimeProviderValues.single(null,extras)).getInt("count");}
    @Override public int bulkInsert(Uri uri,ContentValues[] values){return write("bulkInsert",uri,RuntimeProviderValues.bulk(values)).getInt("count");}
    @Override public ContentProviderResult[] applyBatch(String authority,ArrayList<ContentProviderOperation> operations){if(!reference.getString("authority").equals(authority))throw new SecurityException("PROVIDER_AUTHORITY_MISMATCH");return write("applyBatch",Uri.parse("content://"+authority),RuntimeProviderBatch.snapshot(operations,authority)).getParcelableArray("results",ContentProviderResult.class);}
    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations){return applyBatch(reference.getString("authority"),operations);}
    @Override public Bundle call(String authority,String method,String arg,Bundle extras){if(!reference.getString("authority").equals(authority))throw new SecurityException("PROVIDER_AUTHORITY_MISMATCH");if(method==null||method.isEmpty()||method.length()>200||arg!=null&&arg.length()>8192)throw new IllegalArgumentException("PROVIDER_CALL_INVALID");Bundle payload=new Bundle();payload.putString("method",method);payload.putString("arg",arg);payload.putBundle("extras",primitive(extras));try{return (Bundle)invoke("call",Uri.parse("content://"+authority),payload,null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("PROVIDER_CALL_FAILED",e);}}
    @Override public Bundle call(String method,String arg,Bundle extras){return call(reference.getString("authority"),method,arg,extras);}
    private Cursor retain(Cursor cursor){
        if(cursor==null)return null;synchronized(this){uses++;}
        return new android.database.CursorWrapper(cursor){private final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void close(){if(!closing.compareAndSet(false,true))return;super.close();endUse();}
        };
    }
    private void endUse(){synchronized(this){if(uses<=0)throw new IllegalStateException("PROVIDER_USE_UNBALANCED");uses--;}releaseIfIdle();}
    private void releaseIfIdle(){
        synchronized(this){if(!closed||uses!=0||released)return;released=true;}
        client.close();try{engine.brokerCall(RuntimeWire.PROVIDER_RELEASE,request());}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("PROVIDER_RELEASE_UNCONFIRMED",e);}
    }
    @Override public void close(){synchronized(this){closed=true;}releaseIfIdle();}
}
