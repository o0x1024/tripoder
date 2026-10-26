package dev.tripoder.closurefixture;

import android.content.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Same-file identity across independently requested device storage contexts. */
final class DeviceStorageChecks {
    static void run(Context owner)throws Exception{
        String name="owned-device-storage",round=java.util.UUID.randomUUID().toString();Context first=owner.createDeviceProtectedStorageContext(),second=owner.createDeviceProtectedStorageContext();
        SharedPreferences one=first.getSharedPreferences(name,Context.MODE_PRIVATE),two=second.getSharedPreferences(name,Context.MODE_PRIVATE);
        require(first.isDeviceProtectedStorage()&&second.isDeviceProtectedStorage()&&one==two,"DEVICE_CONTEXTS_SHARE_ONE_FILE_VIEW");
        Context configured=first.createConfigurationContext(new android.content.res.Configuration()),attributed=first.createAttributionContext(null);
        require(configured.isDeviceProtectedStorage()&&attributed.isDeviceProtectedStorage()&&configured.getSharedPreferences(name,0)==one&&attributed.getSharedPreferences(name,0)==one,"DERIVED_CONTEXTS_KEEP_STORAGE_IDENTITY");
        var credential=owner.getSharedPreferences(name,Context.MODE_PRIVATE);
        require(credential.edit().putString("scope","credential").commit()&&one.edit().putString("scope","device").putString("round",round).commit(),"REAL_DISTINCT_VIEW_COMMITS");
        require(credential!=one&&"credential".equals(credential.getString("scope",null))&&"device".equals(two.getString("scope",null)),"CREDENTIAL_AND_DEVICE_FILE_VIEWS_ARE_DISTINCT");
        CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1),done=new CountDownLatch(8);AtomicReference<Throwable> error=new AtomicReference<>();
        for(int i=0;i<8;i++){final int index=i;new Thread(()->{
            try{
                ready.countDown();start.await();SharedPreferences preferences=owner.createDeviceProtectedStorageContext().getSharedPreferences(name,0);
                require(preferences==one&&preferences.edit().putString("entry-"+index,round+":"+index).commit(),"CONCURRENT_SAME_FILE_REAL_COMMIT");
            }catch(Throwable failure){error.compareAndSet(null,failure);}finally{done.countDown();}
        },"owned-device-preference-"+i).start();}
        require(ready.await(2,TimeUnit.SECONDS),"REAL_CONCURRENT_READERS_READY");start.countDown();require(done.await(8,TimeUnit.SECONDS),"REAL_CONCURRENT_COMMITS_FINISH");
        if(error.get()!=null)throw new AssertionError("CONCURRENT_DEVICE_PREFERENCES_FAILED",error.get());
        for(int i=0;i<8;i++)require((round+":"+i).equals(one.getString("entry-"+i,null)),"CONCURRENT_KEYS_RETAINED");
    }
    private static void require(boolean value,String name){if(!value)throw new AssertionError(name);}
    private DeviceStorageChecks(){}
}
