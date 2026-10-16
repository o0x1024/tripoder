package dev.tripoder.p2;

import android.content.*;
import android.database.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Real guest SQLite writes and public SDK boundary checks, without runtime imports. */
public final class ProviderWrites {
    private static final Uri URI=Uri.parse("content://dev.tripoder.p2.write/items");
    public interface Check {void check(String name,boolean passed,Object observed)throws Exception;}
    public static final class Store extends ContentProvider {
        private SQLiteDatabase db;private int inserts,updates,deletes,bulks,batches;
        @Override public boolean onCreate(){db=getContext().openOrCreateDatabase("provider-writes.db",Context.MODE_PRIVATE,null);db.execSQL("CREATE TABLE IF NOT EXISTS items(_id INTEGER PRIMARY KEY, text TEXT, number INTEGER, real REAL, blob BLOB, nullable TEXT)");return true;}
        @Override public String getType(Uri uri){return "vnd.android.cursor.dir/tripoder-write";}
        private String where(Bundle b){return b==null?null:b.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);}
        private String[] args(Bundle b){return b==null?null:b.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);}
        @Override public Cursor query(Uri uri,String[] columns,String where,String[] args,String sort){if(uri.getPath().equals("/lazy"))return new ProviderLazyCursor(this);return db.query("items",columns,where,args,null,null,sort);}
        @Override public Uri insert(Uri uri,ContentValues values){inserts++;long id=db.insertOrThrow("items",null,values);if(uri.getPath().equals("/throwAfterWrite"))throw new IllegalStateException("OWNED_WRITE_AFTER_EFFECT");return ContentUris.withAppendedId(uri,id);}
        @Override public Uri insert(Uri uri,ContentValues values,Bundle extras){
            if(extras!=null&&extras.containsKey("typed")){
                if(!(values.get("byte") instanceof Byte&&values.get("short") instanceof Short&&values.get("float") instanceof Float&&values.get("bool") instanceof Boolean))throw new IllegalArgumentException("TYPED_VALUES_CHANGED");
                values.remove("byte");values.remove("short");values.remove("float");values.remove("bool");
            }
            return insert(uri,values);
        }
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){updates++;return db.update("items",values,where,args);}
        @Override public int update(Uri uri,ContentValues values,Bundle extras){return update(uri,values,where(extras),args(extras));}
        @Override public int delete(Uri uri,String where,String[] args){deletes++;return db.delete("items",where,args);}
        @Override public int delete(Uri uri,Bundle extras){return delete(uri,where(extras),args(extras));}
        @Override public int bulkInsert(Uri uri,ContentValues[] values){bulks++;int count=0;db.beginTransaction();try{for(ContentValues value:values){insert(uri,value);count++;}db.setTransactionSuccessful();return count;}finally{db.endTransaction();}}
        @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)throws OperationApplicationException{batches++;db.beginTransaction();try{ContentProviderResult[] result=super.applyBatch(operations);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}
        @Override public Bundle call(String method,String arg,Bundle extras){Bundle b=new Bundle();b.putInt("inserts",inserts);b.putInt("updates",updates);b.putInt("deletes",deletes);b.putInt("bulks",bulks);b.putInt("batches",batches);b.putInt("pid",android.os.Process.myPid());b.putString("caller",getCallingPackage());b.putString("data",getContext().getDataDir().toString());b.putInt("lazyReads",ProviderLazyCursor.reads.get());b.putInt("lazyWrong",ProviderLazyCursor.wrong.get());b.putInt("lazyCloses",ProviderLazyCursor.closes.get());b.putInt("lazyRequeries",ProviderLazyCursor.requeries.get());b.putInt("lazyFills",ProviderLazyCursor.fills.get());return b;}
    }
    private static ContentValues row(long id,String text){ContentValues v=new ContentValues();v.put("_id",id);v.put("text",text);v.put("number",1234567890123L);v.put("real",1.25D);v.put("blob",new byte[]{1,2,3,4});v.putNull("nullable");return v;}
    private static boolean denied(Runnable action,String code){try{action.run();return false;}catch(RuntimeException error){return android.util.Log.getStackTraceString(error).contains(code);}}
    public static void run(Context context,Check test)throws Exception{
        ContentResolver r=context.getContentResolver();ContentValues first=row(1,"modern");first.put("byte",(byte)2);first.put("short",(short)3);first.put("float",0.5F);first.put("bool",true);Bundle typed=new Bundle();typed.putBoolean("typed",true);
        Uri inserted=r.insert(URI,first,typed);test.check("provider-modern-insert-uri-and-values",ContentUris.parseId(inserted)==1&&first.containsKey("bool"),inserted);
        try(ContentProviderClient client=r.acquireContentProviderClient(URI)){
            test.check("provider-legacy-client-insert",ContentUris.parseId(client.insert(URI,row(2,"legacy")))==2,true);
            test.check("provider-bulk-insert-count",client.bulkInsert(URI,new ContentValues[]{row(3,"bulk-a"),row(4,"bulk-b")})==2,true);
            ContentValues changed=new ContentValues();changed.put("text","changed");test.check("provider-legacy-client-update-selection",client.update(URI,changed,"_id=?",new String[]{"2"})==1,true);
        }
        try(Cursor c=r.query(URI,null,"_id=?",new String[]{"1"},null)){boolean moved=c.moveToFirst();test.check("provider-write-real-database-types",moved&&c.getLong(c.getColumnIndexOrThrow("number"))==1234567890123L&&c.getDouble(c.getColumnIndexOrThrow("real"))==1.25D&&Arrays.equals(c.getBlob(c.getColumnIndexOrThrow("blob")),new byte[]{1,2,3,4})&&c.isNull(c.getColumnIndexOrThrow("nullable")),c.getCount());}
        Bundle selection=new Bundle();selection.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"_id=?");selection.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{"3"});ContentValues changed=new ContentValues();changed.put("text","modern-update");test.check("provider-modern-update-selection",r.update(URI,changed,selection)==1,true);
        test.check("provider-modern-delete-selection",r.delete(URI,selection)==1,true);test.check("provider-legacy-delete-selection",r.delete(URI,"_id=?",new String[]{"4"})==1,true);
        try(Cursor c=r.query(URI,new String[]{"_id","text"},null,null,"_id")){c.moveToFirst();c.moveToNext();test.check("provider-exact-write-effects",c.getCount()==2&&c.getLong(0)==2&&c.getString(1).equals("changed"),c.getCount());}
        ContentValues large=row(9,"too-large");large.put("blob",new byte[65537]);test.check("provider-values-bound-before-callback",denied(()->r.insert(URI,large),"PROVIDER_VALUE_TOO_LARGE"),true);
        Bundle counts=r.call(URI,"counts",null,null);test.check("provider-write-callbacks-exactly-once",counts.getInt("inserts")==4&&counts.getInt("updates")==2&&counts.getInt("deletes")==2&&counts.getInt("bulks")==1&&counts.getInt("pid")!=android.os.Process.myPid()&&context.getPackageName().equals(counts.getString("caller")),counts.toString());
        gate(context,test);permissions(context,test);ProviderLazyCursor.run(context,test);ProviderBatches.run(context,test);ProviderFaults.run(context,test);
    }
    private static void gate(Context c,Check test)throws Exception{
        ContentResolver r=c.getContentResolver();int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION,pid=android.os.Process.myPid(),uid=android.os.Process.myUid();
        Runnable[] grants={()->c.grantUriPermission("dev.tripoder.p0.b",URI,flags),()->c.revokeUriPermission(URI,flags),()->c.revokeUriPermission("dev.tripoder.p0.b",URI,flags),()->c.checkUriPermission(URI,pid,uid,flags),()->c.checkUriPermission(URI,null,null,pid,uid,flags),()->c.checkUriPermissions(List.of(URI),pid,uid,flags),()->c.checkCallingUriPermission(URI,flags),()->c.checkCallingUriPermissions(List.of(URI),flags),()->c.checkCallingOrSelfUriPermission(URI,flags),()->c.checkCallingOrSelfUriPermissions(List.of(URI),flags),()->c.checkContentUriPermissionFull(URI,pid,uid,flags),()->c.enforceUriPermission(URI,pid,uid,flags,"test"),()->c.enforceUriPermission(URI,null,null,pid,uid,flags,"test"),()->c.enforceCallingUriPermission(URI,flags,"test"),()->c.enforceCallingOrSelfUriPermission(URI,flags,"test")};
        for(int i=0;i<grants.length;i++)test.check("provider-context-uri-grant-gate-"+i,denied(grants[i],"URI_GRANT_NOT_IMPLEMENTED"),true);
        Runnable[] persist={()->r.takePersistableUriPermission(URI,flags),()->r.releasePersistableUriPermission(URI,flags),()->r.getPersistedUriPermissions(),()->r.getOutgoingPersistedUriPermissions()};for(int i=0;i<persist.length;i++)test.check("provider-persisted-grant-gate-"+i,denied(persist[i],"URI_PERSISTABLE_GRANT_NOT_IMPLEMENTED"),true);
        ContentObserver observer=new ContentObserver(null){};test.check("provider-register-observer-gate",denied(()->r.registerContentObserver(URI,false,observer),"PROVIDER_URI_UNSUPPORTED"),true);test.check("provider-unregister-unowned-observer-gate",denied(()->r.unregisterContentObserver(observer),"PROVIDER_OBSERVER_NOT_OWNED"),true);
        r.registerContentObserver(android.provider.Settings.System.getUriFor("screen_brightness"),false,observer);r.unregisterContentObserver(observer);test.check("provider-settings-observer-explicit-ui-capability",true,true);
    }
    private static void permissions(Context context,Check test)throws Exception{
        ContentResolver r=context.getContentResolver();ContentValues v=new ContentValues();v.put("probe",1);
        Uri read=Uri.parse("content://dev.tripoder.peer.readlocked/items"),write=Uri.parse("content://dev.tripoder.peer.writelocked/items"),path=Uri.parse("content://dev.tripoder.peer.pathlocked/locked/items");
        test.check("provider-cross-package-write-does-not-require-read",r.insert(read,v)!=null,true);
        test.check("provider-cross-package-read-permission-denied",denied(()->r.query(read,null,null,null,null),"PROVIDER_PERMISSION_UNSUPPORTED"),true);
        try(Cursor c=r.query(write,null,null,null,null)){test.check("provider-cross-package-read-does-not-require-write",c.moveToFirst()&&context.getPackageName().equals(c.getString(1)),c.getCount());}
        test.check("provider-cross-package-insert-permission-denied",denied(()->r.insert(write,v),"PROVIDER_PERMISSION_UNSUPPORTED"),true);
        test.check("provider-cross-package-update-permission-denied",denied(()->r.update(write,v,null,null),"PROVIDER_PERMISSION_UNSUPPORTED"),true);
        test.check("provider-cross-package-delete-permission-denied",denied(()->r.delete(write,null,null),"PROVIDER_PERMISSION_UNSUPPORTED"),true);
        test.check("provider-cross-package-bulk-permission-denied",denied(()->r.bulkInsert(write,new ContentValues[]{v}),"PROVIDER_PERMISSION_UNSUPPORTED"),true);
        test.check("provider-cross-package-path-write-permission-denied",denied(()->r.insert(path,v),"PROVIDER_PATH_PERMISSION_UNSUPPORTED"),true);
        try(Cursor c=r.query(path,null,null,null,null)){test.check("provider-cross-package-path-read-permitted",c.moveToFirst(),c.getCount());}
        test.check("provider-cross-package-path-outside-permitted",r.insert(Uri.parse("content://dev.tripoder.peer.pathlocked/public/items"),v)!=null,true);
        test.check("provider-cross-package-private-denied",denied(()->r.getType(Uri.parse("content://dev.tripoder.peer.private/items")),"PROVIDER_NOT_EXPORTED"),true);
        try(Cursor c=r.query(Uri.parse("content://dev.tripoder.peer.public/items"),null,null,null,null)){c.moveToFirst();test.check("provider-denied-writes-never-enter-target",c.getInt(0)==2&&context.getPackageName().equals(c.getString(1))&&c.getInt(2)!=android.os.Process.myPid(),c.getInt(0));}
    }
}
