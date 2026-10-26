package dev.tripoder.selfhook;

import android.app.ActivityManager;
import android.app.ApplicationStartInfo;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Fixed members, owned values and a package-scoped self broadcast only. */
public final class OwnedProductFrameworkChecks {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new IllegalStateException(name);}
    private static final class Provider extends ContentProvider {
        public boolean onCreate(){return true;}
        public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
        public String getType(Uri uri){return null;}
        public Uri insert(Uri uri,ContentValues values){return null;}
        public int delete(Uri uri,String where,String[] args){return 0;}
        public int update(Uri uri,ContentValues values,String where,String[] args){return 0;}
    }
    public static JSONObject run(Context context,String run) throws Exception {
        android.util.Log.i("TripoderFixedStage","FRAMEWORK_PREPARE");
        FrameworkAccess.Members members=FrameworkAccess.prepare();
        android.util.Log.i("TripoderFixedStage","PHYSICAL_APPLICATION_PREPARE");PhysicalApplication.prepare((android.app.Application)context,members);
        android.util.Log.i("TripoderFixedStage","COMPONENT_FRAMEWORK_PREPARE");ComponentFrameworkAccess.prepare();
        android.util.Log.i("TripoderFixedStage","MANAGER_FRAMEWORK_PREPARE");ManagerFrameworkAccess.prepare();
        android.util.Log.i("TripoderFixedStage","ACTIVITY_FRAMEWORK_PREPARE");ActivityFrameworkAccess.prepare();
        android.util.Log.i("TripoderFixedStage","PACKAGE_FRAMEWORK_PREPARE");PackageFrameworkAccess.prepare();
        android.util.Log.i("TripoderFixedStage","PREPARE_COMPLETE");
        long[] values={0,1,1L<<32,9007199254740993L,Long.MAX_VALUE&~(1L<<31)};
        for(long value:values)check(ComponentFrameworkAccess.bindFlags(Context.BindServiceFlags.of(value))==value,"BIND_FLAGS_EXACT_LONG");
        boolean invalidFlags=false;try{Context.BindServiceFlags.of(1L<<31);}catch(IllegalArgumentException expected){invalidFlags=true;}
        check(invalidFlags,"PLATFORM_DEPRECATED_FLAGS_REJECTED");
        check(ComponentFrameworkAccess.protectedBroadcast(Intent.ACTION_BOOT_COMPLETED),"PROTECTED_SYSTEM_ACTION");
        String action="dev.tripoder.selfhookprobe.OWNED_"+run;
        check(!ComponentFrameworkAccess.protectedBroadcast(action),"OWNED_ACTION_NOT_PROTECTED");
        CountDownLatch completed=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context receiverContext,Intent intent){
            try {
                Object first=ComponentFrameworkAccess.pendingResult(this);
                check(first!=null&&first==ComponentFrameworkAccess.pendingResult(this),"ACTUAL_PENDING_RESULT_IDENTITY");
                PendingResult async=goAsync();check(async==first&&ComponentFrameworkAccess.pendingResult(this)==null,"PENDING_RESULT_TRANSFER_IDENTITY");
                async.finish();
            }catch(Throwable failure){error.set(failure);}finally{completed.countDown();}
        }};
        check(ComponentFrameworkAccess.pendingResult(receiver)==null,"UNDELIVERED_PENDING_NULL");
        context.registerReceiver(receiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try {
            context.sendOrderedBroadcast(new Intent(action).setPackage(context.getPackageName()),null);
            check(completed.await(10,TimeUnit.SECONDS)&&error.get()==null,"OWNED_BROADCAST_DELIVERY:"+error.get());
        } finally {context.unregisterReceiver(receiver);}
        Provider provider=new Provider();Object handle=ComponentFrameworkAccess.providerHandle(provider);
        check(handle==ComponentFrameworkAccess.providerHandle(provider)&&handle instanceof IInterface,"PROVIDER_HANDLE_IDENTITY");
        check(((IInterface)handle).asBinder()!=null,"PROVIDER_HANDLE_BINDER");
        Uri uri=Uri.parse("content://owned-probe/items");
        ContentProviderOperation operation=ContentProviderOperation.newUpdate(uri).withValue("x",1)
            .withSelection("a=? AND b=?",new String[]{"owned",null}).withSelectionBackReference(1,0,"column").build();
        ComponentFrameworkAccess.Selection snapshot=ComponentFrameworkAccess.selection(operation);
        check(Arrays.equals(snapshot.keys(),new int[]{0,1}),"SELECTION_EXACT_KEYS");
        Object[] selected=snapshot.values();check(selected[0].equals("owned")&&selected[1] instanceof ComponentFrameworkAccess.BackReference,"SELECTION_TYPED_VALUES");
        ComponentFrameworkAccess.BackReference ref=(ComponentFrameworkAccess.BackReference)selected[1];
        check(ref.fromIndex==0&&"column".equals(ref.fromKey),"SELECTION_BACK_REFERENCE");
        selected[0]="mutated";int[] keys=snapshot.keys();keys[0]=99;
        check(snapshot.values()[0].equals("owned")&&snapshot.keys()[0]==0,"SELECTION_SNAPSHOT_IMMUTABLE");
        check(ComponentFrameworkAccess.selection(ContentProviderOperation.newDelete(uri).build()).keys().length==0,"EMPTY_SELECTION");
        boolean rejected=false;
        try{ComponentFrameworkAccess.selection(ContentProviderOperation.newDelete(uri).withSelection("?",new String[129]).build());}
        catch(IllegalArgumentException expected){rejected=expected.getMessage().equals("PROVIDER_BATCH_SELECTION_TOO_LARGE");}
        check(rejected,"SELECTION_BUDGET_REJECTION");
        Handler handler=new Handler(Looper.getMainLooper());
        Object[] managers={ManagerFrameworkAccess.activity(context,handler),ManagerFrameworkAccess.connectivity(context,context.getSystemService(ConnectivityManager.class)),
            ManagerFrameworkAccess.telephony(context),ManagerFrameworkAccess.audio(context),ManagerFrameworkAccess.battery(),ManagerFrameworkAccess.power(context,handler),ManagerFrameworkAccess.wifi(null,null),ManagerFrameworkAccess.stats(),ManagerFrameworkAccess.notification(context),ManagerFrameworkAccess.shortcut(context)};
        String[] names={"android.app.ActivityManager","android.net.ConnectivityManager","android.telephony.TelephonyManager","android.media.AudioManager","android.os.BatteryManager","android.os.PowerManager","android.net.wifi.p2p.WifiP2pManager","android.app.usage.NetworkStatsManager","android.app.NotificationManager","android.content.pm.ShortcutManager"};
        for(int i=0;i<managers.length;i++)check(managers[i]!=null&&managers[i].getClass().getName().equals(names[i]),"FIXED_MANAGER_CONSTRUCTOR:"+names[i]);
        check(managers[0]!=context.getSystemService(ActivityManager.class)&&managers[1]!=context.getSystemService(ConnectivityManager.class),"DISTINCT_MANAGER_FACADES");
        check(ManagerFrameworkAccess.callbackFlags(new ConnectivityManager.NetworkCallback())==0,"DEFAULT_NETWORK_CALLBACK_FLAGS");
        android.app.NotificationChannel channel=new android.app.NotificationChannel("before","Owned",android.app.NotificationManager.IMPORTANCE_LOW);
        ManagerFrameworkAccess.channelIdentity(channel,"after","owned-group");check(channel.getId().equals("after")&&channel.getGroup().equals("owned-group"),"FIXED_NOTIFICATION_CHANNEL_IDENTITIES");
        android.app.NotificationChannelGroup group=new android.app.NotificationChannelGroup("before","Owned");
        java.util.List<android.app.NotificationChannel> channels=new java.util.ArrayList<>(java.util.List.of(channel));
        ManagerFrameworkAccess.groupIdentity(group,"mapped-group",channels);check(group.getId().equals("mapped-group")&&group.getChannels()==channels,"FIXED_NOTIFICATION_GROUP_IDENTITIES");
        long started=9007199254740993L,onCreate=started+7;
        ApplicationStartInfo history=ManagerFrameworkAccess.history(1234567890123L,ApplicationStartInfo.STARTUP_STATE_STARTED,54321,12345,
            ApplicationStartInfo.START_REASON_SERVICE,ApplicationStartInfo.START_TYPE_COLD,ApplicationStartInfo.START_COMPONENT_SERVICE,ApplicationStartInfo.LAUNCH_MODE_STANDARD,
            "owned.logical","owned.logical:worker",started,onCreate);
        check(history.getPid()==54321&&history.getRealUid()==12345&&history.getPackageUid()==12345&&history.getDefiningUid()==12345,"HISTORY_IDENTITIES");
        check("owned.logical:worker".equals(history.getProcessName())&&!history.wasForceStopped()&&history.getIntent()==null,"HISTORY_NO_HOST_INTENT");
        check(history.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH)==started
            &&history.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE)==onCreate,"HISTORY_EXACT_LONG_TIMESTAMPS");
        ApplicationStartInfo absent=ManagerFrameworkAccess.history(1,0,1,1,0,0,0,0,"owned","owned",123,null);
        check(!absent.getStartupTimestamps().containsKey(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE),"HISTORY_OPTIONAL_TIMESTAMP_ABSENT");
        check(ActivityFrameworkAccess.bound(ActivityFrameworkAccess.instrumentation()),"PHYSICAL_BOUND_INSTRUMENTATION");
        check(!ActivityFrameworkAccess.bound(new android.app.Instrumentation()),"UNBOUND_INSTRUMENTATION_IDENTITY");
        java.util.concurrent.FutureTask<android.app.Activity> createActivity=new java.util.concurrent.FutureTask<>(android.app.Activity::new);new Handler(Looper.getMainLooper()).post(createActivity);android.app.Activity detached=createActivity.get(10,TimeUnit.SECONDS);Intent resultIntent=new Intent("owned-result");detached.setResult(0x7ffffffe,resultIntent);
        check(ActivityFrameworkAccess.resultCode(detached)==0x7ffffffe&&ActivityFrameworkAccess.resultData(detached)==resultIntent,"ACTIVITY_RESULT_FIELD_IDENTITIES");
        int user=PackageFrameworkAccess.userId();check(user==android.os.Process.myUserHandle().hashCode(),"FIXED_USER_IDENTITY");
        android.content.pm.PackageInfo physicalInfo=(android.content.pm.PackageInfo)PackageFrameworkAccess.physical("package",context.getPackageName(),0L,user);
        check(physicalInfo!=null&&physicalInfo.packageName.equals(context.getPackageName()),"EXACT_OWN_PHYSICAL_PACKAGE_QUERY");
        android.content.pm.ApplicationInfo physicalApplication=(android.content.pm.ApplicationInfo)PackageFrameworkAccess.physical("application",context.getPackageName(),0L,user);
        check(physicalApplication!=null&&physicalApplication.uid==android.os.Process.myUid(),"EXACT_OWN_PHYSICAL_APPLICATION_QUERY");
        Object endpoint=PackageFrameworkAccess.currentEndpoint();android.content.pm.PackageManager facade=PackageFrameworkAccess.construct(context,endpoint);
        check(facade!=context.getPackageManager()&&PackageFrameworkAccess.endpoint(facade)==endpoint,"FIXED_PACKAGE_FACADE_CONSTRUCTOR");
        check(PackageFrameworkAccess.systemContext().getPackageName().equals("android"),"EXACT_PHYSICAL_SYSTEM_CONTEXT");
        JSONObject boundaryInventory=new JSONObject();
        for(int owner:new int[]{13,15,18,24,25,26,80,81,82}){
            java.util.List<String> entries=new java.util.ArrayList<>();
            for(Object candidate:FrameworkAccess.fixedMembers(owner))if(candidate instanceof java.lang.reflect.Method){
                java.lang.reflect.Method method=(java.lang.reflect.Method)candidate;
                entries.add(method.getName()+FixedFrameworkMembers.signature(method.getParameterTypes(),method.getReturnType())+":"+Integer.toHexString(method.getModifiers()));
            }
            java.util.Collections.sort(entries);String text=String.join("\n",entries)+"\n";
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            boundaryInventory.put(FixedFrameworkMembers.owner(owner).getName(),new JSONObject().put("owner",owner).put("methods",new org.json.JSONArray(entries)).put("sha256",digest));
        }
        String wrapped=android.content.ContentResolver.wrap(provider).getClass().getName();
        android.util.Log.i("TripoderFixedStage","REMAINING_INVENTORY");JSONObject remainingInventory=OwnedRemainingInventoryChecks.run();
        android.util.Log.i("TripoderFixedStage","COMPONENT_INSTALLER");
        JSONObject componentInstall=dev.tripoder.runtime.OwnedComponentInstallerChecks.run(context,members,run);
        android.util.Log.i("TripoderFixedStage","FRAMEWORK_COMPLETE");
        return new JSONObject().put("checks",checks).put("managerFacades",10).put("ownedBroadcast",true).put("nativeLongPrecision",true)
            .put("providerHandleIdentity",true).put("wrappedResolverClass",wrapped).put("boundaryInventory",boundaryInventory).put("remainingInventory",remainingInventory).put("componentInstall",componentInstall).put("hookInstalled",true).put("productCallbacksInstalled",false).put("productAccepted",false);
    }
}
