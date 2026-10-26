package dev.tripoder.closurefixture;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.widget.TextView;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Owned semantic oracle: real broadcasts, async completion, platform requests and task roots. */
public final class Main extends Activity {
    private final JSONArray checks=new JSONArray();private TextView text;
    private android.content.res.Resources attachedResources;
    @Override protected void attachBaseContext(Context base){
        android.content.res.Resources original=base.getResources();
        attachedResources=new android.content.res.Resources(original.getAssets(),original.getDisplayMetrics(),original.getConfiguration()){
            @Override public android.graphics.drawable.Drawable getDrawable(int id,Theme theme){return original.getDrawable(id,theme);}
        };
        super.attachBaseContext(new ContextWrapper(base){@Override public android.content.res.Resources getResources(){return attachedResources;}});
    }
    private void check(String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));android.util.Log.i("TripoderClosureFixture","check="+name+";passed="+value);if(!value)throw new AssertionError(name);}
    @Override public void onCreate(Bundle state){super.onCreate(state);text=new TextView(this);text.setText("RUNNING CONTRACTS");setContentView(text);new Thread(this::inspect,"closure-fixture-checks").start();}
    private interface Section{void run()throws Exception;}
    private final JSONArray failures=new JSONArray();
    private void section(String name,Section action){try{action.run();}catch(Throwable error){android.util.Log.e("TripoderClosureFixture","section-failed:"+name,error);try{failures.put(new JSONObject().put("section",name).put("error",android.util.Log.getStackTraceString(error)));}catch(Exception ignored){}}}
    private void inspect(){JSONObject result=new JSONObject();
        section("factory-binding",()->check("same-real-package-published-before-all-eight-guest-phases",BindingChecks.phases==8));
        section("factory-seed",()->FactoryOwnerChecks.run(this,this::check));
        section("factory-argument",()->check("factory-classloader-argument-copy-cannot-mutate-canonical-binding",BindingChecks.factoryArgumentIsolated));
        section("themes",()->check("attach-and-derived-theme-own-guest-resources",ThemeApplication.themeReady));
        section("activity-resource-wrapper",()->check("activity-keeps-resources-installed-by-attachBaseContext",getResources()==attachedResources));
        section("package-context-absence",()->{
            boolean absent=false;try{createPackageContext("dev.tripoder.absent.foreign",CONTEXT_INCLUDE_CODE);}catch(android.content.pm.PackageManager.NameNotFoundException expected){absent=true;}
            check("foreign-package-context-reports-sdk-absence",absent);
        });
        section("raw-package-context",()->RawPackageContextChecks.run(this,this::check));
        section("raw-receivers",()->RawReceiverChecks.run(this,this::check));
        section("settings-namespace",()->SettingsNamespaceChecks.run(this,this::check));
        section("manual-provider-installation",()->ProviderInstallationChecks.run(this,this::check));
        section("private-documents-provider",()->PrivateDocumentsChecks.run(this,this::check));
        section("cold-provider-main-queue",()->ColdProviderChecks.run(this,this::check));
        section("bluetooth-observer",()->BluetoothObserverChecks.run(this,this::check));
        section("self-package-observer",()->SelfPackageReceiverChecks.run(this,this::check));
        section("package-suspension",()->{
            check("package-suspension-query-uses-logical-flags",getPackageManager().isPackageSuspended()==((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_SUSPENDED)!=0));
            check("named-suspension-query-has-same-owner",getPackageManager().isPackageSuspended(getPackageName())==getPackageManager().isPackageSuspended());
            boolean absent=false;try{getPackageManager().isPackageSuspended("dev.tripoder.absent.foreign");}catch(android.content.pm.PackageManager.NameNotFoundException expected){absent=true;}check("suspension-query-keeps-foreign-package-invisible",absent);
        });
        section("permissions",this::permissions);
        section("libraries",()->check("declared-platform-library-visible",Class.forName("org.apache.commons.logging.LogFactory",false,getClassLoader())!=null));
        section("library-definitions",()->PlatformLibraryChecks.run(this,this::check));
        section("resources",this::resourceInflation);section("resource-origins",()->ResourceOriginChecks.run(this,this::check));section("uri",this::uriChecks);section("broadcasts",this::broadcasts);section("protected-receivers",()->ProtectedReceiverChecks.run(this,this::check));section("context-starts",()->ContextStartChecks.run(this,this::check));
        section("delegation",this::delegation);section("active-notifications",this::notifications);section("users",this::users);section("roles",this::roles);section("service-dispatcher",this::serviceDispatcher);section("preferences",this::preferences);section("device-storage",()->{DeviceStorageChecks.run(this);check("device-storage-view-identity-derived-contexts-and-concurrent-disk-commits",true);});section("job-retirement",this::jobs);section("real-job-work",this::jobWork);section("authority-concurrency",()->{AuthorityConcurrency.run(this);check("real-system-sticky-claims-and-job-commits-finish-concurrently",true);});section("component-state",this::componentState);section("remote-process",this::remoteProcess);section("networks",this::networks);section("tasks",this::tasks);
        section("webview-resources",()->WebViewResourceChecks.run(this,this::check));
        try{result.put("passed",failures.length()==0).put("failures",failures).put("checks",checks);JSONArray failed=new JSONArray();for(int i=0;i<failures.length();i++)failed.put(failures.getJSONObject(i).getString("section"));android.util.Log.i("TripoderClosureFixture",new JSONObject().put("passed",failures.length()==0).put("checks",checks.length()).put("failedSections",failed).toString());}catch(Exception ignored){}runOnUiThread(()->text.setText(result.toString()));
    }
    private void uriChecks()throws Exception{
        Uri absent=Uri.parse("content://dev.tripoder.absent.provider/item");int pid=android.os.Process.myPid(),uid=android.os.Process.myUid();
        check("uri-explicit-ungranted-query-is-denied",checkUriPermission(absent,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_DENIED);
        check("uri-root-inspection-matches-framework",checkUriPermission(absent,pid,0,Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED);
        int[] bulk=checkUriPermissions(List.of(absent,absent),pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION);check("uri-bulk-inspection-preserves-each-result",bulk.length==2&&bulk[0]==-1&&bulk[1]==-1);
        check("uri-calling-outside-ipc-does-not-inherit-self",checkCallingUriPermission(absent,Intent.FLAG_GRANT_READ_URI_PERMISSION)==-1);
        boolean denied=false;try{enforceUriPermission(absent,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION,"owned check");}catch(SecurityException expected){denied=true;}check("uri-enforcement-throws-security-denial",denied);
    }
    private void permissions()throws Exception{
        java.io.File file=new java.io.File(getApplicationInfo().dataDir,"owned-permission-contract");
        try(var stream=new java.io.FileOutputStream(file)){stream.write(new byte[]{1,2,3});}
        check("logical-path-remove-write-permission",file.setWritable(false,false)&&!file.canWrite());
        check("logical-path-restore-owner-write-permission",file.setWritable(true,true)&&file.canWrite());
        check("permission-change-outside-guest-denied",!new java.io.File("/data/user/0/dev.tripoder.foreign/not-owned").setWritable(true,true));
    }
    private void resourceInflation()throws Exception{
        int drawable=getResources().getIdentifier("owned","drawable",getPackageName());
        check("drawable-inflation-uses-guest-defining-loader",getResources().getDrawable(drawable,getTheme()).getClass()==OwnedDrawable.class);
        Context derived=createConfigurationContext(new android.content.res.Configuration());
        check("derived-resource-inflation-keeps-guest-loader",derived.getResources().getDrawable(drawable,derived.getTheme()).getClass()==OwnedDrawable.class);
    }
    private void broadcasts()throws Exception{
        String x=getPackageName()+".X",y=getPackageName()+".Y",barrier=getPackageName()+".BARRIER";AtomicInteger count=new AtomicInteger();CountDownLatch received=new CountDownLatch(2);
        BroadcastReceiver shared=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){count.incrementAndGet();received.countDown();}};
        IntentFilter one=new IntentFilter(x);one.addDataScheme("content");one.addDataAuthority("one",null);
        IntentFilter two=new IntentFilter(y);two.addDataScheme("file");
        registerReceiver(shared,one,Context.RECEIVER_NOT_EXPORTED);registerReceiver(shared,two,Context.RECEIVER_NOT_EXPORTED);registerReceiver(shared,new IntentFilter(one),Context.RECEIVER_NOT_EXPORTED);
        try{
            sendBroadcast(new Intent(x,Uri.parse("content://one/valid")).setPackage(getPackageName()));sendBroadcast(new Intent(y,Uri.parse("file:///valid")).setPackage(getPackageName()));
            check("multiple-filters-receive-each-match",received.await(6,TimeUnit.SECONDS));
            CountDownLatch fence=new CountDownLatch(1);BroadcastReceiver end=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){fence.countDown();}};registerReceiver(end,new IntentFilter(barrier),Context.RECEIVER_NOT_EXPORTED);
            try{sendBroadcast(new Intent(x,Uri.parse("file:///cross-product")).setPackage(getPackageName()));sendBroadcast(new Intent(y,Uri.parse("content://one/cross-product")).setPackage(getPackageName()));sendBroadcast(new Intent(barrier).setPackage(getPackageName()));check("broadcast-barrier-delivered",fence.await(6,TimeUnit.SECONDS));SystemClock.sleep(150);check("overlap-deduplicated-and-no-filter-cross-product",count.get()==2);}finally{unregisterReceiver(end);}
        }finally{unregisterReceiver(shared);}
        IntentFilter narrow=new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);narrow.addDataScheme("package");narrow.addDataSchemeSpecificPart(getPackageName(),PatternMatcher.PATTERN_LITERAL);narrow.addCategory("owned.category");
        BroadcastReceiver packages=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){}};
        registerReceiver(packages,narrow,Context.RECEIVER_NOT_EXPORTED);unregisterReceiver(packages);check("protected-receiver-keeps-exact-narrow-data-filter",true);
        boolean repeat=false;try{unregisterReceiver(shared);}catch(IllegalArgumentException expected){repeat=true;}check("all-filter-entries-unregistered",repeat);
        CountDownLatch sticky=new CountDownLatch(1);AtomicInteger nested=new AtomicInteger();
        BroadcastReceiver recursive=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
            if(intent.getBooleanExtra("local",false)){nested.incrementAndGet();return;}
            onReceive(context,new Intent(intent).putExtra("local",true));PendingResult pending=goAsync();new Thread(()->{pending.finish();sticky.countDown();},"owned-async-finish").start();
        }};
        registerReceiver(recursive,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));try{check("local-onReceive-with-live-token-does-not-reclaim",sticky.await(6,TimeUnit.SECONDS)&&nested.get()==1);}finally{unregisterReceiver(recursive);}
    }
    private void componentState()throws Exception{
        android.content.pm.PackageManager pm=getPackageManager();ComponentName component=new ComponentName(this,ToggleActivity.class);
        var record=getSharedPreferences("component-state-oracle",MODE_PRIVATE);
        if(record.contains("expectedRawState"))check("component-state-survives-authority-restart-and-retained-data-upgrade",pm.getComponentEnabledSetting(component)==record.getInt("expectedRawState",-1)&&pm.getActivityInfo(component,0).enabled);
        for(ComponentName reset:List.of(new ComponentName(this,RemoteProvider.class),new ComponentName(this,ProbeProvider.class),new ComponentName(getPackageName(),getPackageName()+".DisabledAlias"),new ComponentName(this,ToggleJob.class)))pm.setComponentEnabledSetting(reset,0,android.content.pm.PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(component,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,android.content.pm.PackageManager.DONT_KILL_APP);
        boolean absent=false;try{pm.getActivityInfo(component,0);}catch(android.content.pm.PackageManager.NameNotFoundException expected){absent=true;}
        check("component-default-retains-disabled-manifest",absent);
        pm.setComponentEnabledSetting(component,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP);
        check("enabled-component-metadata-agrees-with-writer",pm.getComponentEnabledSetting(component)==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED&&pm.getActivityInfo(component,0).enabled);
        runOnUiThread(()->startActivity(new Intent(this,ToggleActivity.class)));
        check("enabled-component-is-really-admitted-and-created",ToggleActivity.entered.await(6,TimeUnit.SECONDS));
        boolean unsupported=false;try{pm.setComponentEnabledSetting(component,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,2);}catch(UnsupportedOperationException expected){unsupported=true;}
        check("unsupported-component-flags-do-not-mutate-state",unsupported&&pm.getComponentEnabledSetting(component)==1);
        ComponentName alias=new ComponentName(getPackageName(),getPackageName()+".DisabledAlias");pm.setComponentEnabledSetting(alias,1,android.content.pm.PackageManager.DONT_KILL_APP);
        runOnUiThread(()->startActivity(new Intent().setComponent(alias)));check("disabled-alias-enabled-after-canonical-keeps-real-entry-family",ToggleActivity.aliasEntered.await(6,TimeUnit.SECONDS));
        ComponentName jobService=new ComponentName(this,ToggleJob.class);pm.setComponentEnabledSetting(jobService,1,android.content.pm.PackageManager.DONT_KILL_APP);
        var scheduler=getSystemService(android.app.job.JobScheduler.class).forNamespace("owned-toggle");var delayed=new android.app.job.JobInfo.Builder(903,jobService).setMinimumLatency(3600000).build();
        check("enabled-disabled-manifest-job-service-is-really-scheduled",scheduler.schedule(delayed)==android.app.job.JobScheduler.RESULT_SUCCESS&&scheduler.getPendingJob(903).getService().equals(jobService));
        ComponentName provider=new ComponentName(this,ProbeProvider.class);pm.setComponentEnabledSetting(provider,1,android.content.pm.PackageManager.DONT_KILL_APP);
        try(ContentProviderClient client=getContentResolver().acquireContentProviderClient(Uri.parse("content://"+getPackageName()+".enabled"))){
            check("dynamic-provider-real-onCreate-once",client!=null&&client.call("probe",null,null).getInt("creates")==1);
            pm.setComponentEnabledSetting(provider,2,android.content.pm.PackageManager.DONT_KILL_APP);
            check("provider-disable-is-visible-to-metadata",pm.resolveContentProvider(getPackageName()+".enabled",0)==null);
            check("provider-disable-keeps-already-acquired-handle",client.call("probe",null,null).getInt("creates")==1);
            pm.setComponentEnabledSetting(provider,1,android.content.pm.PackageManager.DONT_KILL_APP);
            check("provider-reenable-reuses-existing-instance",client.call("probe",null,null).getInt("creates")==1);
        }
        pm.setComponentEnabledSetting(provider,0,android.content.pm.PackageManager.DONT_KILL_APP);
        check("component-setting-persistence-marker-committed",record.edit().putBoolean("committed",true).putInt("expectedRawState",1).commit());
    }
    private void jobWork()throws Exception{
        var scheduler=getSystemService(android.app.job.JobScheduler.class).forNamespace("owned-work-queue");
        PersistableBundle extras=new PersistableBundle();extras.putString("kind","owned-work");
        var job=new android.app.job.JobInfo.Builder(907,new ComponentName(this,QueuedJob.class)).setMinimumLatency(2000).setOverrideDeadline(2000).setExtras(extras).build();
        check("enqueue-first-real-work-item",scheduler.enqueue(job,new android.app.job.JobWorkItem(new Intent("owned-queue").putExtra("sequence",1)))==android.app.job.JobScheduler.RESULT_SUCCESS);
        check("enqueue-second-real-work-item",scheduler.enqueue(job,new android.app.job.JobWorkItem(new Intent("owned-queue").putExtra("sequence",2)))==android.app.job.JobScheduler.RESULT_SUCCESS);
        check("real-queue-dequeues-and-completes-first-batch",QueuedJob.first.await(10,TimeUnit.SECONDS)&&QueuedJob.error==null);
        // jobFinished posts to the real main-thread engine. Its Java return is
        // not the system ACK; observe removal before asking for a new attempt.
        long completedDeadline=SystemClock.elapsedRealtime()+5000;
        while(scheduler.getPendingJob(907)!=null&&SystemClock.elapsedRealtime()<completedDeadline)Thread.sleep(20);
        check("real-platform-confirms-first-attempt-no-longer-pending",scheduler.getPendingJob(907)==null);
        check("enqueue-after-real-finished-attempt",scheduler.enqueue(job,new android.app.job.JobWorkItem(new Intent("owned-queue").putExtra("sequence",3)))==android.app.job.JobScheduler.RESULT_SUCCESS);
        check("real-queue-new-attempt-retains-each-payload-once",QueuedJob.all.await(10,TimeUnit.SECONDS)&&QueuedJob.error==null&&QueuedJob.sequences.equals(Set.of(1,2,3))&&QueuedJob.deliveryCounts);
        check("work-completion-rejects-forged-duplicate-and-finished-capability",QueuedJob.forgedDenied&&QueuedJob.duplicateDenied&&QueuedJob.finishedDenied);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        runOnUiThread(()->{blocked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        check("real-main-thread-start-delivery-is-queued",blocked.await(2,TimeUnit.SECONDS));
        try{
            for(int sequence=1;sequence<=2;sequence++){PersistableBundle version=new PersistableBundle();version.putInt("sequence",sequence);var replacement=new android.app.job.JobInfo.Builder(908,new ComponentName(this,ReplacedJob.class)).setOverrideDeadline(0).setExtras(version).build();check("real-job-replacement-accepted-"+sequence,scheduler.schedule(replacement)==android.app.job.JobScheduler.RESULT_SUCCESS);}
        }finally{release.countDown();}
        check("fenced-older-start-does-not-enter-guest-or-break-late-stop",ReplacedJob.latest.await(8,TimeUnit.SECONDS)&&ReplacedJob.exact);
    }
    private void jobs()throws Exception{
        var scheduler=getSystemService(android.app.job.JobScheduler.class).forNamespace("owned-delayed");
        var job=new android.app.job.JobInfo.Builder(901,new ComponentName(this,OwnedJob.class)).setMinimumLatency(3600000).build();
        check("real-job-schedule-accepted",scheduler.schedule(job)==android.app.job.JobScheduler.RESULT_SUCCESS);
        var pending=scheduler.getPendingJob(901);check("pending-job-retains-logical-component",pending!=null&&pending.getService().equals(job.getService()));
        var persisted=new android.app.job.JobInfo.Builder(904,new ComponentName(this,OwnedJob.class)).setMinimumLatency(3600000).setPersisted(true).build();
        check("persisted-job-has-real-platform-admission-and-logical-view",scheduler.schedule(persisted)==android.app.job.JobScheduler.RESULT_SUCCESS&&scheduler.getPendingJob(904).isPersisted()&&scheduler.getPendingJob(904).getService().equals(persisted.getService()));
        PersistableBundle extras=new PersistableBundle();extras.putString("kind","expedited-owned");
        var urgent=new android.app.job.JobInfo.Builder(902,new ComponentName(this,OwnedJob.class)).setExpedited(true).setExtras(extras).build();
        check("expedited-schedule-retains-real-platform-admission",scheduler.schedule(urgent)==android.app.job.JobScheduler.RESULT_SUCCESS);
        check("expedited-real-service-receives-exact-flags-and-logical-extras",OwnedJob.expedited.await(10,TimeUnit.SECONDS)&&OwnedJob.exact);
        // The paired harness stops this installation and observes the real platform
        // namespace afterwards; leave the delayed Job pending to exercise that path.
    }
    private void delegation()throws Exception{
        NotificationManager manager=getSystemService(NotificationManager.class);manager.setNotificationDelegate(getPackageName());check("delegate-roundtrip-self",getPackageName().equals(manager.getNotificationDelegate()));
        manager.setNotificationDelegate("dev.tripoder.absent.delegate");check("absent-delegate-preserves-record",getPackageName().equals(manager.getNotificationDelegate()));
        manager.setNotificationDelegate(null);check("delegate-revoke-persists",manager.getNotificationDelegate()==null);
        check("notification-posting-authority-scoped",manager.canNotifyAsPackage(getPackageName())&&!manager.canNotifyAsPackage("dev.tripoder.absent.delegate"));
    }
    private void notifications()throws Exception{
        android.util.Log.i("TripoderClosureFixture","policy-access-observed="+getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted());
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("owned-active","Owned active query",NotificationManager.IMPORTANCE_LOW));
        Notification notification=new Notification.Builder(this,"owned-active").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Owned query oracle").build();
        try{
            manager.notify(null,905,notification);manager.notify("",905,notification);manager.notify("owned",906,notification);
            android.service.notification.StatusBarNotification[] active;long until=SystemClock.elapsedRealtime()+5000;boolean all;
            do{active=manager.getActiveNotifications();boolean one=false,two=false,three=false;for(var item:active){one|=item.getId()==905&&item.getTag()==null;two|=item.getId()==905&&"".equals(item.getTag());three|=item.getId()==906&&"owned".equals(item.getTag());}all=one&&two&&three;if(!all)SystemClock.sleep(50);}while(!all&&SystemClock.elapsedRealtime()<until);
            check("active-notifications-observe-three-real-distinct-tags",all);
            boolean scoped=true;for(var item:active)scoped&=item.getPackageName().equals(getPackageName())&&item.getUid()==android.os.Process.myUid()&&item.getPostTime()>0&&"owned-active".equals(item.getNotification().getChannelId())&&item.getId()!=999401;
            check("active-notifications-retain-real-post-and-exclude-host-witness",scoped);
        }finally{manager.cancel(null,905);manager.cancel("",905);manager.cancel("owned",906);}
    }
    private void users()throws Exception{
        UserManager manager=getSystemService(UserManager.class);Bundle absent=manager.getApplicationRestrictions(getPackageName());
        check("own-app-restriction-query-is-empty-without-issuer",absent!=null&&absent.isEmpty());
        absent.putString("caller-local","value");check("restriction-query-does-not-share-mutable-answer",manager.getApplicationRestrictions(getPackageName()).isEmpty());
        boolean denied=false;try{manager.getApplicationRestrictions("dev.tripoder.absent.foreign");}catch(SecurityException expected){denied=true;}
        check("restriction-query-cannot-read-another-app",denied);
        check("device-user-unlock-query-retains-real-service",manager.isUserUnlocked());
    }
    private void roles()throws Exception{
        android.app.role.RoleManager manager=getSystemService(android.app.role.RoleManager.class);
        check("own-role-held-query-is-false-without-controller",!manager.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER));
        check("device-role-availability-retains-real-service",manager.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER));
    }
    private void serviceDispatcher()throws Exception{
        AtomicInteger callbacks=new AtomicInteger();ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){callbacks.incrementAndGet();}public void onServiceDisconnected(ComponentName name){callbacks.incrementAndGet();}};
        boolean bound=bindService(new Intent("dev.tripoder.absent.SERVICE").setPackage("dev.tripoder.absent.package"),connection,Context.BIND_AUTO_CREATE);
        check("missing-service-bind-is-false-without-callback",!bound&&callbacks.get()==0);
        check("repeat-false-bind-does-not-invent-success",!bindService(new Intent("dev.tripoder.absent.SERVICE").setPackage("dev.tripoder.absent.package"),connection,Context.BIND_AUTO_CREATE));
        unbindService(connection);check("false-bind-still-forgets-one-local-dispatcher",true);
        boolean duplicate=false;try{unbindService(connection);}catch(IllegalArgumentException expected){duplicate=true;}check("forgotten-service-dispatcher-rejects-second-unbind",duplicate);
    }
    static final String LONG_STRING="large\u0000\u0001\ud83e\uddf6".repeat(16000);
    private static final String BINARY_STRING="begin\u0000\u0001\u0008\u001f\n\ud83e\uddf6end";
    private void preferences()throws Exception{
        android.content.SharedPreferences nullName=getSharedPreferences(null,MODE_PRIVATE),literalNull=getSharedPreferences("null",MODE_PRIVATE),emptyName=getSharedPreferences("",MODE_PRIVATE);
        check("preferences-null-name-uses-platform-null-file",nullName==literalNull&&nullName.edit().putString("name-contract","null-value").commit());
        check("preferences-empty-name-has-distinct-real-file",emptyName!=nullName&&emptyName.edit().putString("name-contract","empty-value").commit());
        var preferences=getSharedPreferences("platform-string-values",MODE_PRIVATE);
        check("preferences-commit-preserves-nul-and-control-characters",preferences.edit().putString("nul\u0000key",BINARY_STRING).putStringSet("set",Set.of(BINARY_STRING,"ordinary")).putString("large",LONG_STRING).commit());
        check("preferences-memory-preserves-exact-java-string",BINARY_STRING.equals(preferences.getString("nul\u0000key",null)));
        check("preferences-string-is-not-limited-to-64k-token",LONG_STRING.equals(preferences.getString("large",null)));
    }
    private void remoteProcess()throws Exception{
        CountDownLatch reply=new CountDownLatch(1);AtomicReference<Bundle> received=new AtomicReference<>();
        Messenger returning=new Messenger(new Handler(Looper.getMainLooper(),message->{received.set(message.getData());reply.countDown();return true;}));
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){Message request=Message.obtain();request.replyTo=returning;try{new Messenger(binder).send(request);}catch(RemoteException error){Bundle failed=new Bundle();failed.putString("error",error.toString());received.set(failed);reply.countDown();}}public void onServiceDisconnected(ComponentName name){}};
        boolean bound=bindService(new Intent(this,RemoteService.class),connection,Context.BIND_AUTO_CREATE);check("explicit-application-start-can-bind-declared-remote-process",bound);
        try{
            check("remote-process-real-binder-reply",reply.await(15,TimeUnit.SECONDS));Bundle data=received.get();
            check("remote-process-is-distinct-and-no-fabricated-value",data!=null&&!data.containsKey("error")&&data.getInt("pid")!=android.os.Process.myPid());
            check("preferences-disk-roundtrip-retains-control-characters",BINARY_STRING.equals(data.getString("value"))&&new HashSet<>(data.getStringArrayList("set")).equals(Set.of(BINARY_STRING,"ordinary")));
            check("preferences-large-string-disk-roundtrip-exact",data.getBoolean("largeExact"));
            check("preferences-null-and-empty-names-disk-roundtrip",data.getBoolean("namesExact"));
            check("device-storage-values-retained-in-real-second-process",data.getBoolean("deviceStorageExact"));
            check("component-setting-and-enabled-metadata-visible-in-second-process",data.getBoolean("componentExact"));
            // Keep the real :remote Service bound beyond the original startup
            // budget, then initialize a newly enabled provider in that same PID.
            SystemClock.sleep(32000);getPackageManager().setComponentEnabledSetting(new ComponentName(this,RemoteProvider.class),1,android.content.pm.PackageManager.DONT_KILL_APP);
            Bundle remote=getContentResolver().call(Uri.parse("content://"+getPackageName()+".remote-enabled"),"probe",null,null);
            check("late-dynamic-provider-uses-existing-remote-process-and-single-init",remote!=null&&remote.getInt("pid")==data.getInt("pid")&&remote.getInt("creates")==1);
            getPackageManager().setComponentEnabledSetting(new ComponentName(this,RemoteProvider.class),0,android.content.pm.PackageManager.DONT_KILL_APP);
        }finally{unbindService(connection);}
    }
    private void networks()throws Exception{
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);CountDownLatch event=new CountDownLatch(1);AtomicReference<String> kind=new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network network){kind.set("available");event.countDown();}public void onUnavailable(){kind.set("unavailable");event.countDown();}};
        manager.requestNetwork(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),callback,5000);
        try{check("active-network-request-real-platform-result",event.await(7,TimeUnit.SECONDS)&&kind.get()!=null);}finally{manager.unregisterNetworkCallback(callback);}
    }
    private void tasks()throws Exception{
        PerTask.reset();PendingIntent capability=PendingIntent.getActivity(this,317,new Intent(this,PerTask.class),PendingIntent.FLAG_IMMUTABLE);
        PerTask.expected=capability;
        runOnUiThread(()->startActivity(new Intent(this,PerTask.class).putExtra("owned-token",capability).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        check("mode4-first-task-created",PerTask.first.await(6,TimeUnit.SECONDS));
        check("activity-pending-token-keeps-binder-identity",PerTask.tokenRoundtrip);
        runOnUiThread(()->startActivity(new Intent(this,PerTask.class).putExtra("owned-token",capability).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        check("mode4-second-task-created",PerTask.second.await(6,TimeUnit.SECONDS));
        check("mode4-distinct-instances-and-task-roots",PerTask.instances.size()==2&&PerTask.tasks.size()==2);
        capability.cancel();
        runOnUiThread(()->{for(PerTask activity:PerTask.instances)activity.finishAndRemoveTask();});
    }
}
