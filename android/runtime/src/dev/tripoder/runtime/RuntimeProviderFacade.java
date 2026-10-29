package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.FileNotFoundException;
import java.util.*;

/** One logical acquisition owns exactly one real Android stable/unstable client reference. */
public final class RuntimeProviderFacade extends RuntimeProviderBase implements AutoCloseable {
    private final RuntimeEngine engine;
    private final Bundle reference;
    private final ContentProviderClient client;
    private boolean closed,released;private int uses;
    private final boolean thin;
    public boolean isClosed(){synchronized(this){return closed||released;}}
    RuntimeProviderFacade(RuntimeEngine engine,Bundle reference,ContentProviderClient client){this.engine=engine;this.reference=reference;this.client=client;this.thin=reference.getBoolean("thin");ProviderInfo info=new ProviderInfo();info.authority=reference.getString("authority");info.exported=false;attachInfo(engine.host,info);}
    private Bundle request(){Bundle b=engine.auth();b.putString("cap",reference.getString("cap"));return b;}
    void awaitReady()throws Exception{
        if(thin){invoke("ready",Uri.parse("content://"+reference.getString("authority")),new Bundle(),null);return;}
        Bundle wait=request();wait.putBoolean("begin",true);boolean edge=engine.brokerCall(RuntimeWire.PROVIDER_WAIT,wait).getBoolean("edge");
        try{invoke("ready",Uri.parse("content://"+reference.getString("authority")),new Bundle(),null);}
        finally{if(edge){wait.putBoolean("begin",false);engine.brokerCall(RuntimeWire.PROVIDER_WAIT,wait);}}
    }
    static Bundle primitive(Bundle input){
        Bundle copy=primitive(input,0);
        if(copy==null)return null;
        Parcel p=Parcel.obtain();try{p.writeBundle(copy);if(p.dataSize()>524288)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");}finally{p.recycle();}return copy;
    }
    /** Platform classes exist, by name, in every process; the sender's own classes exist in the processes of its own package. Both are carried as opaque data the authority never unparcels. */
    private static boolean platformClass(Class<?> type){ClassLoader loader=type.getClassLoader();return loader==null||"java.lang.BootClassLoader".equals(loader.getClass().getName());}
    private static boolean admits(Class<?> type){
        Class<?> element=type.isArray()?type.getComponentType():type;
        return platformClass(element)||guestOwned(element);
    }
    private static boolean admitsElements(java.util.List<?> items){for(Object item:items)if(item!=null&&!admits(item.getClass()))return false;return true;}
    private static boolean allOf(java.util.List<?> items,Class<?> type){for(Object item:items)if(!type.isInstance(item))return false;return true;}
    private static boolean sparse(android.util.SparseArray<?> array){if(array.size()>256)return false;for(int i=0;i<array.size();i++){Object item=array.valueAt(i);if(!(item instanceof android.os.Parcelable)||!admits(item.getClass()))return false;}return true;}
    private static String describe(Object item){return item==null?"null":item.getClass().getName()+" loader="+item.getClass().getClassLoader();}
    /** A Binder object travels natively in a Bundle between app processes. The authority's own endpoint is the one thing a guest must never hand around. */
    private static void requireBinder(Object value){RuntimeEngine engine=RuntimeEngine.current;if(engine!=null&&engine.isBroker(value))throw new SecurityException("PROVIDER_EXTRA_AUTHORITY_BINDER_DENIED");}
    /** A Parcelable defined by this package's own loader is application data for its own components; it is never interpreted outside that package. */
    private static boolean guestOwned(Class<?> type){RuntimeEngine engine=RuntimeEngine.current;return engine!=null&&type.getClassLoader()!=null&&engine.ownsLoader(type.getClassLoader());}
    /** Finite data only: scalars, bounded text, bounded primitive arrays and nested Bundles. Nothing here carries authority or descriptors. */
    private static Bundle primitive(Bundle input,int depth){
        if(input==null)return null;if(depth>3||input.size()>512)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");Bundle copy=new Bundle();
        for(String key:input.keySet()){if(key.length()>512)throw new IllegalArgumentException("PROVIDER_KEY_TOO_LONG");Object v=input.get(key);
            if(v==null)copy.putString(key,null);
            else if(v instanceof String){if(((String)v).length()>524288)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putString(key,(String)v);}
            else if(v instanceof Integer)copy.putInt(key,(Integer)v);else if(v instanceof Long)copy.putLong(key,(Long)v);else if(v instanceof Boolean)copy.putBoolean(key,(Boolean)v);
            else if(v instanceof Double)copy.putDouble(key,(Double)v);else if(v instanceof Float)copy.putFloat(key,(Float)v);else if(v instanceof Short)copy.putShort(key,(Short)v);else if(v instanceof Byte)copy.putByte(key,(Byte)v);else if(v instanceof Character)copy.putChar(key,(Character)v);
            else if(v instanceof Bundle)copy.putBundle(key,primitive((Bundle)v,depth+1));
            else if(v instanceof byte[]){if(((byte[])v).length>524288)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putByteArray(key,((byte[])v).clone());}
            else if(v instanceof int[]){if(((int[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putIntArray(key,((int[])v).clone());}
            else if(v instanceof long[]){if(((long[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putLongArray(key,((long[])v).clone());}
            else if(v instanceof boolean[]){if(((boolean[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putBooleanArray(key,((boolean[])v).clone());}
            else if(v instanceof float[]){if(((float[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putFloatArray(key,((float[])v).clone());}
            else if(v instanceof double[]){if(((double[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putDoubleArray(key,((double[])v).clone());}
            else if(v instanceof String[]){String[] array=(String[])v;if(array.length>512)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");for(String value:array)if(value!=null&&value.length()>524288)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putStringArray(key,array.clone());}
            else if(v instanceof CharSequence){if(((CharSequence)v).length()>524288)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putCharSequence(key,(CharSequence)v);}
            else if(v instanceof android.os.IBinder){requireBinder(v);copy.putBinder(key,(android.os.IBinder)v);}
            else if(v instanceof android.os.Parcelable&&admits(v.getClass()))copy.putParcelable(key,(android.os.Parcelable)v);
            else if(v instanceof android.os.Parcelable[]&&admitsElements(java.util.Arrays.asList((Object[])v))){if(((Object[])v).length>256)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putParcelableArray(key,(android.os.Parcelable[])((android.os.Parcelable[])v).clone());}
            else if(v instanceof android.util.SparseArray&&sparse((android.util.SparseArray<?>)v))copy.putSparseParcelableArray(key,(android.util.SparseArray<android.os.Parcelable>)v);
            else if(v instanceof java.util.ArrayList&&((java.util.ArrayList<?>)v).size()<=256){
                java.util.ArrayList<?> list=(java.util.ArrayList<?>)v;
                if(list.isEmpty())copy.putStringArrayList(key,new java.util.ArrayList<>());
                else if(allOf(list,String.class)){for(Object item:list)if(((String)item).length()>8192)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putStringArrayList(key,new java.util.ArrayList<>((java.util.ArrayList<String>)list));}
                else if(allOf(list,Integer.class))copy.putIntegerArrayList(key,new java.util.ArrayList<>((java.util.ArrayList<Integer>)list));
                else if(allOf(list,CharSequence.class))copy.putCharSequenceArrayList(key,new java.util.ArrayList<>((java.util.ArrayList<CharSequence>)list));
                else if(admitsElements(list)&&allOf(list,android.os.Parcelable.class))copy.putParcelableArrayList(key,new java.util.ArrayList<>((java.util.ArrayList<android.os.Parcelable>)list));
                else throw new IllegalArgumentException("PROVIDER_EXTRA_TYPE_UNSUPPORTED:"+key+":list-of-"+describe(list.get(0)));
            }
            else if(v instanceof java.io.Serializable&&admits(v.getClass()))copy.putSerializable(key,(java.io.Serializable)v);
            else throw new IllegalArgumentException("PROVIDER_EXTRA_TYPE_UNSUPPORTED:"+key+":"+v.getClass().getName()+" super="+v.getClass().getSuperclass()+" ifaces="+java.util.Arrays.toString(v.getClass().getInterfaces())+" loader="+v.getClass().getClassLoader());
        }return copy;
    }
    /** Binders cannot be marshalled to bytes, so a Bundle that carries one is digested by what can be seen on both sides: key, kind and (for plain data) the bytes with each Binder replaced by a marker. */
    static String digest(Bundle b)throws Exception{
        Parcel p=Parcel.obtain();try{p.writeBundle(markBinders(b));return ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(p.marshall()));}
        catch(RuntimeException unmarshallable){return ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(kinds(b).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        finally{p.recycle();}
    }
    private static Bundle markBinders(Bundle input){
        if(input==null)return null;Bundle copy=new Bundle(input);
        for(String key:new java.util.ArrayList<>(copy.keySet())){Object value=copy.get(key);
            if(value instanceof android.os.IBinder)copy.putString(key,"\0binder");
            else if(value instanceof Bundle)copy.putBundle(key,markBinders((Bundle)value));}
        return copy;
    }
    private static String kinds(Bundle input){StringBuilder text=new StringBuilder();for(String key:new java.util.TreeSet<>(input.keySet())){Object value=input.get(key);text.append(key).append('=').append(value==null?"null":value instanceof Bundle?"{"+kinds((Bundle)value)+"}":value.getClass().getName()).append(';');}return text.toString();}
    private Object invoke(String kind,Uri uri,Bundle payload,String[] projection)throws Exception{
        synchronized(this){if(closed)throw new IllegalStateException("PROVIDER_CLIENT_CLOSED");uses++;}
        try{return thin?invokeThin(kind,uri,payload,projection):invokeHeld(kind,uri,payload,projection);}finally{endUse();}
    }
    private Object invokeThin(String kind,Uri uri,Bundle payload,String[] projection)throws Exception{
        if(uri==null||!"content".equals(uri.getScheme())||!reference.getString("authority").equals(uri.getAuthority()))throw new SecurityException("PROVIDER_URI_MISMATCH");
        long identity=Binder.clearCallingIdentity();try{
            if(kind.equals("query")){
                Uri wire=new Uri.Builder().scheme("content").authority(reference.getString("physical")).appendPath("v2-thin").appendPath(reference.getString("lease")).appendQueryParameter("uri",uri.toString()).appendQueryParameter("source",engine.generation).build();
                return retain(client.query(wire,projection,payload,null));
            }
            Bundle envelope=new Bundle();
            envelope.putString("lease",reference.getString("lease"));
            envelope.putString("authority",reference.getString("authority"));
            envelope.putString("kind",kind);
            envelope.putString("uri",uri.toString());
            envelope.putString("source",engine.generation);
            envelope.putBundle("payload",payload);
            envelope.putStringArray("projection",projection);
            Bundle response=client.call("tripoder.thin.v2",null,envelope);
            return kind.equals("getType")?response.getString("type"):response;
        }finally{Binder.restoreCallingIdentity(identity);}
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
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode,CancellationSignal signal)throws FileNotFoundException{
        try{return client.openFile(uri,mode,signal);}
        catch(RemoteException e){throw new IllegalStateException("PROVIDER_OPEN_FILE_FAILED",e);}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return openFile(uri,mode,null);}
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode,CancellationSignal signal)throws FileNotFoundException{
        try{return client.openAssetFile(uri,mode,signal);}
        catch(RemoteException e){throw new IllegalStateException("PROVIDER_OPEN_ASSET_FILE_FAILED",e);}
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException{return openAssetFile(uri,mode,null);}
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri,String type,Bundle opts,CancellationSignal signal)throws FileNotFoundException{
        try{return client.openTypedAssetFileDescriptor(uri,type,opts,signal);}
        catch(RemoteException e){throw new IllegalStateException("PROVIDER_OPEN_TYPED_ASSET_FILE_FAILED",e);}
    }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri,String type,Bundle opts)throws FileNotFoundException{return openTypedAssetFile(uri,type,opts,null);}
    private Cursor retain(Cursor cursor){
        if(cursor==null)return null;synchronized(this){uses++;}
        return new android.database.CursorWrapper(cursor){private final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void close(){if(!closing.compareAndSet(false,true))return;try{super.close();}finally{endUse();}}
        };
    }
    private void endUse(){synchronized(this){if(uses<=0)throw new IllegalStateException("PROVIDER_USE_UNBALANCED");uses--;}releaseIfIdle();}
    private static final java.util.concurrent.ExecutorService RELEASE_EXECUTOR=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Tripoder-ProviderRelease");t.setDaemon(true);return t;});
    private void releaseIfIdle(){
        synchronized(this){if(!closed||uses!=0||released)return;released=true;}
        client.close();
        if(thin)return;
        Bundle req=request();
        RELEASE_EXECUTOR.execute(()->{
            try{engine.brokerCall(RuntimeWire.PROVIDER_RELEASE,req);}
            catch(Throwable e){android.util.Log.w("Tripoder","PROVIDER_RELEASE failed",e);}
        });
    }
    @Override public void close(){synchronized(this){closed=true;}releaseIfIdle();}
}
