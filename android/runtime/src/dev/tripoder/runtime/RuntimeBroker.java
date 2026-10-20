package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;

/** One authority in the controller process. Identity is derived from registered Binder peers. */
public final class RuntimeBroker extends RuntimeWire.Endpoint {
    public final Context host;
    public final RuntimeStore store;
    public final ApkStore apks;
    public final RuntimeCatalog catalog;
    public final ProjectBindings projects;
    public final ProcessSupervisor processes;
    /** Binder extras of broadcasts in flight, by delivery ticket. Memory only: a Binder cannot outlive the processes that hold it. */
    final java.util.Map<String,Bundle> broadcastBinders=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,Bundle>(){@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Bundle> eldest){return size()>256;}});
    final ActivityLedger activities;
    private final ActivityStartReceipts activityStarts;
    final JobLedger jobs;
    final PendingIntentLedger pendingIntents;
    final RuntimePendingIntentBroker pendingIntentBroker;
    final RuntimeJobBroker jobBroker;
    final JobAttemptLedger jobAttempts;
    final ServiceLedger services;
    private final RuntimeServiceBroker serviceBroker;
    final ProviderLedger providers;
    private final RuntimeProviderBroker providerBroker;
    final BroadcastLedger broadcasts;
    final RuntimeBroadcastBroker broadcastBroker;
    private final ApplicationStartLedger applicationStarts;
    public ExecutionContract contract;
    final RuntimeHookClaims hookClaims=new RuntimeHookClaims(this);
    final RuntimeProbePlans probePlans=new RuntimeProbePlans();
    private final Map<String,Slot> slots=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Delivery> deliveries=new HashMap<>();
    private static final class Delivery {final String source,target,sourceTicket;boolean authorized;String receivingInstance;Delivery(String s,String t,String ticket){source=s;target=t;sourceTicket=ticket;}}
    public final JSONArray events=new JSONArray();
    public volatile String lastCallback,lastOperation;
    private long BOOT_MARK;
    private void bootStep(String name){long now=android.os.SystemClock.elapsedRealtime();if(now-BOOT_MARK>200)android.util.Log.w("TripoderBoot","SLOW_RECOVER_STEP "+name+" ms="+(now-BOOT_MARK));}
    public RuntimeBroker(Context context){
        host=context;store=new RuntimeStore(context);var activityComponents=new ActivityComponentLedger(store,context);catalog=new RuntimeCatalog(store,activityComponents);contract=new ExecutionContract(store);apks=new ApkStore(context,store,catalog,contract,this);activities=new ActivityLedger(store,contract,activityComponents);services=new ServiceLedger(store);serviceBroker=new RuntimeServiceBroker(this);
        pendingIntents=new PendingIntentLedger(store);pendingIntentBroker=new RuntimePendingIntentBroker(this);
        jobs=new JobLedger(store);jobAttempts=new JobAttemptLedger(this);jobBroker=new RuntimeJobBroker(this);
        providers=new ProviderLedger(store);providerBroker=new RuntimeProviderBroker(this);projects=new ProjectBindings(context,store,catalog,apks,contract);
        broadcasts=new BroadcastLedger(store);broadcastBroker=new RuntimeBroadcastBroker(this);
        try{applicationStarts=new ApplicationStartLedger(store,context);BOOT_MARK=android.os.SystemClock.elapsedRealtime();store.recoverSessions(context);bootStep("store.recoverSessions(context)");BOOT_MARK=android.os.SystemClock.elapsedRealtime();contract.onRecover();bootStep("contract.onRecover()");BOOT_MARK=android.os.SystemClock.elapsedRealtime();jobs.recovered();bootStep("jobs.recovered()");BOOT_MARK=android.os.SystemClock.elapsedRealtime();JobAttemptLedger.recover(store.getWritableDatabase());bootStep("JobAttemptLedger.recover(store.getWritab");BOOT_MARK=android.os.SystemClock.elapsedRealtime();jobBroker.reconcileSchedules();bootStep("jobBroker.reconcileSchedules()");BOOT_MARK=android.os.SystemClock.elapsedRealtime();pendingIntentBroker.retireDirectTransports();bootStep("retireDirectTransports()");BOOT_MARK=android.os.SystemClock.elapsedRealtime();providers.recover(contract);bootStep("providers.recover(contract)");BOOT_MARK=android.os.SystemClock.elapsedRealtime();broadcasts.recover(contract);bootStep("broadcasts.recover(contract)");BOOT_MARK=android.os.SystemClock.elapsedRealtime();activities.recover(context);bootStep("activities.recover(context)");BOOT_MARK=android.os.SystemClock.elapsedRealtime();services.reconcile(context);bootStep("services.reconcile(context)");BOOT_MARK=android.os.SystemClock.elapsedRealtime();apks.recover();bootStep("apks.recover()");BOOT_MARK=android.os.SystemClock.elapsedRealtime();projects.recover();bootStep("projects.recover()");}
        catch(Exception error){store.close();throw new IllegalStateException("AUTHORITY_RECOVERY_FAILED",error);}
        activityStarts=new ActivityStartReceipts(store,context,activities,this);processes=new ProcessSupervisor(this);
    }
    public static final class Slot {
        public final ApkStore.BundleInstall install;public final ExecutionContract.Allocation allocation;public final String activityStub;public final RuntimeMode mode;public IBinder peer;boolean closing;
        final long startupDeadlineElapsed=RuntimeStartupDeadline.after(SystemClock.elapsedRealtime());
        String probeConfiguration;
        Slot(ApkStore.BundleInstall i,ExecutionContract.Allocation a,String stub,RuntimeMode m){install=i;allocation=a;activityStub=stub;mode=m;}
    }
    public synchronized Bundle allocate(ApkStore.BundleInstall install,String processName,String stub,IBinder peer)throws Exception {
        return processes.claimComponent(reserve(install,processName,stub),peer);
    }
    synchronized Slot reserve(ApkStore.BundleInstall install,String processName,String stub)throws Exception{
        var current=apks.get(install.space,install.info.packageName);
        if(!current.generation.equals(install.generation)||!current.data.equals(install.data))throw new IllegalStateException("STALE_INSTALL");
        current.wire();
        if(!install.artifacts.processes.contains(processName))throw new IllegalArgumentException("PROCESS_NOT_DECLARED");
        String retained=retainedStub(install,processName);
        if(providers.occupied(stub))throw new IllegalStateException("PROCESS_SLOT_RETAINED_BY_PROVIDER");
        if(broadcasts.occupied(stub))throw new IllegalStateException("PROCESS_SLOT_RETAINED_BY_BROADCAST");
        if(retained!=null&&!stub.equals(retained)||activities.processPinnedByOther(stub,install,processName)||services.occupiedByOther(stub,install,processName)||jobs.components.occupiedByOther(stub,install,processName))throw new IllegalStateException("PROCESS_SLOT_RETAINED_BY_TASK");
        for(Slot old:slots.values()){
            if(old.activityStub.equals(stub))throw new IllegalStateException("PROCESS_SLOT_IN_USE");
            if(old.install.space.equals(install.space)&&old.install.info.packageName.equals(install.info.packageName)&&old.allocation.session.identity.processName.equals(processName))throw new IllegalStateException("PROCESS_ALREADY_ALLOCATED");
        }
        RuntimeMode mode=resolveMode(install.space,install.info.packageName,install.generation);
        ExecutionContract.Allocation a=contract.allocate(install.space,install.info.packageName,install.generation,processName,android.os.Process.myUid());
        Slot slot=new Slot(install,a,stub,mode);slot.probeConfiguration=probePlans.forProcess(this,install.space,install.info.packageName,install.generation,processName);var db=store.getWritableDatabase();db.beginTransaction();try{store.reserve(a.session,stub);providers.reserve(slot);db.setTransactionSuccessful();}finally{db.endTransaction();}
        slots.put(a.session.processGeneration,slot);return slot;
    }
    RuntimeMode resolveMode(String space,String pkg,String generation){
        List<String> modes=new ArrayList<>();
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT mode FROM project_refs WHERE space=? AND package=? AND generation=? AND kind='DIRECT' AND state='COMMITTED' ORDER BY id",new String[]{space,pkg,generation})){while(c.moveToNext())modes.add(c.getString(0));}
        return RuntimeMode.resolve(modes);
    }
    synchronized Bundle attachPeer(Slot slot,IBinder peer)throws Exception{
        String generation=slot.allocation.session.processGeneration;
        if(requiredSlot(generation)!=slot||slot.peer!=null)throw new IllegalStateException("PROCESS_PEER_ALREADY_BOUND");
        // ProcessSupervisor owns the sole death observation and its drain ACK.
        // A second listener here can write after that ACK let authority close.
        slot.peer=peer;
        Bundle input=slot.install.wire();input.putString(RuntimeMode.KEY,slot.mode.wire);if(slot.probeConfiguration!=null)input.putString("codeProbeConfiguration",slot.probeConfiguration);input.putLong("startupDeadlineElapsed",slot.startupDeadlineElapsed);input.putBundle("providers",providers.publications(generation));input.putString("generation",generation);input.putString("processName",slot.allocation.session.identity.processName);input.putString("bootstrap",slot.allocation.bootstrapToken);input.putBinder("broker",this);return input;
    }
    String retainedStub(ApkStore.BundleInstall install,String process){
        String retained=null;
        for(String stub:new String[]{activities.retainedStub(install.space,install.info.packageName,install.generation,process),services.retainedStub(install.space,install.info.packageName,install.generation,process),jobs.components.retainedStub(install.space,install.info.packageName,install.generation,process)}){
            if(stub!=null){if(retained!=null&&!retained.equals(stub))throw new IllegalStateException("COMPONENT_PROCESS_PIN_CONFLICT");retained=stub;}
        }return retained;
    }
    synchronized Slot findProcess(String space,String pkg,String process){for(Slot s:slots.values())if(s.install.space.equals(space)&&s.install.info.packageName.equals(pkg)&&s.allocation.session.identity.processName.equals(process))return s;return null;}
    synchronized boolean stubUsed(String stub){for(Slot s:slots.values())if(s.activityStub.equals(stub))return true;return false;}
    synchronized List<Slot> processSlots(){return new ArrayList<>(slots.values());}
    synchronized Bundle processEvidence(Slot slot){var s=contract.getSession(slot.allocation.session.processGeneration);Bundle b=new Bundle();b.putString("generation",s.processGeneration);b.putString("installGeneration",s.identity.installGeneration);b.putString("processName",s.identity.processName);b.putString("mode",slot.mode.wire);b.putString("state",s.state.name());b.putInt("pid",s.pid);b.putInt("uid",s.uid);return b;}
    public synchronized void dead(String generation){jobAttempts.dead(generation);hookClaims.dead(generation);contract.markDead(generation);store.deadSession(generation);activities.launches.dead(generation);providers.dead(generation,contract);providerBroker.dead(generation);broadcasts.dead(generation,contract);slots.remove(generation);for(var e:deliveries.entrySet())if(e.getValue().source.equals(generation)||e.getValue().target.equals(generation)){var operation=contract.query(e.getKey());if(operation.state==ExecutionContract.OperationState.RUNNING){contract.markUnknown(e.getKey(),"PROCESS_DIED_BEFORE_DELIVERY_ACK");trace("delivery-unknown",e.getKey());}}}
    private void trace(String stage,String op){try{synchronized(events){events.put(new JSONObject().put("stage",stage).put("operation",op).put("operationState",contract.query(op).state.name()).put("time",System.currentTimeMillis()));}}catch(JSONException e){throw new IllegalStateException(e);}}
    public synchronized void remove(String space,String pkg){for(Slot s:slots.values())if(s.install.space.equals(space)&&s.install.info.packageName.equals(pkg))throw new IllegalStateException("INSTALL_IN_USE");apks.removeRecord(space,pkg);}
    public synchronized String generation(String space,String pkg){for(var e:slots.entrySet())if(e.getValue().install.space.equals(space)&&e.getValue().install.info.packageName.equals(pkg))return e.getKey();throw new IllegalStateException("PROCESS_NOT_REGISTERED");}
    public synchronized Intent launch(String generation){Slot s=requiredSlot(generation);return launch(generation,s.install.info.activities[0].name);}
    public synchronized void completeActivity(String ticket){activities.controllerResultDelivered(ticket);}
    public synchronized Intent launch(String generation,String component){Slot s=requiredSlot(generation);var activity=RuntimeComponents.activity(s.install,component);if(!activity.processName.equals(s.allocation.session.identity.processName))throw new IllegalArgumentException("COMPONENT_PROCESS_MISMATCH");String ticket=activities.prepare(s,component,null,null,host.getPackageName(),null,null);return new Intent().setComponent(new ComponentName(host,activities.get(ticket).stub)).putExtra("tripoder.component",component).putExtra("tripoder.generation",generation).putExtra("tripoder.ticket",ticket).putExtra("tripoder.delivery",activities.launches.forTicket(ticket));}
    Slot requiredSlot(String gen){Slot slot=slots.get(gen);if(slot==null)throw new IllegalStateException("STALE_SESSION");if(slot.closing)throw new IllegalStateException("PROCESS_CLOSING");store.requireOpen(gen);return slot;}
    /** Completion is not admission. Callers still authenticate, and each ledger checks its exact resource. */
    Slot slotForReceipt(String generation){
        var session=contract.getSession(generation);
        if(session.state!=ExecutionContract.ProcessState.ATTACHED&&session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("RECEIPT_SESSION_INACTIVE");
        Slot slot=slots.get(generation);if(slot==null)throw new IllegalStateException("STALE_SESSION");return slot;
    }
    Slot slotForTermination(String gen){Slot slot=slots.get(gen);if(slot==null)throw new IllegalStateException("STALE_SESSION");return slot;}
    @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception {
        if(code==RuntimeWire.PENDING_INTENT)return pendingIntentBroker.route(input,pid,uid);
        if(code==RuntimeWire.EXIT_HISTORY)return RuntimeExitHistory.query(this,input,pid,uid);
        if(code==RuntimeWire.PERMISSION_CONTROLLER)return RuntimePermissionController.query(this,input,pid,uid);
        if(code==RuntimeWire.JOB_ROUTE)return jobBroker.route(input,pid,uid);
        if(code==RuntimeWire.PROCESS_VIEW)return RuntimeProcessView.query(this,input,pid,uid);
        if(code==RuntimeWire.ROUTE)return routeActivity(input,pid,uid);
        if(code==RuntimeWire.SERVICE_ROUTE)return serviceBroker.route(input,pid,uid);
        if(code==RuntimeWire.PROVIDER_ACQUIRE)return providerBroker.acquire(input,pid,uid);
        if(code>=RuntimeWire.BROADCAST_REGISTER&&code<=RuntimeWire.BROADCAST_EVENT||code==RuntimeWire.BROADCAST_SYSTEM_CLAIM||code==RuntimeWire.BROADCAST_STICKY_VIEW)return routeBroadcastOperation(code,input,pid,uid);
        if(code>=RuntimeWire.PROVIDER_PUBLICATION&&code<=RuntimeWire.PROVIDER_WAIT||code==RuntimeWire.PROVIDER_CURSOR_ACCESS||code==RuntimeWire.PROVIDER_RECEIVED)return routeProvider(code,input,pid,uid);
        if(code==RuntimeWire.EVENT)return routeProcessEvent(input,pid,uid);
        if(code==RuntimeWire.APPLICATION_START_EVENT||code==RuntimeWire.APPLICATION_START_HISTORY)return routeApplicationStart(code,input,pid,uid);
        synchronized(this){return handleLocked(code,input,pid,uid);}
    }
    private Bundle routeBroadcast(Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        String generation=RuntimeWire.required(input,"generation");
        var session=contract.authenticate(generation,pid,uid);
        return broadcastBroker.route(input,session);
    }
    private Bundle routeBroadcastOperation(int code,Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        if(code==RuntimeWire.BROADCAST_ROUTE)return routeBroadcast(input,pid,uid);
        String generation=RuntimeWire.required(input,"generation");
        contract.authenticate(generation,pid,uid);
        return broadcastBroker.handle(code,input,generation);
    }
    private Bundle routeProvider(int code,Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        String generation=RuntimeWire.required(input,"generation");
        contract.authenticate(generation,pid,uid);
        return providerBroker.handle(code,input,generation);
    }
    private Bundle routeProcessEvent(Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        String generation=RuntimeWire.required(input,"generation");
        var session=contract.authenticate(generation,pid,uid);
        String stage=RuntimeWire.required(input,"stage");
        if(!Set.of("adapter-ready","application-ready").contains(stage))throw new SecurityException("PROCESS_EVENT_UNSUPPORTED");
        if(stage.equals("application-ready"))store.session(contract.markRunning(generation,pid,uid));
        processes.event(generation,stage);
        synchronized(events){events.put(new JSONObject().put("stage",stage).put("session",generation).put("space",session.identity.spaceId).put("package",session.identity.packageName).put("pid",pid).put("uid",uid).put("time",System.currentTimeMillis()));}
        return new Bundle();
    }
    private Bundle routeApplicationStart(int code,Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        String generation=RuntimeWire.required(input,"generation");
        var session=contract.authenticate(generation,pid,uid);
        if(code==RuntimeWire.APPLICATION_START_EVENT){
            applicationStarts.event(session,input);
            return new Bundle();
        }
        return applicationStarts.query(session,input);
    }
    private Bundle handleLocked(int code,Bundle input,int pid,int uid)throws Exception {
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        Bundle result=new Bundle();
        if(code==RuntimeWire.REGISTER){String gen=RuntimeWire.required(input,"generation"),token=RuntimeWire.required(input,"bootstrap");if(!requiredSlot(gen).allocation.bootstrapToken.equals(token))throw new SecurityException("BOOTSTRAP_SESSION_MISMATCH");var s=contract.attach(token,pid,uid);store.session(s);result.putString("generation",s.processGeneration);return result;}
        String generation=RuntimeWire.required(input,"generation");var session=contract.authenticate(generation,pid,uid);
        if(code==RuntimeWire.CODE_ARTIFACT_DIRECTORY){
            if(input.size()!=1)throw new IllegalArgumentException("ARTIFACT_DIRECTORY_REQUEST_FIELDS");
            java.io.File root=new java.io.File(host.getFilesDir(),"code-artifacts"),directory=new java.io.File(root,RuntimeFiles.id(generation));
            if(java.nio.file.Files.isSymbolicLink(root.toPath())||java.nio.file.Files.isSymbolicLink(directory.toPath()))throw new SecurityException("ARTIFACT_DIRECTORY_SYMLINK");
            if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("ARTIFACT_DIRECTORY_CREATE");
            result.putParcelable("directory",android.os.ParcelFileDescriptor.open(directory,android.os.ParcelFileDescriptor.MODE_READ_ONLY));return result;
        }
        Slot from=RuntimeCompletion.isReceipt(code,input)?slotForReceipt(generation):requiredSlot(generation);
        if(code==RuntimeWire.JOB_BEGIN)return jobBroker.beginAttempt(input,from);
        if(code==RuntimeWire.JOB_EVENT)return jobBroker.attemptEvent(input,from);
        switch(code){
            case RuntimeWire.ACTIVITY_ENTRY_STATUS:{String candidate=RuntimeWire.required(input,"candidate"),kind=input.getString("kind","new-intent");if(!Set.of("factory","new-intent").contains(kind))throw new SecurityException("ACTIVITY_ENTRY_KIND_INVALID");boolean closed=activities.closedEntry(candidate,RuntimeWire.required(input,"stub"),from);if(closed)ActivityEntryFences.rejected(this,candidate,kind);result.putBoolean("closed",closed);return result;}
            case RuntimeWire.ACTIVITY_RECORD:{
                // A launch record is identified by its lease, not by the component name a platform may rewrite on the
                // intent it serialises (HyperOS resolves the record's visible component back from the ActivityInfo it
                // was given). The lease names the slot this generation owns; only that generation may ask.
                String ticket=RuntimeWire.required(input,"ticket");var row=activities.get(ticket);
                if(!row.generation.equals(generation))throw new SecurityException("ACTIVITY_RECORD_FOREIGN_GENERATION");
                result.putString("stub",row.stub);result.putString("component",row.component);break;
            }
            case RuntimeWire.SERVICE_ACK:return serviceBroker.acknowledge(input,generation);
            case RuntimeWire.SERVICE_CLAIM:return services.claim(RuntimeWire.required(input,"stub"),from,RuntimeWire.required(input,"instance"));
            case RuntimeWire.SERVICE_EVENT:services.lifecycle(RuntimeWire.required(input,"lease"),generation,RuntimeWire.required(input,"instance"),RuntimeWire.required(input,"event"));break;
            case RuntimeWire.ACTIVITY_CLAIM:{
                String ticket=RuntimeWire.required(input,"ticket");var prior=activities.get(ticket);
                if(activities.closedEntry(ticket,RuntimeWire.required(input,"stub"),from)){ActivityEntryFences.rejected(this,ticket,"factory");result.putString("rejectedActivity",ActivityEntryFences.REJECTED);return result;}
                if(!prior.generation.equals(generation)&&store.hookRecoveryBlocked(prior.generation)){if(!prior.stub.equals(input.getString("stub")))throw new SecurityException("ACTIVITY_STUB_MISMATCH");result.putString("rejectedActivity","HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START");return result;}
                if(!prior.generation.equals(generation)){var restorable=activities.restore(ticket,RuntimeWire.required(input,"stub"));activities.rebind(restorable,from);}
                activities.claim(ticket,from,RuntimeWire.required(input,"component"),RuntimeWire.required(input,"instance"),RuntimeWire.required(input,"stub"),RuntimeWire.required(input,"delivery"),input.getString("payloadDigest"));activities.caller(input.getString("delivery"),result);break;
            }
            case RuntimeWire.ACTIVITY_RESUMED:activities.source(RuntimeWire.required(input,"ticket"),RuntimeWire.required(input,"instance"),from,input.getInt("taskId",-1));activityStarts.reconcile(generation);break;
            case RuntimeWire.ACTIVITY_START_RESULT:activityStarts.record(input,from);break;
            case RuntimeWire.ACTIVITY_CREATED:activities.created(RuntimeWire.required(input,"ticket"),generation,RuntimeWire.required(input,"instance"),input.getInt("taskId",-1));break;
            case RuntimeWire.ACTIVITY_ATTACHED:activities.attached(RuntimeWire.required(input,"ticket"),generation,RuntimeWire.required(input,"instance"),input.getInt("taskId",-1));break;
            case RuntimeWire.ACTIVITY_DESTROYED:activities.destroyed(RuntimeWire.required(input,"ticket"),generation,RuntimeWire.required(input,"instance"),input.getBoolean("finishing"),input.getInt("taskId",-1));break;
            case RuntimeWire.ACTIVITY_NEW_INTENT:if(activities.closedEntry(RuntimeWire.required(input,"candidate"),RuntimeWire.required(input,"stub"),from)){result.putBoolean("closed",true);return result;}activities.newIntent(RuntimeWire.required(input,"ticket"),RuntimeWire.required(input,"instance"),from,RuntimeWire.required(input,"candidate"),RuntimeWire.required(input,"delivery"),RuntimeWire.required(input,"stub"),RuntimeWire.required(input,"payloadDigest"));activities.caller(input.getString("delivery"),result);break;
            case RuntimeWire.ACTIVITY_NEW_INTENT_ACK:activities.newIntentReceived(RuntimeWire.required(input,"ticket"),RuntimeWire.required(input,"instance"),generation,RuntimeWire.required(input,"delivery"),input.getBoolean("success"));break;
            case RuntimeWire.RESULT:{String op=RuntimeWire.required(input,"operation");var source=contract.consumeCallback(RuntimeWire.required(input,"callback"),generation,pid,uid,op);Delivery delivery=deliveries.get(op);if(delivery==null){if(contract.query(op).state!=ExecutionContract.OperationState.RUNNING){result.putString("source",source.processGeneration);break;}throw new SecurityException("DELIVERY_TARGET_MISMATCH");}if(!delivery.target.equals(generation))throw new SecurityException("DELIVERY_TARGET_MISMATCH");delivery.authorized=true;trace("return-authorized",op);result.putString("source",source.processGeneration);break;}
            case RuntimeWire.ACTIVITY_RESULT_ENTER:{
                String op=RuntimeWire.required(input,"operation");Delivery delivery=deliveries.get(op);
                var receiver=activities.source(RuntimeWire.required(input,"ticket"),RuntimeWire.required(input,"instance"),from,input.getInt("taskId",-1));
                if(delivery==null||!delivery.source.equals(generation)||!delivery.sourceTicket.equals(receiver.id)||delivery.receivingInstance!=null||(!delivery.authorized&&input.getInt("resultCode")!=android.app.Activity.RESULT_CANCELED))throw new SecurityException("DELIVERY_NOT_AUTHORIZED");
                delivery.receivingInstance=receiver.instance;result.putString("callerPackage",contract.getSession(delivery.target).identity.packageName);break;
            }
            case RuntimeWire.RECEIVED:{
                String op=RuntimeWire.required(input,"operation");Delivery delivery=deliveries.get(op);
                var receiver=activities.source(RuntimeWire.required(input,"ticket"),RuntimeWire.required(input,"instance"),from,input.getInt("taskId",-1));
                if(delivery==null||!delivery.source.equals(generation)||!delivery.sourceTicket.equals(receiver.id)||!receiver.instance.equals(delivery.receivingInstance)||(!delivery.authorized&&input.getInt("resultCode")!=android.app.Activity.RESULT_CANCELED))throw new SecurityException("DELIVERY_NOT_AUTHORIZED");
                if(!input.getBoolean("success")){if(contract.query(op).state==ExecutionContract.OperationState.RUNNING)contract.markUnknown(op,"ACTIVITY_RESULT_CALLBACK_FAILED");trace("source-callback-failed",op);break;}
                boolean success=input.getInt("resultCode")!=android.app.Activity.RESULT_CANCELED;
                String value=success?"source-onActivityResult-completed;resultCode="+input.getInt("resultCode"):"RESULT_CANCELED";
                if(contract.query(op).state==ExecutionContract.OperationState.UNKNOWN){
                    String evidence="source="+generation+";operation="+op+";pid="+pid+";resultCode="+input.getInt("resultCode");
                    contract.reconcile(op,success?ExecutionContract.OperationState.SUCCEEDED:ExecutionContract.OperationState.FAILED,value,evidence);
                }else if(success)contract.succeed(op,value);else contract.fail(op,value);
                activities.resultDelivered(op);trace("source-received",op);deliveries.remove(op);break;
            }
            default:throw new IllegalArgumentException("BROKER_OPERATION_UNSUPPORTED");
        }return result;
    }
    private Bundle routeActivity(Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        String generation=RuntimeWire.required(input,"generation"),pkg=RuntimeWire.required(input,"package"),component=RuntimeWire.required(input,"component"),op=RuntimeWire.required(input,"operation");
        boolean wantsResult=input.getBoolean("wantsResult");
        // A start requested through an Application/Service Context has no source Activity; it is a new task started by the process itself, as Android treats a non-Activity caller.
        boolean fromContext=input.getBoolean("fromContext");
        String sourceTicket=fromContext?"":RuntimeWire.required(input,"ticket"),sourceInstance=fromContext?"":RuntimeWire.required(input,"instance"),payloadDigest=RuntimeWire.required(input,"requestDigest");
        if(fromContext&&(wantsResult||(input.getInt("flags",0)&Intent.FLAG_ACTIVITY_NEW_TASK)==0))throw new IllegalArgumentException("CONTEXT_START_REQUIRES_NEW_TASK_WITHOUT_RESULT");
        ExecutionContract.Session source;ApkStore.BundleInstall target;String targetProcess;
        synchronized(this){
            source=contract.authenticate(generation,pid,uid);if(!fromContext)activities.source(sourceTicket,sourceInstance,requiredSlot(generation),input.getInt("taskId",-1));target=apks.get(source.identity.spaceId,pkg);
            var declared=RuntimeComponents.activity(target,component);RuntimeComponents.authorize(declared,source.identity.packageName);targetProcess=declared.processName;
            RuntimeActivityIntent.requireFlags(input.getInt("flags",0));
            var accepted=contract.accept(op,source.identity.key(),ApkStore.textDigest(generation+"\n"+sourceTicket+"\n"+sourceInstance+"\n"+pkg+"\n"+component+"\n"+wantsResult+"\n"+payloadDigest));
            if(!accepted.created)throw new IllegalStateException("ROUTE_ALREADY_ACCEPTED_QUERY_REQUIRED");contract.start(op);
        }
        try{
            // Activity starts, like Service starts, authorize a destination
            // component; they must not block the source UI on another guest's
            // bootstrap/Application. Android owns the actual process entry.
            Slot to=processes.reserveComponent(target,targetProcess);
            synchronized(this){
                contract.authenticate(generation,pid,uid);var caller=fromContext?null:activities.source(sourceTicket,sourceInstance,requiredSlot(generation),input.getInt("taskId",-1));String toGen=to.allocation.session.processGeneration;
                if(requiredSlot(toGen)!=to||!to.install.generation.equals(target.generation))throw new IllegalStateException("STALE_INSTALL");
                String callback=null;if(wantsResult){callback=contract.issueCallback(generation,toGen,op,System.currentTimeMillis()+60000);lastCallback=callback;lastOperation=op;deliveries.put(op,new Delivery(generation,toGen,sourceTicket));}
                String ticket=activities.prepare(to,component,wantsResult?op:null,op,source.identity.packageName,payloadDigest,caller,input.getInt("flags",0));String delivery=activities.launches.forTicket(ticket);Bundle result=new Bundle();result.putString("stub",activities.get(ticket).stub);result.putString("generation",toGen);if(callback!=null)result.putString("callback",callback);result.putString("operation",op);result.putString("ticket",ticket);result.putString("delivery",delivery);activities.caller(delivery,result);return result;
            }
        }catch(Exception error){synchronized(this){if(contract.query(op).state==ExecutionContract.OperationState.RUNNING)contract.markUnknown(op,"COMPONENT_PREPARATION_UNCONFIRMED");}throw error;}
    }
    /** Called only after all workers are confirmed dead, never while replacing an active authority. */
    public synchronized Bundle inspectTasks(){var observed=RuntimeTaskReferences.observe(host);Bundle out=new Bundle();out.putIntArray("recent",observed.recent.stream().mapToInt(Integer::intValue).toArray());out.putIntArray("running",observed.running.stream().mapToInt(Integer::intValue).toArray());return out;}
    public synchronized void recover(){if(!slots.isEmpty())throw new IllegalStateException("RECOVERY_WITH_LIVE_PROCESS");store.recoverSessions(host);contract.onRecover();try{apks.recover();projects.recover();}catch(Exception error){throw new IllegalStateException("ARTIFACT_RECOVERY_FAILED",error);}}
}
