package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.view.Display;
import android.view.LayoutInflater;
import java.io.*;
import java.util.concurrent.Executor;

/**
 * P0 supports guest identity/resources, private Java files and the UI services
 * listed in getSystemService. Component routing belongs to the Activity adapter;
 * Context component, cross-package and derived-context entry points reject here.
 * This wrapper is not an isolation boundary for arbitrary same-UID code.
 */
public final class GuestContext extends GuestStorageContext {
    final RuntimeEngine engine;
    private final Resources guestResources;
    private final PackageManager guestPackages;
    private final Context platform;
    private final ClassLoader resourceOnlyLoader;
    public GuestContext(Context base,RuntimeEngine engine){this(base,engine,engine.resources);}
    static GuestContext application(Context logical,RuntimeEngine engine){return new GuestContext(logical,engine,engine.resources,engine.host);}
    private GuestContext(Context base,RuntimeEngine engine,Resources resources){this(base,engine,resources,base);}
    private GuestContext(Context base,RuntimeEngine engine,Resources resources,Context platform){this(base,engine,resources,platform,null);}
    GuestContext(Context base,RuntimeEngine engine,Resources resources,Context platform,ClassLoader resourceOnlyLoader){super(base,engine.storage);this.engine=engine;this.guestResources=resources;this.platform=platform;this.resourceOnlyLoader=resourceOnlyLoader;this.guestPackages=engine.packages.manager(this,resources);}
    @Override public String getPackageName(){return engine.packageName;}
    @Override public ClassLoader getClassLoader(){return resourceOnlyLoader==null?engine.loader:resourceOnlyLoader;}
    @Override public Resources getResources(){return guestResources;}
    @Override public AssetManager getAssets(){return guestResources.getAssets();}
    @Override public Context getApplicationContext(){return resourceOnlyLoader!=null?null:engine.application==null?this:engine.application;}
    @Override public ApplicationInfo getApplicationInfo(){return GuestPackageManager.copyApplicationInfo(engine.info,true);}
    @Override public String getPackageCodePath(){return engine.info.sourceDir;}
    @Override public String getPackageResourcePath(){return engine.info.publicSourceDir;}
    @Override public PackageManager getPackageManager(){return guestPackages;}
    @Override public Context createPackageContext(String packageName,int flags){return engine.packageContexts.create(packageName,flags);}
    @Override public Context createContextForSplit(String splitName){throw new UnsupportedOperationException("SPLIT_CONTEXT_UNSUPPORTED");}
    @Override public Context createDeviceProtectedStorageContext(){throw new UnsupportedOperationException("DEVICE_PROTECTED_STORAGE_UNSUPPORTED");}
    @Override public Context createConfigurationContext(Configuration config){Configuration merged=new Configuration(guestResources.getConfiguration());merged.updateFrom(config);Context derived=super.createConfigurationContext(config);return new GuestContext(derived,engine,new Resources(guestResources.getAssets(),guestResources.getDisplayMetrics(),merged),derived,resourceOnlyLoader);}
    @Override public Context createDisplayContext(Display display){throw new UnsupportedOperationException("DISPLAY_CONTEXT_UNSUPPORTED");}
    @Override public Context createWindowContext(int type,Bundle options){throw new UnsupportedOperationException("WINDOW_CONTEXT_UNSUPPORTED");}
    @Override public Context createWindowContext(Display display,int type,Bundle options){throw new UnsupportedOperationException("WINDOW_CONTEXT_UNSUPPORTED");}
    @Override public Context createAttributionContext(String attributionTag){throw new UnsupportedOperationException("ATTRIBUTION_CONTEXT_UNSUPPORTED");}
    @Override public Context createContext(ContextParams params){throw new UnsupportedOperationException("DERIVED_CONTEXT_UNSUPPORTED");}
    @Override public Context createDeviceContext(int deviceId){throw new UnsupportedOperationException("DEVICE_CONTEXT_UNSUPPORTED");}
    @Override public Object getSystemService(String name){
        if(ACTIVITY_SERVICE.equals(name))return engine.activityManager.facade();
        if(CONNECTIVITY_SERVICE.equals(name))return engine.connectivity.facade();
        if(TELEPHONY_SERVICE.equals(name))return engine.telephony.facade();
        if(AUDIO_SERVICE.equals(name))return engine.audio.facade();
        if(BATTERY_SERVICE.equals(name))return engine.battery.facade();
        if(POWER_SERVICE.equals(name))return engine.power.facade();
        if(NETWORK_STATS_SERVICE.equals(name))return engine.networkStats.facade();
        if(WIFI_P2P_SERVICE.equals(name))return engine.wifiP2p.facade();
        if(NOTIFICATION_SERVICE.equals(name))return engine.notifications.facade();
        if(JOB_SCHEDULER_SERVICE.equals(name))return engine.jobScheduler.facade();
        if(SHORTCUT_SERVICE.equals(name))return engine.shortcuts.facade();
        if(LAYOUT_INFLATER_SERVICE.equals(name))return ((LayoutInflater)platform.getSystemService(name)).cloneInContext(this);
        // This pinned Xiaomi baseline invokes its parental-control service in Activity.onResume.
        // Preserve platform policy enforcement; this is an explicit host-backed UI capability.
        if("HyperPackageManager".equals(name)||UI_MODE_SERVICE.equals(name)||WINDOW_SERVICE.equals(name)||INPUT_METHOD_SERVICE.equals(name)||ACCESSIBILITY_SERVICE.equals(name)||DISPLAY_SERVICE.equals(name)
            || name.equals(super.getSystemServiceName(android.view.autofill.AutofillManager.class))
            || name.equals(super.getSystemServiceName(android.view.contentcapture.ContentCaptureManager.class)))return platform.getSystemService(name);
        throw new UnsupportedOperationException("SYSTEM_SERVICE_UNSUPPORTED:"+name);
    }
    @Override public void startActivity(Intent intent){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startActivity(Intent intent,Bundle options){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startActivities(Intent[] intents){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startActivities(Intent[] intents,Bundle options){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startIntentSender(IntentSender sender,Intent fillIn,int flagsMask,int flagsValues,int extraFlags){throw new UnsupportedOperationException("INTENT_SENDER_UNSUPPORTED");}
    @Override public void startIntentSender(IntentSender sender,Intent fillIn,int flagsMask,int flagsValues,int extraFlags,Bundle options){throw new UnsupportedOperationException("INTENT_SENDER_UNSUPPORTED");}
    @Override public ComponentName startService(Intent intent){try{return engine.services.start(platform,intent);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_START_FAILED",e);}}
    @Override public ComponentName startForegroundService(Intent intent){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public boolean stopService(Intent intent){try{return engine.services.stop(platform,intent);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_STOP_FAILED",e);}}
    @Override public boolean bindService(Intent intent,ServiceConnection conn,int flags){try{return engine.services.bind(platform,intent,conn,flags,null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_BIND_FAILED",e);}}
    @Override public boolean bindService(Intent intent,int flags,Executor executor,ServiceConnection conn){try{return engine.services.bind(platform,intent,conn,flags,executor);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_BIND_FAILED",e);}}
    @Override public boolean bindService(Intent intent,ServiceConnection conn,BindServiceFlags flags){return bindService(intent,conn,Math.toIntExact(RuntimeBridge.bindServiceFlags(flags)));}
    @Override public boolean bindService(Intent intent,BindServiceFlags flags,Executor executor,ServiceConnection conn){return bindService(intent,Math.toIntExact(RuntimeBridge.bindServiceFlags(flags)),executor,conn);}
    @Override public boolean bindIsolatedService(Intent intent,int flags,String instanceName,Executor executor,ServiceConnection conn){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public boolean bindIsolatedService(Intent intent,BindServiceFlags flags,String instanceName,Executor executor,ServiceConnection conn){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public boolean bindServiceAsUser(Intent intent,ServiceConnection conn,int flags,UserHandle user){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public boolean bindServiceAsUser(Intent intent,ServiceConnection conn,BindServiceFlags flags,UserHandle user){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public void rebindService(ServiceConnection conn,BindServiceFlags flags){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public void updateServiceBindings(java.util.Collection<UpdateBindingParams> updates){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public void updateServiceGroup(ServiceConnection conn,int group,int importance){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public void unbindService(ServiceConnection conn){try{engine.services.unbind(conn);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_UNBIND_FAILED",e);}}
    @Override public boolean startInstrumentation(ComponentName component,String profileFile,Bundle arguments){throw new UnsupportedOperationException("INSTRUMENTATION_UNSUPPORTED");}
    @Override public void sendBroadcast(Intent intent){engine.receivers.send(intent,null,null,false,null,null,0,null,null);}
    @Override public void sendBroadcast(Intent intent,String permission){engine.receivers.send(intent,permission,null,false,null,null,0,null,null);}
    @Override public void sendBroadcast(Intent intent,String permission,Bundle options){engine.receivers.send(intent,permission,options,false,null,null,0,null,null);}
    @Override public void sendBroadcastWithMultiplePermissions(Intent intent,String[] permissions){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendOrderedBroadcast(Intent intent,String permission){engine.receivers.send(intent,permission,null,true,null,null,0,null,null);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,Bundle options){engine.receivers.send(intent,permission,options,true,null,null,0,null,null);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){engine.receivers.send(intent,permission,null,true,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,Bundle options,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){engine.receivers.send(intent,permission,options,true,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,String appOp,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendOrderedBroadcast(Intent intent,int initialCode,String permission,String appOp,BroadcastReceiver receiver,Handler scheduler,String initialData,Bundle initialExtras,Bundle options){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendBroadcastAsUser(Intent intent,UserHandle user){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendBroadcastAsUser(Intent intent,UserHandle user,String permission){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendOrderedBroadcastAsUser(Intent intent,UserHandle user,String permission,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendStickyBroadcast(Intent intent){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendStickyBroadcast(Intent intent,Bundle options){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendStickyOrderedBroadcast(Intent intent,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void removeStickyBroadcast(Intent intent){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendStickyBroadcastAsUser(Intent intent,UserHandle user){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void sendStickyOrderedBroadcastAsUser(Intent intent,UserHandle user,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void removeStickyBroadcastAsUser(Intent intent,UserHandle user){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter){return engine.receivers.register(receiver,filter,null,null,0);}
    @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags){return engine.receivers.register(receiver,filter,null,null,flags);}
    @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler){return engine.receivers.register(receiver,filter,permission,scheduler,0);}
    @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags){return engine.receivers.register(receiver,filter,permission,scheduler,flags);}
    @Override public void unregisterReceiver(BroadcastReceiver receiver){engine.receivers.unregister(receiver);}
    @Override public ContentResolver getContentResolver(){return engine.settingsResolver();}
    private static UnsupportedOperationException uriGrant(){return new UnsupportedOperationException("URI_GRANT_NOT_IMPLEMENTED");}
    @Override public void grantUriPermission(String target,android.net.Uri uri,int flags){throw uriGrant();}
    @Override public void revokeUriPermission(android.net.Uri uri,int flags){throw uriGrant();}
    @Override public void revokeUriPermission(String target,android.net.Uri uri,int flags){throw uriGrant();}
    @Override public int checkUriPermission(android.net.Uri uri,int pid,int uid,int flags){throw uriGrant();}
    @Override public int checkUriPermission(android.net.Uri uri,String read,String write,int pid,int uid,int flags){throw uriGrant();}
    @Override public int[] checkUriPermissions(java.util.List<android.net.Uri> uris,int pid,int uid,int flags){throw uriGrant();}
    @Override public int checkCallingUriPermission(android.net.Uri uri,int flags){throw uriGrant();}
    @Override public int[] checkCallingUriPermissions(java.util.List<android.net.Uri> uris,int flags){throw uriGrant();}
    @Override public int checkCallingOrSelfUriPermission(android.net.Uri uri,int flags){throw uriGrant();}
    @Override public int[] checkCallingOrSelfUriPermissions(java.util.List<android.net.Uri> uris,int flags){throw uriGrant();}
    @Override public int checkContentUriPermissionFull(android.net.Uri uri,int pid,int uid,int flags){throw uriGrant();}
    @Override public void enforceUriPermission(android.net.Uri uri,int pid,int uid,int flags,String message){throw uriGrant();}
    @Override public void enforceUriPermission(android.net.Uri uri,String read,String write,int pid,int uid,int flags,String message){throw uriGrant();}
    @Override public void enforceCallingUriPermission(android.net.Uri uri,int flags,String message){throw uriGrant();}
    @Override public void enforceCallingOrSelfUriPermission(android.net.Uri uri,int flags,String message){throw uriGrant();}
}
