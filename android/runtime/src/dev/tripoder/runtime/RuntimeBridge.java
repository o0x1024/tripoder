package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.res.Resources;

/** Required adapter seam. A missing adapter is an error, never a host execution path. */
public final class RuntimeBridge {
    public static void failureDiagnostic(String stage,Throwable error){RuntimeStartupDiagnostics.failure(stage,error);}
    public static android.os.BatteryManager createBatteryManager(){return RuntimeSelfHookManagers.batteryManager();}
    public static boolean ownsBatteryManager(android.os.BatteryManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.battery.owns(value);}
    public static android.os.BatteryManager batteryPlatform(){return RuntimeEngine.current.battery.platform();}
    public static void batteryProperty(int id){RuntimeBattery.property(id);}
    public static int batteryIntProperty(int id){return RuntimeEngine.current.battery.intProperty(id);}
    public static android.os.PowerManager createPowerManager(Context host){return RuntimeSelfHookManagers.powerManager(host);}
    public static boolean ownsPowerManager(android.os.PowerManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.power.owns(value);}
    public static android.os.PowerManager powerPlatform(){return RuntimeEngine.current.power.platform();}
    public static boolean powerIgnoring(String packageName){return RuntimeEngine.current.power.ignoring(packageName);}
    public static java.util.Map<Integer,Float> powerThresholds(){return RuntimeEngine.current.power.thresholds();}
    public static java.util.concurrent.Executor powerDefaultExecutor(){return RuntimeEngine.current.power.defaultExecutor();}
    public static void powerStatusAdd(java.util.concurrent.Executor executor,android.os.PowerManager.OnThermalStatusChangedListener listener){RuntimeEngine.current.power.add(listener,executor,false);}
    public static void powerStatusRemove(android.os.PowerManager.OnThermalStatusChangedListener listener){RuntimeEngine.current.power.remove(listener,false);}
    public static void powerHeadroomAdd(java.util.concurrent.Executor executor,android.os.PowerManager.OnThermalHeadroomChangedListener listener){RuntimeEngine.current.power.add(listener,executor,true);}
    public static void powerHeadroomRemove(android.os.PowerManager.OnThermalHeadroomChangedListener listener){RuntimeEngine.current.power.remove(listener,true);}
    public static android.app.usage.NetworkStatsManager createNetworkStatsManager(){return RuntimeSelfHookManagers.networkStatsManager();}
    public static boolean ownsNetworkStatsManager(android.app.usage.NetworkStatsManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.networkStats.owns(value);}
    public static void trafficUid(int uid){RuntimeNetworkStats.uid(uid);}
    public static void trafficKind(int kind){if(kind<0||kind>3)throw new IllegalArgumentException("TRAFFIC_COUNTER_KIND_NOT_GRANTED");}
    public static android.net.wifi.p2p.WifiP2pManager createWifiP2pManager(Context host){return RuntimeSelfHookManagers.wifiP2pManager(host);}
    public static boolean ownsWifiP2pManager(android.net.wifi.p2p.WifiP2pManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.wifiP2p.owns(value);}
    public static boolean ownsWifiP2pChannel(android.net.wifi.p2p.WifiP2pManager.Channel value){return RuntimeEngine.current!=null&&RuntimeEngine.current.wifiP2p.owns(value);}
    public static android.net.wifi.p2p.WifiP2pManager wifiP2pPlatform(){return RuntimeEngine.current.wifiP2p.platform();}
    public static android.net.wifi.p2p.WifiP2pManager.Channel wifiP2pInitialize(Context context,android.os.Looper looper,android.net.wifi.p2p.WifiP2pManager.ChannelListener listener){return RuntimeEngine.current.wifiP2p.initialize(context,looper,listener);}
    public static void wifiP2pBeginClose(android.net.wifi.p2p.WifiP2pManager.Channel channel){RuntimeEngine.current.wifiP2p.beginClose(channel);}
    public static void wifiP2pEndClose(android.net.wifi.p2p.WifiP2pManager.Channel channel,boolean confirmed){RuntimeEngine.current.wifiP2p.endClose(channel,confirmed);}
    public static android.app.NotificationManager createNotificationManager(android.content.Context host){return RuntimeSelfHookManagers.notificationManager(host);}
    public static android.content.pm.ShortcutManager createShortcutCapability(Context context){return RuntimeSelfHookManagers.shortcutManager(context);}
    public static boolean ownsShortcutCapability(android.content.pm.ShortcutManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.shortcuts.owns(value);}
    public static boolean shortcutPinSupported(){return RuntimeEngine.current.shortcuts.pinSupported();}
    public static boolean ownsNotificationManager(android.app.NotificationManager value){return RuntimeEngine.current!=null&&RuntimeEngine.current.notifications.owns(value);}
    public static android.app.NotificationManager notificationPlatform(){return RuntimeEngine.current.notifications.platform();}
    public static void notificationChannelIdentity(android.app.NotificationChannel value,String id,String group){dev.tripoder.selfhook.ManagerFrameworkAccess.channelIdentity(value,id,group);}
    public static void notificationGroupIdentity(android.app.NotificationChannelGroup value,String id,java.util.List<android.app.NotificationChannel> channels){dev.tripoder.selfhook.ManagerFrameworkAccess.groupIdentity(value,id,channels);}
    public static void notificationCreateChannels(java.util.List<android.app.NotificationChannel> values){RuntimeEngine.current.notifications.createChannels(values);}
    public static void notificationCreateGroups(java.util.List<android.app.NotificationChannelGroup> values){RuntimeEngine.current.notifications.createGroups(values);}
    public static android.app.NotificationChannel notificationChannel(String id,String conversation){return RuntimeEngine.current.notifications.channel(id,conversation);}
    public static java.util.List<android.app.NotificationChannel> notificationChannels(){return RuntimeEngine.current.notifications.channels();}
    public static android.app.NotificationChannelGroup notificationGroup(String id){return RuntimeEngine.current.notifications.group(id);}
    public static java.util.List<android.app.NotificationChannelGroup> notificationGroups(){return RuntimeEngine.current.notifications.groups();}
    public static void notificationDeleteChannel(String id){RuntimeEngine.current.notifications.deleteChannel(id);}
    public static void notificationDeleteGroup(String id){RuntimeEngine.current.notifications.deleteGroup(id);}
    public static void notificationPost(String tag,int id,android.app.Notification notification){RuntimeEngine.current.notifications.post(tag,id,notification);}
    public static void notificationCancel(String tag,int id){RuntimeEngine.current.notifications.cancel(tag,id);}
    public static void notificationCancelAll(){RuntimeEngine.current.notifications.cancelAll();}
    private RuntimeBridge(){}
    public static android.media.AudioManager createAudioManager(Context host){return RuntimeSelfHookManagers.audioManager(host);}
    public static boolean ownsAudioManager(android.media.AudioManager manager){return RuntimeEngine.current!=null&&RuntimeEngine.current.audio.owns(manager);}
    public static android.media.AudioManager audioPlatform(){return RuntimeEngine.current.audio.platform();}
    public static String audioProperty(String name){return RuntimeEngine.current.audio.property(name);}
    public static void audioRegister(android.media.AudioDeviceCallback callback,android.os.Handler handler){RuntimeEngine.current.audio.register(callback,handler);}
    public static void audioUnregister(android.media.AudioDeviceCallback callback){RuntimeEngine.current.audio.unregister(callback);}
    public static android.telephony.TelephonyManager createTelephonyManager(Context host){return RuntimeSelfHookManagers.telephonyManager(host);}
    public static boolean ownsTelephonyManager(android.telephony.TelephonyManager manager){return RuntimeEngine.current!=null&&RuntimeEngine.current.telephony.owns(manager);}
    public static android.telephony.TelephonyManager telephonyPlatform(){return RuntimeEngine.current.telephony.platform();}
    public static void telephonyRegister(java.util.concurrent.Executor executor,android.telephony.TelephonyCallback callback){RuntimeEngine.current.telephony.register(executor,callback);}
    public static void telephonyUnregister(android.telephony.TelephonyCallback callback){RuntimeEngine.current.telephony.unregister(callback);}
    public static boolean protectedBroadcast(String action){return RuntimeSelfHookComponents.protectedBroadcast(action);}
    public static android.net.ConnectivityManager createConnectivityManager(Context host){return RuntimeSelfHookManagers.connectivityManager(host);}
    public static boolean ownsConnectivityManager(android.net.ConnectivityManager manager){return RuntimeEngine.current!=null&&RuntimeEngine.current.connectivity.owns(manager);}
    public static android.net.ConnectivityManager networkPlatform(){return RuntimeEngine.current.connectivity.platform();}
    public static int networkCallbackFlags(android.net.ConnectivityManager.NetworkCallback callback){return RuntimeSelfHookManagers.networkCallbackFlags(callback);}
    public static void networkRegister(android.net.NetworkRequest request,android.net.ConnectivityManager.NetworkCallback callback,android.os.Handler handler,boolean defaults){RuntimeEngine.current.connectivity.register(request,callback,handler,defaults);}
    public static void networkUnregister(android.net.ConnectivityManager.NetworkCallback callback){RuntimeEngine.current.connectivity.unregister(callback);}
    public static ActivityManager createActivityManager(Context host){return RuntimeSelfHookManagers.activityManager(host);}
    public static boolean ownsActivityManager(ActivityManager manager){return RuntimeEngine.current!=null&&RuntimeEngine.current.activityManager.owns(manager);}
    public static ActivityManager activityPlatform(){return RuntimeEngine.current.activityManager.platform();}
    public static int memoryClass(boolean large){return RuntimeEngine.current.activityManager.memoryClass(large);}
    public static boolean lowRamDevice(){return RuntimeEngine.current.activityManager.lowRam();}
    public static void memoryInfo(ActivityManager.MemoryInfo output){RuntimeEngine.current.activityManager.memoryInfo(output);}
    public static java.util.List<ActivityManager.RunningAppProcessInfo> processView()throws Exception{return RuntimeEngine.current.activityManager.processes();}
    public static android.os.Debug.MemoryInfo[] processMemory(int[] pids)throws Exception{return RuntimeEngine.current.activityManager.processMemory(pids);}
    public static java.util.ArrayList<android.os.Bundle> historicalStarts(int max)throws Exception{return RuntimeEngine.current.activityManager.history(max);}
    public static java.util.List<android.app.ApplicationExitInfo> historicalExits(String pkg,int pid,int max)throws Exception{return RuntimeEngine.current.activityManager.exits(pkg,pid,max);}
    public static void storageFinisher(Runnable finisher){RuntimeSelfHookApplication.addFinisher(finisher);}
    public static String nativeStorageSpec()throws Exception{return RuntimeEngine.current.storage.nativeSpec(new java.io.File(RuntimeEngine.current.info.sourceDir).getParentFile());}
    public static boolean owns(Activity activity){return activity!=null&&RuntimeEngine.current!=null&&RuntimeEngine.current.ownsActivity(activity);}
    public static boolean instrumentationBound(Instrumentation instrumentation){return dev.tripoder.selfhook.ActivityFrameworkAccess.bound(instrumentation);}
    public static Activity constructWithCurrentInstrumentation(RuntimeActivityCreation.Permit permit)throws Exception{return RuntimeSelfHookActivities.construct(permit);}
    public static void attachedActivity(Activity activity){RuntimeEngine.current.activityAttached(activity);}
    public static void attachApplication(Application application,Context context){RuntimeSelfHookApplication.attach(application,context);}
    public static void observeApplicationOwnership(String stage,Context host,Application guest){RuntimeSelfHookApplication.observe(stage,host,guest);}
    public static Context createApplicationContext(Context host,Application guest,android.content.pm.ApplicationInfo info,ClassLoader loader,AppComponentFactory factory,Resources resources){return RuntimeSelfHookApplication.create(host,guest,info,loader,factory,resources);}
    public static void checkApplicationOwnership(Context host,Application guest){RuntimeSelfHookApplication.check(host,guest);}
    public static boolean ownsService(Service service){return RuntimeEngine.current!=null&&RuntimeEngine.current.services.owns(service);}
    public static void installServiceAdapter(Service service){RuntimeSelfHookComponents.installService(service);}
    public static long bindServiceFlags(Context.BindServiceFlags flags){return RuntimeSelfHookComponents.bindServiceFlags(flags);}
    public static Intent serviceIntent(Service service,Intent intent){return RuntimeEngine.current.services.visible(service,intent);}
    public static ComponentName serviceComponent(Service service){return RuntimeEngine.current.services.physical(service);}
    public static void serviceEvent(Service service,String event)throws Exception{RuntimeEngine.current.services.lifecycle(service,event);}
    public static Application application(){return RuntimeEngine.current.guestApplication();}
    public static Context context(Context original){if(original instanceof GuestContext){if(((GuestContext)original).engine!=RuntimeEngine.current)throw new SecurityException("ACTIVITY_CONTEXT_ENGINE_MISMATCH");return original;}return new GuestContext(original,RuntimeEngine.current);}
    public static void requireActivityContext(Context context){java.util.Set<Context> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());while(context!=null&&seen.size()<16&&seen.add(context)){if(context instanceof GuestContext&&((GuestContext)context).engine==RuntimeEngine.current)return;context=context instanceof ContextWrapper?((ContextWrapper)context).getBaseContext():null;}throw new SecurityException("ACTIVITY_LOGICAL_BASE_CONTEXT_REQUIRED");}
    public static Resources resources(){return RuntimeEngine.current.resources;}
    public static android.content.pm.ActivityInfo activityInfo(Activity activity){return RuntimeEngine.current.activityInfo(activity);}
    public static String processName(){return RuntimeEngine.current.processName;}
    public static int theme(Activity activity){return RuntimeEngine.current.theme(activity);}
    public static Intent intent(Activity activity){return RuntimeEngine.current.visibleIntent(activity);}
    public static Intent newIntent(Activity activity,Intent intent,ComponentCaller caller)throws Exception{return RuntimeEngine.current.newIntent(activity,intent,caller);}
    public static void newIntentComplete(Activity activity,String delivery,boolean success)throws Exception{RuntimeEngine.current.newIntentComplete(activity,delivery,success);}
    public static ComponentCaller activityCaller(Activity activity,ComponentCaller caller,boolean current){return RuntimeEngine.current.activityCallers.observe(activity,caller,current);}
    public static boolean ownsCaller(ComponentCaller caller){return RuntimeEngine.current.activityCallers.owns(caller);}
    public static String callerPackage(ComponentCaller caller){return RuntimeEngine.current.activityCallers.packageName(caller);}
    public static ComponentName callingActivity(Activity activity,ComponentName physical){return RuntimeEngine.current.activityCallers.callingActivity(activity,physical);}
    public static android.net.Uri activityReferrer(Activity activity){return RuntimeEngine.current.activityCallers.referrer(activity);}
    public static void selfHookReady(){RuntimeSelfHookBootstrap.PROCESS.requireComplete();RuntimeEngine.adapterReady=true;}
    public static void playUiSound(android.view.View root,int effect){RuntimeUiEffects.play(RuntimeEngine.current,root,effect);}
    public static boolean ownsResolver(ContentResolver resolver){return RuntimeEngine.current.ownsResolver(resolver);}
    public static ContentResolver resolver(){return RuntimeEngine.current.settingsResolver();}
    public static ContentProvider provider(){return RuntimeEngine.current.settingsProvider();}
    public static ContentProvider acquireProvider(String authority,boolean stable,boolean existing){return RuntimeEngine.current.providers.acquire(authority,stable,existing);}
    public static void releaseProvider(ContentProvider provider){if(provider instanceof RuntimeProviderFacade)((RuntimeProviderFacade)provider).close();}
    public static void settingsProviderHandle(Object handle){RuntimeEngine.current.providers.handles.settings(handle);}
    public static void rememberProviderHandle(Object handle,ContentProvider provider){RuntimeEngine.current.providers.handles.remember(handle,provider);}
    public static void releaseProviderHandle(Object handle){RuntimeEngine.current.providers.handles.release(handle);}
    public static boolean providerAttachmentScoped(ContentProvider provider){return RuntimeProviderAttachments.scoped(provider);}
    public static Context providerAttachContext(ContentProvider provider,Context guest,android.content.pm.ProviderInfo info,boolean testing){return RuntimeProviderAttachments.platformContext(provider,guest,info,testing);}
    public static void checkSettingsUri(android.net.Uri uri){SettingsReadProvider.uri(uri);}
    public static void registerSettingsObserver(android.net.Uri uri,boolean descendants,android.database.ContentObserver observer,int user){RuntimeEngine.current.providers.settingsObservers.register(uri,descendants,observer,user);}
    public static void unregisterSettingsObserver(android.database.ContentObserver observer){RuntimeEngine.current.providers.settingsObservers.unregister(observer);}
    public static boolean notifyLocalChange(android.net.Uri uri){return RuntimeEngine.current.providers.settingsObservers.notifyLocal(uri);}
    public static int[] batchSelectionKeys(ContentProviderOperation operation,int index){return RuntimeSelfHookComponents.selectionKeys(operation,index);}
    /** The physical summary is the Runtime exit-attribution marker; guest summaries are retained logically and never overwrite it. */
    public static void setProcessStateSummary(byte[] summary){RuntimeEngine.current.setGuestProcessStateSummary(summary);}
    public static void installReceiverAdapter(BroadcastReceiver receiver){RuntimeSelfHookComponents.installReceiver(receiver);}
    public static boolean ownsReceiver(BroadcastReceiver receiver){return RuntimeEngine.current!=null&&RuntimeEngine.current.receivers.owns(receiver);}
    public static Intent receiverBefore(BroadcastReceiver receiver,Intent intent,Object pending){return RuntimeEngine.current.receivers.before(receiver,intent,pending);}
    public static void receiverAfter(BroadcastReceiver receiver,boolean success){RuntimeEngine.current.receivers.after(receiver,success);}
    public static void receiverAsync(BroadcastReceiver receiver,Object pending){RuntimeEngine.current.receivers.asynchronous(receiver,pending);}
    public static void receiverFinished(Object pending){RuntimeEngine.current.receivers.finished(pending);}
    public static void attach(Activity activity){RuntimeSelfHookActivities.attach(activity);}
    public static Intent route(Activity caller,Intent intent,int requestCode)throws Exception{return RuntimeEngine.current.route(caller,intent,requestCode);}
    public static Intent routeFromContext(Intent intent)throws Exception{return RuntimeEngine.current.routeFromContext(intent);}
    public static android.content.ComponentName resolveOwnActivity(Intent intent){return RuntimeEngine.current==null?null:GuestIntentResolver.resolveActivity(RuntimeEngine.current,intent);}
    private static final java.util.Map<android.app.Service,Integer> FOREGROUND=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public static Object serviceForeground(android.app.Service service,String name,Object[] args){
        RuntimeEngine engine=RuntimeEngine.current;
        switch(name){
            case "startForeground":{int id=(Integer)args[0];engine.notifications.post(null,id,(android.app.Notification)args[1]);FOREGROUND.put(service,id);return null;}
            case "stopForeground":{
                boolean remove=args[0] instanceof Boolean?(Boolean)args[0]:(((Integer)args[0])&android.app.Service.STOP_FOREGROUND_REMOVE)!=0;
                Integer id=FOREGROUND.get(service);if(id!=null&&remove){engine.notifications.facade().cancel(id);}if(remove)FOREGROUND.remove(service);return null;
            }
            case "getForegroundServiceType":return 0;
            default:throw new UnsupportedOperationException("FOREGROUND_SERVICE_CALL_UNSUPPORTED:"+name);
        }
    }
    public static void noteSystemResult(Activity caller,int requestCode)throws Exception{RuntimeEngine.current.noteSystemResult(caller,requestCode);}
    public static android.content.ComponentName resolveOwnService(Intent intent){if(RuntimeEngine.current==null)return null;var hits=GuestIntentResolver.query(RuntimeEngine.current,"service",intent,0);return hits.isEmpty()||hits.get(0).serviceInfo==null?null:new android.content.ComponentName(hits.get(0).serviceInfo.packageName,hits.get(0).serviceInfo.name);}
    public static boolean startsHost(Intent intent){return RuntimeEngine.current!=null&&RuntimeEngine.current.targetsHost(intent);}
    public static void activityStartResult(Object intent,int result)throws Exception{RuntimeEngine.current.activityStartResult(intent,result);}
    public static void returning(Activity activity,int code,Intent data)throws Exception{RuntimeEngine.current.returning(activity,code,data);}
    public static String receiving(Activity activity,int requestCode,int resultCode,ComponentCaller caller)throws Exception{if(!owns(activity))throw new SecurityException("RESULT_RECEIVER_MISMATCH");return RuntimeEngine.current.receiving(activity,requestCode,resultCode,caller);}
    public static void received(Activity activity,String operation,int resultCode,boolean success)throws Exception{RuntimeEngine.current.received(activity,operation,resultCode,success);}
}
