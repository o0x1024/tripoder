package dev.tripoder.runtime;
import android.app.*;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import dev.tripoder.selfhook.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
/** Actual boot installation and physical passthrough under an owned fixture.
 * Product routing callbacks need the real Engine and are not claimed here. */
public final class OwnedComponentInstallerChecks {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static final class OwnedService extends Service {public IBinder onBind(Intent intent){return null;}}
    public static final class OwnedIntentService extends IntentService {
        final CountDownLatch handled=new CountDownLatch(1);volatile Intent received;
        public OwnedIntentService(){super("owned-fixed-intent-service");}
        protected void onHandleIntent(Intent intent){received=intent;handled.countDown();}
    }
    private static final class Provider extends ContentProvider {
        int creates;
        public boolean onCreate(){creates++;return true;}
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String w,String[] a){return 0;}public int update(Uri u,ContentValues v,String w,String[] a){return 0;}
    }
    private static Method named(FixedComponentHooks.Group group,Class<?> owner,String name,String descriptor){
        Method found=null;for(Method m:FixedComponentHooks.methods(group))if(m.getDeclaringClass()==owner&&m.getName().equals(name)
            &&java.lang.invoke.MethodType.methodType(m.getReturnType(),m.getParameterTypes()).toMethodDescriptorString().equals(descriptor)){
            if(found!=null)throw new AssertionError("AMBIGUOUS_FIXED_FIXTURE_TARGET");found=m;}
        if(found==null)throw new AssertionError("MISSING_FIXED_FIXTURE_TARGET");return found;
    }
    public static JSONObject run(Context context,FrameworkAccess.Members members,String run)throws Exception {
        android.util.Log.i("TripoderFixedStage","MANAGED_INITIALIZE");ManagedHook.initializeProcess();
        android.util.Log.i("TripoderFixedStage","SERVICE_TOKEN_PREPARE");ServiceTokenAccess.prepare();
        android.util.Log.i("TripoderFixedStage","FIXED_COMPONENT_PREPARE");FixedComponentHooks.prepare();
        android.util.Log.i("TripoderFixedStage","FIXED_RUNTIME_PREPARE");FixedRuntimeHooks.prepare();
        check(FixedComponentHooks.methods(FixedComponentHooks.Group.SERVICES).size()==18&&FixedComponentHooks.methods(FixedComponentHooks.Group.PROVIDERS).size()==28
            &&FixedComponentHooks.methods(FixedComponentHooks.Group.RECEIVERS).size()==6&&FixedComponentHooks.methods(FixedComponentHooks.Group.WIDGETS).size()==28,"COMPLETE_FIXED_COMPONENT_SETS");
        int capacity=ManagedHook.remainingCapacity();boolean rejected=false;
        try{ManagedHook.prepare(Service.class.getMethod("getApplication"),new Executable[0]);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED");}
        check(rejected&&ManagedHook.remainingCapacity()==capacity,"UNSELECTED_BOOT_METHOD_REJECTED_BEFORE_SLOTS");
        rejected=false;try{ManagedHook.prepare(IntentService.class.getMethod("onStart",Intent.class,int.class),new Executable[0]);}
        catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED");}
        check(rejected&&ManagedHook.remainingCapacity()==capacity,"INTENT_SERVICE_OWNER_DOES_NOT_ADMIT_UNSELECTED_METHODS");
        Method reservedTarget=named(FixedComponentHooks.Group.SERVICES,IntentService.class,"onCreate","()V");
        ManagedHook.Handle reservedHandle=ManagedHook.prepare(reservedTarget,new Executable[0]);
        rejected=false;try{ManagedHook.enableEntryBatch(Map.of(reservedHandle,call->call.original(call.arguments())));}
        catch(IllegalArgumentException expected){rejected="GUEST_CALLBACK_ENTRY_REQUIRED".equals(expected.getMessage());}
        check(rejected&&ManagedHook.remainingCapacity()==capacity-1,"CANONICAL_BOOT_CLASS_EXCLUDED_FROM_GUEST_ENTRY_PUBLICATION");
        Map<Executable,ManagedHook.Handle> reservations=new LinkedHashMap<>();reservations.put(reservedTarget,reservedHandle);
        android.util.Log.i("TripoderFixedStage","SYSTEM_RECEIVER_SCOPE");JSONObject systemReceiverScope=OwnedSystemReceiverChecks.run(reservations);
        ConcurrentHashMap<String,AtomicInteger> calls=new ConcurrentHashMap<>();ConcurrentHashMap<Method,Throwable> originalFailures=new ConcurrentHashMap<>();Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(FixedComponentHooks.Group group:FixedComponentHooks.Group.values())for(Method method:FixedComponentHooks.methods(group))callbacks.put(method,(receiver,args,original)->{
            calls.computeIfAbsent(method.getDeclaringClass().getName()+"."+method.getName(),key->new AtomicInteger()).incrementAndGet();try{return original.call(args);}catch(Throwable error){originalFailures.put(method,error);throw error;}
        });
        int componentCount=callbacks.size();
        int remainingCount=0;JSONArray remainingGroups=new JSONArray();
        for(FixedRuntimeHooks.Group group:FixedRuntimeHooks.Group.values()){
            var selected=FixedRuntimeHooks.methods(group);remainingCount+=selected.size();remainingGroups.put(selected.size());
            for(Executable target:selected)callbacks.put(target,(receiver,args,original)->original.call(args));
        }
        check(remainingCount==624&&remainingGroups.toString().equals(new JSONArray(List.of(6,11,10,517,62,1,2,2,4,5,1,1,2)).toString()),"COMPLETE_REMAINING_RUNTIME_TARGETS:"+remainingGroups+":"+remainingCount);
        int fixedCount=componentCount+remainingCount;
        if(componentCount!=80||callbacks.size()!=fixedCount)throw new AssertionError("FIXED_CALLBACK_SET_OVERLAP");
        RuntimeManagedInstaller installer=new RuntimeManagedInstaller(reservations);
        android.util.Log.i("TripoderFixedStage","INSTALLING_702");installer.install(callbacks);
        android.util.Log.i("TripoderFixedStage","INSTALLED_702");
        check(installer.installedCount()==fixedCount&&capacity-ManagedHook.remainingCapacity()==fixedCount,"ALL_FIXED_BOOT_BINDINGS_PUBLISHED");
        OwnedService service=new OwnedService();Binder token=new Binder();List<Integer> stopped=Collections.synchronizedList(new ArrayList<>());
        Class<?> managerType=Class.forName("android.app.IActivityManager",false,null);
        Object manager=Proxy.newProxyInstance(OwnedComponentInstallerChecks.class.getClassLoader(),new Class<?>[]{managerType},(proxy,method,args)->{
            if(!method.getName().equals("stopServiceToken")||args[1]!=token||!((ComponentName)args[0]).getPackageName().equals(context.getPackageName()))throw new AssertionError("PHYSICAL_SERVICE_ARGUMENTS");
            stopped.add((Integer)args[2]);return true;
        });
        Field thread=members.fields[11];thread.setAccessible(true);
        Method attach=named(FixedComponentHooks.Group.SERVICES,Service.class,"attach","(Landroid/content/Context;Landroid/app/ActivityThread;Ljava/lang/String;Landroid/os/IBinder;Landroid/app/Application;Ljava/lang/Object;)V");
        attach.invoke(service,context,thread.get(null),service.getClass().getName(),token,(Application)context,manager);
        check(service.getBaseContext()==context&&service.getApplication()==context,"ORIGINAL_SERVICE_ATTACH_PHYSICAL_IDENTITIES");
        service.onCreate();service.onDestroy();service.onRebind(null);check(!service.onUnbind(null)&&service.onStartCommand(null,7,Integer.MAX_VALUE)==Service.START_STICKY,"ORIGINAL_INHERITED_SERVICE_LIFECYCLES");
        check(service.stopSelfResult(123),"ORIGINAL_SERVICE_STOP_RESULT");service.stopSelf(9);service.stopSelf();
        check(stopped.equals(List.of(123,9,-1)),"ORIGINAL_SERVICE_TOKEN_AND_START_IDS");
        check(ServiceTokenAccess.stop(service,new ComponentName(context,service.getClass()),124)&&stopped.equals(List.of(123,9,-1,124)),"FIXED_JNI_TOKEN_BYPASS_AFTER_MANAGED_HOOKS");
        android.util.Log.i("TripoderFixedStage","SERVICE_TOKEN_SCOPE");JSONObject serviceTokenScope=OwnedServiceTokenChecks.run();

        OwnedIntentService intentService=new OwnedIntentService();
        attach.invoke(intentService,context,thread.get(null),intentService.getClass().getName(),token,(Application)context,manager);
        Intent work=new Intent("owned.fixed.WORK").setComponent(new ComponentName(context,intentService.getClass())).putExtra("sequence",42);
        intentService.onCreate();
        try{
            check(intentService.onBind(work)==null&&intentService.onStartCommand(work,0,1)==Service.START_NOT_STICKY,"ORIGINAL_INTENT_SERVICE_NULL_BINDING_AND_QUEUE_RESULT");
            check(intentService.handled.await(6,TimeUnit.SECONDS)&&intentService.received==work,"ORIGINAL_INTENT_SERVICE_ASYNC_WORK_PASSTHROUGH");
        }finally{intentService.onDestroy();}
        check(calls.getOrDefault("android.app.IntentService.onCreate",new AtomicInteger()).get()==1
            &&calls.getOrDefault("android.app.IntentService.onDestroy",new AtomicInteger()).get()==1
            &&calls.getOrDefault("android.app.IntentService.onStartCommand",new AtomicInteger()).get()==1
            &&calls.getOrDefault("android.app.IntentService.onBind",new AtomicInteger()).get()==1,"ACTUAL_FIXED_INTENT_SERVICE_CALLBACKS");

        Provider provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="dev.tripoder.selfhookprobe.owned-provider";info.exported=false;info.applicationInfo=new android.content.pm.ApplicationInfo(context.getApplicationInfo());
        named(FixedComponentHooks.Group.PROVIDERS,ContentProvider.class,"attachInfo","(Landroid/content/Context;Landroid/content/pm/ProviderInfo;Z)V").invoke(provider,context,info,true);
        check(provider.creates==1&&provider.getContext()==context,"ORIGINAL_PROVIDER_ATTACH_ONCE");
        ContentResolver wrapped=ContentResolver.wrap(provider);check(wrapped.getClass().getName().equals("android.content.ContentResolver$1"),"PINNED_WRAPPED_RESOLVER_CLASS");
        Method acquire=named(FixedComponentHooks.Group.PROVIDERS,ContentResolver.class,"acquireExistingProvider","(Landroid/content/Context;Ljava/lang/String;)Landroid/content/IContentProvider;"),
            release=named(FixedComponentHooks.Group.PROVIDERS,Class.forName("android.content.ContentResolver$1",false,null),"releaseProvider","(Landroid/content/IContentProvider;)Z");
        try{acquire.invoke(wrapped,context,info.authority);throw new AssertionError("WRAPPED_ACQUIRE_ORIGINAL_RETURNED");}
        catch(InvocationTargetException error){check(error.getCause() instanceof UnsupportedOperationException&&error.getCause()==originalFailures.get(acquire),"EXACT_ORIGINAL_WRAPPED_ACQUIRE_EXCEPTION");}
        try{release.invoke(wrapped,ComponentFrameworkAccess.providerHandle(provider));throw new AssertionError("WRAPPED_RELEASE_ORIGINAL_RETURNED");}
        catch(InvocationTargetException error){check(error.getCause() instanceof UnsupportedOperationException&&error.getCause()==originalFailures.get(release),"EXACT_ORIGINAL_WRAPPED_RELEASE_EXCEPTION");}
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();String action="dev.tripoder.selfhookprobe.FIXED_COMPONENT_"+run;
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context host,Intent intent){try{
            Object before=ComponentFrameworkAccess.pendingResult(this);PendingResult pending=goAsync();
            if(pending!=before||ComponentFrameworkAccess.pendingResult(this)!=null)throw new AssertionError("REAL_PENDING_RESULT_TRANSFER");pending.finish();
        }catch(Throwable failure){error.set(failure);}finally{done.countDown();}}};
        context.registerReceiver(receiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{context.sendOrderedBroadcast(new Intent(action).setPackage(context.getPackageName()),null);check(done.await(10,TimeUnit.SECONDS)&&error.get()==null,"REAL_GO_ASYNC_AND_FINISH:"+error.get());}
        finally{context.unregisterReceiver(receiver);}
        check(calls.getOrDefault("android.app.Service.attach",new AtomicInteger()).get()==2&&calls.getOrDefault("android.app.Service.onCreate",new AtomicInteger()).get()==2
            &&calls.getOrDefault("android.content.ContentProvider.attachInfo",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.ContentResolver.acquireExistingProvider",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.ContentResolver$1.acquireProvider",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.BroadcastReceiver.goAsync",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.BroadcastReceiver$PendingResult.finish",new AtomicInteger()).get()>=1,"ACTUAL_FIXED_ENTRY_CALLBACKS");
        android.util.Log.i("TripoderFixedStage","ROOTS_GC");System.gc();JSONObject roots=new JSONObject(OriginalMethodStorage.report());
        check(roots.getInt("published")==fixedCount&&roots.getInt("rootedOutsideDeclaredTables")==fixedCount&&roots.getInt("bootAllocations")==fixedCount,"ALL_BOOT_BACKUPS_ROOTED_AFTER_GC");
        rejected=false;try{installer.install(callbacks);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_INSTALLER_TARGET_UNAVAILABLE");}
        check(rejected&&installer.installedCount()==fixedCount,"DUPLICATE_PERMANENT_BINDINGS_REJECTED");
        android.util.Log.i("TripoderFixedStage","COMPONENT_INSTALLER_COMPLETE");JSONObject observed=new JSONObject();for(var entry:calls.entrySet())observed.put(entry.getKey(),entry.getValue().get());
        return new JSONObject().put("checks",checks).put("fixedMethods",fixedCount).put("remainingGroups",remainingGroups).put("groups",new JSONArray(List.of(18,28,6,28))).put("callbacks",observed).put("roots",roots)
            .put("entryOnlyBootRejected",true).put("unselectedIntentServiceRejected",true)
            .put("serviceTokenScope",serviceTokenScope)
            .put("systemReceiverScope",systemReceiverScope)
            .put("actualFixedInstaller",true).put("productCallbacksInstalled",false).put("engineConnected",false).put("productAccepted",false);
    }
}
