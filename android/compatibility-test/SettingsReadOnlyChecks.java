package dev.tripoder.runtime;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** A recording local backend, never the real Settings provider or a global SDK cache. */
final class SettingsReadOnlyChecks {
    private final BiConsumer<String,Boolean> check;
    SettingsReadOnlyChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private interface Action {void run()throws Exception;}
    private void denied(String label,Action action,AtomicInteger forwarded)throws Exception{
        int before=forwarded.get();boolean rejected=false;try{action.run();}catch(UnsupportedOperationException expected){rejected=true;}
        check.accept(label,rejected&&forwarded.get()==before);
    }
    void run(Context context)throws Exception{
        AtomicInteger forwarded=new AtomicInteger();
        ContentProvider backend=new ContentProvider(){
            @Override public boolean onCreate(){return true;}
            @Override public Bundle call(String authority,String method,String arg,Bundle extras){forwarded.incrementAndGet();Bundle actual=new Bundle();actual.putBoolean("owned-backend-executed",true);return actual;}
            @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){forwarded.incrementAndGet();return null;}
            @Override public String getType(Uri uri){forwarded.incrementAndGet();return "owned/type";}
            @Override public Uri insert(Uri uri,ContentValues values){forwarded.incrementAndGet();throw new AssertionError("PHYSICAL_WRITE_FORWARDED");}
            @Override public int update(Uri uri,ContentValues values,String where,String[] args){forwarded.incrementAndGet();throw new AssertionError("PHYSICAL_WRITE_FORWARDED");}
            @Override public int delete(Uri uri,String where,String[] args){forwarded.incrementAndGet();throw new AssertionError("PHYSICAL_WRITE_FORWARDED");}
        };
        var info=new android.content.pm.ProviderInfo();info.authority="settings";info.exported=false;backend.attachInfo(context,info);
        SettingsReadProvider subject=new SettingsReadProvider(ContentResolver.wrap(backend));subject.attachInfo(context,new android.content.pm.ProviderInfo(info));
        check.accept("settings-exact-read-forwards-to-owned-recording-backend",subject.call("settings","GET_system","owned",null).getBoolean("owned-backend-executed")&&forwarded.get()==1);
        check.accept("settings-config-get-and-list-are-real-exact-read-routes",subject.call("settings","GET_config","owned/key",null).getBoolean("owned-backend-executed")&&subject.call("settings","LIST_config",null,null).getBoolean("owned-backend-executed")&&forwarded.get()==3);
        for(String method:new String[]{"PUT_system","PUT_secure","PUT_global","PUT_config","SET_ALL_config","DELETE_config","RESET_system","UNDECLARED_COMMAND"})denied("settings-rejects-"+method+"-without-forwarding",()->subject.call("settings",method,"owned",new Bundle()),forwarded);
        Uri uri=Uri.parse("content://settings/system/owned");ContentValues value=new ContentValues();
        denied("settings-insert-never-forwards",()->subject.insert(uri,value),forwarded);
        denied("settings-update-never-forwards",()->subject.update(uri,value,null,null),forwarded);
        denied("settings-delete-never-forwards",()->subject.delete(uri,null,null),forwarded);
        denied("settings-bulk-insert-even-empty-is-explicitly-unsupported",()->subject.bulkInsert(uri,new ContentValues[0]),forwarded);
        denied("settings-legacy-batch-even-empty-never-forwards",()->subject.applyBatch(new ArrayList<>()),forwarded);
        denied("settings-authority-batch-even-empty-never-forwards",()->subject.applyBatch("settings",new ArrayList<>()),forwarded);
        Bundle wrongUser=new Bundle();wrongUser.putInt("_user",android.os.Process.myUserHandle().hashCode()+1);
        denied("settings-read-cannot-borrow-another-user",()->subject.call("settings","GET_system","owned",wrongUser),forwarded);
        Bundle wrongType=new Bundle();wrongType.putString("_user","0");
        denied("settings-user-type-cannot-default-to-current-user",()->subject.call("settings","GET_system","owned",wrongType),forwarded);
        check.accept("settings-all-denials-preserve-backend-read-counter",forwarded.get()==3);
    }
}
