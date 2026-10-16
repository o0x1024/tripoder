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
    private static final class Provider extends ContentProvider {
        int creates;
        public boolean onCreate(){creates++;return true;}
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String w,String[] a){return 0;}public int update(Uri u,ContentValues v,String w,String[] a){return 0;}
    }
    private static Method named(FixedComponentHooks.Group group,String name,int arguments){
        Method found=null;for(Method m:FixedComponentHooks.methods(group))if(m.getName().equals(name)&&m.getParameterCount()==arguments){
            if(found!=null)throw new AssertionError("AMBIGUOUS_FIXED_FIXTURE_TARGET");found=m;}
        if(found==null)throw new AssertionError("MISSING_FIXED_FIXTURE_TARGET");return found;
    }
    public static JSONObject run(Context context,FrameworkAccess.Members members,String run)throws Exception {
        ManagedHook.initializeProcess();ServiceTokenAccess.prepare();FixedComponentHooks.prepare();FixedRuntimeHooks.prepare();
        check(FixedComponentHooks.methods(FixedComponentHooks.Group.SERVICES).size()==14&&FixedComponentHooks.methods(FixedComponentHooks.Group.PROVIDERS).size()==28
            &&FixedComponentHooks.methods(FixedComponentHooks.Group.RECEIVERS).size()==3,"COMPLETE_FIXED_COMPONENT_SETS");
        int capacity=ManagedHook.remainingCapacity();boolean rejected=false;
        try{ManagedHook.prepare(Service.class.getMethod("getApplication"),new Executable[0]);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED");}
        check(rejected&&ManagedHook.remainingCapacity()==capacity,"UNSELECTED_BOOT_METHOD_REJECTED_BEFORE_SLOTS");
        ConcurrentHashMap<String,AtomicInteger> calls=new ConcurrentHashMap<>();ConcurrentHashMap<Method,Throwable> originalFailures=new ConcurrentHashMap<>();Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(FixedComponentHooks.Group group:FixedComponentHooks.Group.values())for(Method method:FixedComponentHooks.methods(group))callbacks.put(method,(receiver,args,original)->{
            calls.computeIfAbsent(method.getDeclaringClass().getName()+"."+method.getName(),key->new AtomicInteger()).incrementAndGet();try{return original.call(args);}catch(Throwable error){originalFailures.put(method,error);throw error;}
        });
        int remainingCount=0;JSONArray remainingGroups=new JSONArray();
        for(FixedRuntimeHooks.Group group:FixedRuntimeHooks.Group.values()){
            var selected=FixedRuntimeHooks.methods(group);remainingCount+=selected.size();remainingGroups.put(selected.size());
            for(Executable target:selected)callbacks.put(target,(receiver,args,original)->original.call(args));
        }
        check(remainingCount==607,"COMPLETE_REMAINING_RUNTIME_TARGETS");
        int fixedCount=45+remainingCount;
        RuntimeManagedInstaller installer=new RuntimeManagedInstaller(java.util.Map.of());installer.install(callbacks);
        check(installer.installedCount()==fixedCount&&capacity-ManagedHook.remainingCapacity()==fixedCount,"ALL_FIXED_BOOT_BINDINGS_PUBLISHED");
        OwnedService service=new OwnedService();Binder token=new Binder();List<Integer> stopped=Collections.synchronizedList(new ArrayList<>());
        Class<?> managerType=Class.forName("android.app.IActivityManager",false,null);
        Object manager=Proxy.newProxyInstance(OwnedComponentInstallerChecks.class.getClassLoader(),new Class<?>[]{managerType},(proxy,method,args)->{
            if(!method.getName().equals("stopServiceToken")||args[1]!=token||!((ComponentName)args[0]).getPackageName().equals(context.getPackageName()))throw new AssertionError("PHYSICAL_SERVICE_ARGUMENTS");
            stopped.add((Integer)args[2]);return true;
        });
        Field thread=members.fields[11];thread.setAccessible(true);
        named(FixedComponentHooks.Group.SERVICES,"attach",6).invoke(service,context,thread.get(null),service.getClass().getName(),token,(Application)context,manager);
        check(service.getBaseContext()==context&&service.getApplication()==context,"ORIGINAL_SERVICE_ATTACH_PHYSICAL_IDENTITIES");
        service.onCreate();service.onDestroy();service.onRebind(null);check(!service.onUnbind(null)&&service.onStartCommand(null,7,Integer.MAX_VALUE)==Service.START_STICKY,"ORIGINAL_INHERITED_SERVICE_LIFECYCLES");
        check(service.stopSelfResult(123),"ORIGINAL_SERVICE_STOP_RESULT");service.stopSelf(9);service.stopSelf();
        check(stopped.equals(List.of(123,9,-1)),"ORIGINAL_SERVICE_TOKEN_AND_START_IDS");
        check(ServiceTokenAccess.stop(service,new ComponentName(context,service.getClass()),124)&&stopped.equals(List.of(123,9,-1,124)),"FIXED_JNI_TOKEN_BYPASS_AFTER_MANAGED_HOOKS");

        Provider provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="dev.tripoder.selfhookprobe.owned-provider";info.exported=false;info.applicationInfo=new android.content.pm.ApplicationInfo(context.getApplicationInfo());
        named(FixedComponentHooks.Group.PROVIDERS,"attachInfo",3).invoke(provider,context,info,true);
        check(provider.creates==1&&provider.getContext()==context,"ORIGINAL_PROVIDER_ATTACH_ONCE");
        ContentResolver wrapped=ContentResolver.wrap(provider);check(wrapped.getClass().getName().equals("android.content.ContentResolver$1"),"PINNED_WRAPPED_RESOLVER_CLASS");
        Method acquire=named(FixedComponentHooks.Group.PROVIDERS,"acquireExistingProvider",2),release=named(FixedComponentHooks.Group.PROVIDERS,"releaseProvider",1);
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
        check(calls.getOrDefault("android.app.Service.attach",new AtomicInteger()).get()==1&&calls.getOrDefault("android.app.Service.onCreate",new AtomicInteger()).get()==1
            &&calls.getOrDefault("android.content.ContentProvider.attachInfo",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.ContentResolver.acquireExistingProvider",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.ContentResolver$1.acquireProvider",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.BroadcastReceiver.goAsync",new AtomicInteger()).get()>=1
            &&calls.getOrDefault("android.content.BroadcastReceiver$PendingResult.finish",new AtomicInteger()).get()>=1,"ACTUAL_FIXED_ENTRY_CALLBACKS");
        System.gc();JSONObject roots=new JSONObject(OriginalMethodStorage.report());
        check(roots.getInt("published")==fixedCount&&roots.getInt("rootedOutsideDeclaredTables")==fixedCount&&roots.getInt("bootAllocations")==fixedCount,"ALL_BOOT_BACKUPS_ROOTED_AFTER_GC");
        rejected=false;try{installer.install(callbacks);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_INSTALLER_TARGET_UNAVAILABLE");}
        check(rejected&&installer.installedCount()==fixedCount,"DUPLICATE_PERMANENT_BINDINGS_REJECTED");
        JSONObject observed=new JSONObject();for(var entry:calls.entrySet())observed.put(entry.getKey(),entry.getValue().get());
        return new JSONObject().put("checks",checks).put("fixedMethods",fixedCount).put("remainingGroups",remainingGroups).put("groups",new JSONArray(List.of(14,28,3))).put("callbacks",observed).put("roots",roots)
            .put("actualFixedInstaller",true).put("productCallbacksInstalled",false).put("engineConnected",false).put("productAccepted",false);
    }
}
