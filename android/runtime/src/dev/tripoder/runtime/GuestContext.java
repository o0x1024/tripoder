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
    private final boolean deviceProtected;
    /**
     * The application-context plumbing a real application's base context exposes.
     *
     * <p>On a device, {@code Application.getBaseContext()} is a platform {@code ContextImpl} holding
     * {@code mOuterContext} (the owning application) and {@code mPackageInfo} (its {@code LoadedApk}). Hot-fix and
     * plugin runtimes read and rewrite both during {@code attach} and {@code onCreate}; a wrapper that hides them
     * makes that contract unreachable. These fields name the same objects the guest's real {@code ContextImpl}
     * holds: the owning application and the guest's own {@code LoadedApk}.
     */
    private android.content.Context mOuterContext;
    private Object mPackageInfo;
    GuestContext(Context base,RuntimeEngine engine,Resources resources,Context platform,ClassLoader resourceOnlyLoader){this(base,engine,resources,platform,resourceOnlyLoader,engine.storage,false);}
    private GuestContext(Context base,RuntimeEngine engine,Resources resources,Context platform,ClassLoader resourceOnlyLoader,RuntimeStorage storage,boolean deviceProtected){super(base,storage);this.deviceProtected=deviceProtected;this.engine=engine;this.guestResources=resources;this.platform=platform;this.resourceOnlyLoader=resourceOnlyLoader;this.guestPackages=engine.packages.manager(this,resources);
        this.mPackageInfo=dev.tripoder.selfhook.ApplicationAdapter.packageInfo();}
    /** Mirror of {@code ContextImpl.setOuterContext}: runs before the guest's {@code Application.attach}. */
    void attachedApplication(android.app.Application application){mOuterContext=application;}
    /** Mirrors ContextImpl: the deprecated int BIND_EXTERNAL_SERVICE bit is the long-flag form for BindServiceFlags. */
    private static long serviceFlags(int flags){long value=Integer.toUnsignedLong(flags);if((flags&BIND_EXTERNAL_SERVICE)!=0)value=(value&~Integer.toUnsignedLong(BIND_EXTERNAL_SERVICE))|BIND_EXTERNAL_SERVICE_LONG;return value;}
    @Override public String getPackageName(){return engine.packageName;}
    /**
     * Platform services validate the operation package against the real UID (AppOps, storage volumes, widgets). The
     * guest's package is not installed for this UID, so presenting it there is rejected even though the guest is
     * behaving exactly as it would on a device. Two identities are therefore kept separate: {@link #getPackageName()}
     * is what the guest sees, while the operation package is the installed package that owns this UID. Services that
     * attribute work to the caller then accept it, and the runtime translates anything guest-specific itself.
     */
    @Override public String getOpPackageName(){return engine.host.getPackageName();}
    @Override public String getAttributionTag(){return null;}
    @Override public ClassLoader getClassLoader(){return resourceOnlyLoader==null?engine.loader:resourceOnlyLoader;}
    @Override public Resources getResources(){return guestResources;}
    @Override public AssetManager getAssets(){return guestResources.getAssets();}
    @Override public Context getApplicationContext(){return resourceOnlyLoader!=null?null:engine.applicationContext();}
    @Override public ApplicationInfo getApplicationInfo(){return engine.applicationInfo;}
    @Override public String getPackageCodePath(){return getApplicationInfo().sourceDir;}
    @Override public String getPackageResourcePath(){return getApplicationInfo().publicSourceDir;}
    @Override public PackageManager getPackageManager(){return guestPackages;}
    // A guest is granted only what its own manifest asks for (the host holds the permission on its behalf); an undeclared permission is simply not held.
    private int guestPermission(String permission,int result){return permission!=null&&engine.requestedPermissions.contains(permission)?result:PackageManager.PERMISSION_DENIED;}
    @Override public int checkSelfPermission(String permission){return guestPermission(permission,super.checkSelfPermission(permission));}
    @Override public int checkPermission(String permission,int pid,int uid){int result=super.checkPermission(permission,pid,uid);return pid==android.os.Process.myPid()&&uid==android.os.Process.myUid()?guestPermission(permission,result):result;}
    @Override public int checkCallingOrSelfPermission(String permission){int result=super.checkCallingOrSelfPermission(permission);return android.os.Binder.getCallingPid()==android.os.Process.myPid()?guestPermission(permission,result):result;}
    @Override public Context createPackageContext(String packageName,int flags){return engine.packageContexts.create(packageName,flags);}
    // Declared splits share the guest's merged class loader and resources; an undeclared split name throws NameNotFoundException, matching Android.
    @Override public Context createContextForSplit(String splitName)throws PackageManager.NameNotFoundException{
        if(splitName!=null&&engine.info.splitNames!=null)for(String declared:engine.info.splitNames)if(splitName.equals(declared))return this;
        throw new PackageManager.NameNotFoundException(String.valueOf(splitName));
    }
    @Override public boolean isDeviceProtectedStorage(){return deviceProtected;}
    @Override public Context createDeviceProtectedStorageContext(){try{return new GuestContext(getBaseContext(),engine,guestResources,platform,resourceOnlyLoader,engine.storage.view("device-protected"),true);}catch(Exception error){throw new IllegalStateException("DEVICE_PROTECTED_STORAGE_FAILED",error);}}
    @Override public Context createConfigurationContext(Configuration config){Configuration merged=new Configuration(guestResources.getConfiguration());merged.updateFrom(config);Context derived=super.createConfigurationContext(config);return new GuestContext(derived,engine,new Resources(guestResources.getAssets(),guestResources.getDisplayMetrics(),merged),derived,resourceOnlyLoader);}
    // Derived contexts keep the guest's identity, storage and class loader and take only what differs (display, attribution, device) from the platform's own derivation.
    @Override public Context createDisplayContext(Display display){return windowDerived(platform.createDisplayContext(display));}
    private Context windowDerived(Context derived){Resources platformResources=derived.getResources();return new GuestContext(derived,engine,new Resources(guestResources.getAssets(),platformResources.getDisplayMetrics(),new Configuration(platformResources.getConfiguration())),derived,resourceOnlyLoader);}
    @Override public Context createWindowContext(int type,Bundle options){return windowDerived(platform.createWindowContext(type,options));}
    @Override public Context createWindowContext(Display display,int type,Bundle options){return windowDerived(platform.createWindowContext(display,type,options));}
    @Override public Context createAttributionContext(String attributionTag){return windowDerived(platform.createAttributionContext(attributionTag));}
    @Override public Context createContext(ContextParams params){return windowDerived(platform.createContext(params));}
    @Override public Context createDeviceContext(int deviceId){return windowDerived(platform.createDeviceContext(deviceId));}
    @Override public Object getSystemService(String name){
        if(engine.mode==RuntimeMode.THIN&&RuntimeThinPolicy.platformService(name))return engine.host.getSystemService(name);
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
        if(ALARM_SERVICE.equals(name))return engine.alarms.facade();
        if(SHORTCUT_SERVICE.equals(name))return engine.shortcuts.facade();
        if("webviewupdate".equals(name))return engine.webView.updateService();
        if(LAYOUT_INFLATER_SERVICE.equals(name))return ((LayoutInflater)platform.getSystemService(name)).cloneInContext(this);
        return platform.getSystemService(name);
    }
    // Routed by the activity-start hook: guest components through the ledger, everything else to the platform.
    @Override public void startActivity(Intent intent){startActivity(intent,null);}
    @Override public void startActivity(Intent intent,Bundle options){super.startActivity(intent,options);}
    @Override public void startActivities(Intent[] intents){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startActivities(Intent[] intents,Bundle options){throw new UnsupportedOperationException("CONTEXT_START_ACTIVITY_UNSUPPORTED");}
    @Override public void startIntentSender(IntentSender sender,Intent fillIn,int flagsMask,int flagsValues,int extraFlags){throw new UnsupportedOperationException("INTENT_SENDER_UNSUPPORTED");}
    @Override public void startIntentSender(IntentSender sender,Intent fillIn,int flagsMask,int flagsValues,int extraFlags,Bundle options){throw new UnsupportedOperationException("INTENT_SENDER_UNSUPPORTED");}
    @Override public ComponentName startService(Intent intent){try{return engine.services.start(platform,intent);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_START_FAILED",e);}}
    // The promise of a foreground start is a notification shown through Service.startForeground; the start itself is an ordinary service start.
    @Override public ComponentName startForegroundService(Intent intent){return startService(intent);}
    @Override public boolean stopService(Intent intent){try{return engine.services.stop(platform,intent);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_STOP_FAILED",e);}}
    @Override public boolean bindService(Intent intent,ServiceConnection conn,int flags){if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,conn,serviceFlags(flags),null,null);try{return engine.services.bind(platform,intent,conn,flags,null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_BIND_FAILED",e);}}
    @Override public boolean bindService(Intent intent,int flags,Executor executor,ServiceConnection conn){if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,conn,serviceFlags(flags),executor,null);try{return engine.services.bind(platform,intent,conn,flags,executor);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_BIND_FAILED",e);}}
    @Override public boolean bindService(Intent intent,ServiceConnection conn,BindServiceFlags flags){if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,conn,RuntimeBridge.bindServiceFlags(flags),null,null);return bindService(intent,conn,Math.toIntExact(RuntimeBridge.bindServiceFlags(flags)));}
    @Override public boolean bindService(Intent intent,BindServiceFlags flags,Executor executor,ServiceConnection conn){if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,conn,RuntimeBridge.bindServiceFlags(flags),executor,null);return bindService(intent,Math.toIntExact(RuntimeBridge.bindServiceFlags(flags)),executor,conn);}
    @Override public boolean bindIsolatedService(Intent intent,int flags,String instanceName,Executor executor,ServiceConnection conn){return engine.webView.services.bind(intent,conn,serviceFlags(flags),executor,java.util.Objects.requireNonNull(instanceName));}
    @Override public boolean bindIsolatedService(Intent intent,BindServiceFlags flags,String instanceName,Executor executor,ServiceConnection conn){return engine.webView.services.bind(intent,conn,RuntimeBridge.bindServiceFlags(flags),executor,java.util.Objects.requireNonNull(instanceName));}
    // Binding as another user needs INTERACT_ACROSS_USERS, which no ordinary app holds; binding as the current user is the ordinary bind.
    @Override public boolean bindServiceAsUser(Intent intent,ServiceConnection conn,int flags,UserHandle user){requireCurrentUser(user);return bindService(intent,conn,flags);}
    @Override public boolean bindServiceAsUser(Intent intent,ServiceConnection conn,BindServiceFlags flags,UserHandle user){requireCurrentUser(user);return bindService(intent,conn,flags);}
    private static void requireCurrentUser(UserHandle user){if(user==null||!user.equals(android.os.Process.myUserHandle()))throw new SecurityException("Requires INTERACT_ACROSS_USERS permission to bind as another user");}
    @Override public void rebindService(ServiceConnection conn,BindServiceFlags flags){engine.webView.services.rebind(conn,flags);}
    @Override public void updateServiceBindings(java.util.Collection<UpdateBindingParams> updates){throw new UnsupportedOperationException("SERVICE_UNSUPPORTED");}
    @Override public void updateServiceGroup(ServiceConnection conn,int group,int importance){engine.webView.services.updateGroup(conn,group,importance);}
    @Override public void unbindService(ServiceConnection conn){if(engine.webView.services.owns(conn)){engine.webView.services.unbind(conn);return;}try{engine.services.unbind(conn);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("SERVICE_UNBIND_FAILED",e);}}
    @Override public boolean startInstrumentation(ComponentName component,String profileFile,Bundle arguments){throw new UnsupportedOperationException("INSTRUMENTATION_UNSUPPORTED");}
    @Override public void sendBroadcast(Intent intent){engine.receivers.send(intent,null,null,false,null,null,0,null,null);}
    @Override public void sendBroadcast(Intent intent,String permission){engine.receivers.send(intent,permission,null,false,null,null,0,null,null);}
    @Override public void sendBroadcast(Intent intent,String permission,Bundle options){engine.receivers.send(intent,permission,options,false,null,null,0,null,null);}
    // The authority checks one receiver permission; zero or one is exact, a conjunction of several is not modelled.
    @Override public void sendBroadcastWithMultiplePermissions(Intent intent,String[] permissions){if(permissions==null||permissions.length==0)sendBroadcast(intent);else if(permissions.length==1)sendBroadcast(intent,permissions[0]);else throw new UnsupportedOperationException("BROADCAST_MULTIPLE_PERMISSIONS_UNSUPPORTED");}
    @Override public void sendOrderedBroadcast(Intent intent,String permission){engine.receivers.send(intent,permission,null,true,null,null,0,null,null);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,Bundle options){engine.receivers.send(intent,permission,options,true,null,null,0,null,null);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){engine.receivers.send(intent,permission,null,true,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendOrderedBroadcast(Intent intent,String permission,Bundle options,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){engine.receivers.send(intent,permission,options,true,receiver,scheduler,initialCode,initialData,initialExtras);}
    // An app-op restriction on receivers is an additional filter this runtime does not apply; without one the call is the ordinary ordered send.
    @Override public void sendOrderedBroadcast(Intent intent,String permission,String appOp,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){if(appOp!=null)throw new UnsupportedOperationException("BROADCAST_APPOP_UNSUPPORTED");sendOrderedBroadcast(intent,permission,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendOrderedBroadcast(Intent intent,int initialCode,String permission,String appOp,BroadcastReceiver receiver,Handler scheduler,String initialData,Bundle initialExtras,Bundle options){if(appOp!=null)throw new UnsupportedOperationException("BROADCAST_APPOP_UNSUPPORTED");sendOrderedBroadcast(intent,permission,options,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendBroadcastAsUser(Intent intent,UserHandle user){requireCurrentUser(user);sendBroadcast(intent);}
    @Override public void sendBroadcastAsUser(Intent intent,UserHandle user,String permission){requireCurrentUser(user);sendBroadcast(intent,permission);}
    @Override public void sendOrderedBroadcastAsUser(Intent intent,UserHandle user,String permission,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){requireCurrentUser(user);sendOrderedBroadcast(intent,permission,receiver,scheduler,initialCode,initialData,initialExtras);}
    @Override public void sendStickyBroadcast(Intent intent){engine.receivers.sendSticky(intent,null,null);}
    @Override public void sendStickyBroadcast(Intent intent,Bundle options){engine.receivers.sendSticky(intent,null,options);}
    @Override public void sendStickyOrderedBroadcast(Intent intent,BroadcastReceiver receiver,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){throw new UnsupportedOperationException("BROADCAST_UNSUPPORTED");}
    @Override public void removeStickyBroadcast(Intent intent){engine.receivers.removeSticky(intent);}
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
