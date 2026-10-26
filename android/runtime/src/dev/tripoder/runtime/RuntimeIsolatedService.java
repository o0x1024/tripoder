package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** Android creates the real isolated process/token; onBind receives the per-lease capability. */
public final class RuntimeIsolatedService extends Service {
    static final String AUTHORITY="tripoder.isolatedAuthority";
    private IBinder authority;private String generation,lease;private int authorityPid,ownerUid;private RuntimeIsolatedApplication guest;private RuntimeIsolatedNativeHost nativeHost;private Bundle bootstrapped;
    private final IBinder control=new RuntimeWire.Endpoint(){
        @Override protected Bundle handle(int code,Bundle request,int pid,int uid){
            if(authority==null||pid!=authorityPid||uid!=ownerUid||!java.util.Objects.equals(generation,request.getString("generation")))throw new SecurityException("ISOLATED_CONTROL_CALLER_MISMATCH");
            if(code!=RuntimeWire.STOP)throw new SecurityException("ISOLATED_CONTROL_OPERATION_DENIED");terminate();return new Bundle();
        }
    };
    private static void terminate(){new Thread(()->{SystemClock.sleep(100);android.os.Process.killProcess(android.os.Process.myPid());},"isolated-terminate").start();}
    private void event(String name)throws Exception{Bundle request=new Bundle();request.putString("generation",generation);request.putString("lease",lease);request.putString("event",name);RuntimeWire.call(authority,RuntimeWire.ISOLATED_EVENT,request);}
    private void identity(Intent input){
        if(input==null||input.getExtras()==null||authority==null||!authority.equals(input.getExtras().getBinder(AUTHORITY))||!generation.equals(input.getStringExtra("tripoder.generation"))||!lease.equals(input.getStringExtra("tripoder.serviceLease")))throw new SecurityException("ISOLATED_BIND_INSTANCE_MISMATCH");
    }
    @Override public IBinder onBind(Intent intent){
        Bundle pending=null;
        try{
            if(!android.os.Process.isIsolated())throw new SecurityException("REAL_ISOLATED_PROCESS_REQUIRED");
            if(guest==null&&nativeHost==null){
                if(authority!=null)throw new SecurityException("ISOLATED_BOOTSTRAP_ALREADY_ATTEMPTED");
                if(intent==null||intent.getExtras()==null)throw new SecurityException("ISOLATED_BIND_CAPABILITY_REQUIRED");authority=intent.getExtras().getBinder(AUTHORITY);if(authority==null)throw new SecurityException("ISOLATED_BIND_CAPABILITY_REQUIRED");
                generation=intent.getStringExtra("tripoder.generation");lease=intent.getStringExtra("tripoder.serviceLease");Bundle request=new Bundle();request.putString("generation",generation);request.putString("lease",lease);request.putBinder("peer",control);
                Bundle bootstrap=RuntimeWire.call(authority,RuntimeWire.ISOLATED_BOOTSTRAP,request);pending=bootstrap;authorityPid=bootstrap.getInt("authorityPid");ownerUid=bootstrap.getInt("ownerUid");
                Bundle registration=new Bundle();registration.putString("generation",generation);registration.putString("lease",lease);
                if(RuntimeWire.call(authority,RuntimeWire.ISOLATED_REGISTER,registration).getBoolean("closing"))throw new IllegalStateException("ISOLATED_PROCESS_CLOSING");
                authority.linkToDeath(()->android.os.Process.killProcess(android.os.Process.myPid()),0);
                if(bootstrap.containsKey("nativeService")){bootstrapped=bootstrap;var info=bootstrap.getParcelable("info",android.content.pm.ApplicationInfo.class);if(info==null)throw new SecurityException("ISOLATED_INFO_REQUIRED");nativeHost=new RuntimeIsolatedNativeHost(bootstrap,info.packageName,RuntimeWire.required(bootstrap,"component"));}
                else guest=new RuntimeIsolatedApplication(this,bootstrap,intent);
                event("onCreate");
            }
            identity(intent);
            IBinder result;if(nativeHost!=null)result=nativeHost.onBind(intent);else{guest.check();result=guest.service.onBind(guest.intent(intent));}
            event("onBind");return result;
        }catch(Exception error){if(pending!=null)RuntimeIsolatedCode.closeDescriptors(pending);android.util.Log.e("TripoderIsolated","ISOLATED_SERVICE_BOOTSTRAP_FAILED",error);terminate();return null;}
    }
    @Override public boolean onUnbind(Intent intent){try{identity(intent);boolean result=nativeHost!=null?nativeHost.onUnbind(intent):guest.service.onUnbind(guest.intent(intent));event("onUnbind");return result;}catch(Exception error){throw new IllegalStateException("ISOLATED_UNBIND_FAILED",error);}}
    @Override public void onRebind(Intent intent){try{identity(intent);if(nativeHost!=null)nativeHost.onRebind(intent);else guest.service.onRebind(guest.intent(intent));event("onRebind");}catch(Exception error){throw new IllegalStateException("ISOLATED_REBIND_FAILED",error);}}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(nativeHost!=null)try{nativeHost.onTrimMemory(level);}catch(RuntimeException error){android.util.Log.e("TripoderIsolated","NATIVE_TRIM_FAILED",error);}}
    @Override public void onDestroy(){try{if(guest!=null){guest.service.onDestroy();event("onDestroy");}else if(nativeHost!=null){nativeHost.onDestroy();event("onDestroy");}}catch(Exception error){android.util.Log.e("TripoderIsolated","DESTROY_UNCONFIRMED",error);}finally{if(guest!=null)guest.close();if(bootstrapped!=null)RuntimeIsolatedCode.closeDescriptors(bootstrapped);super.onDestroy();terminate();}}
}
