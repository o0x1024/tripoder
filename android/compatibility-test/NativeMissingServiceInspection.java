package dev.tripoder.hooklab;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** SDK requests for a verified absent class in this QA package only; no guest is loaded. */
final class NativeMissingServiceInspection {
    static JSONObject inspect(Context owned)throws Exception{
        if(!owned.getPackageName().startsWith("dev.tripoder.compat"))throw new SecurityException("OWNED_QA_PACKAGE_REQUIRED");
        ComponentName component=new ComponentName(owned.getPackageName(),owned.getPackageName()+".OwnedUndeclaredServiceProbe");
        boolean absent=false;
        try{owned.getPackageManager().getServiceInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);}
        catch(PackageManager.NameNotFoundException expected){absent=true;}
        if(!absent)throw new SecurityException("OWNED_ABSENT_SERVICE_REQUIRED");
        JSONObject out=new JSONObject().put("component",component.flattenToShortString()).put("nativeDeclarationAbsent",true).put("accountDataRead",false);
        try{ComponentName value=owned.startService(new Intent().setComponent(component));out.put("startReturnedComponent",value==null?JSONObject.NULL:value.flattenToShortString());}
        catch(RuntimeException error){out.put("startErrorType",error.getClass().getName());}
        AtomicInteger callbacks=new AtomicInteger();ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){callbacks.incrementAndGet();}
            public void onServiceDisconnected(ComponentName name){callbacks.incrementAndGet();}
            public void onNullBinding(ComponentName name){callbacks.incrementAndGet();}
            public void onBindingDied(ComponentName name){callbacks.incrementAndGet();}
        };
        boolean returned=false;
        try{boolean bound=owned.bindService(new Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE);returned=true;out.put("bindReturned",bound);}
        catch(RuntimeException error){out.put("bindErrorType",error.getClass().getName());}
        finally{if(returned)try{
            owned.unbindService(connection);out.put("unbindAfterReturnSucceeded",true);
            try{owned.unbindService(connection);out.put("secondUnbindReturned",true);}catch(RuntimeException error){out.put("secondUnbindErrorType",error.getClass().getName());}
        }catch(RuntimeException error){out.put("unbindAfterReturnErrorType",error.getClass().getName());}}
        return out.put("callbacksAtReturn",callbacks.get()).put("businessAccepted",false);
    }
    private NativeMissingServiceInspection(){}
}
