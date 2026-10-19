package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Reserve under authority; bind and LOAD outside its lock so REGISTER/EVENT can re-enter. */
public final class ProcessSupervisor {
    private final RuntimeBroker broker;
    private final Map<String,Launch> launches=new HashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService starts=Executors.newFixedThreadPool(RuntimeSlot.CAPACITY);
    private final ExecutorService stops=Executors.newFixedThreadPool(RuntimeSlot.CAPACITY);
    ProcessSupervisor(RuntimeBroker broker){this.broker=broker;main.postDelayed(this::maintainSpare,SPARE_INITIAL_DELAY_MS);}
    private static final long SPARE_INITIAL_DELAY_MS=3000,SPARE_AFTER_LAUNCH_DELAY_MS=1500;
    /** One identity-free, already prewarmed worker (library loaded, fixed hooks reserved, no guest identity). Guarded by broker. */
    private Spare spare;
    private final class Spare implements ServiceConnection {
        final int index;boolean bound,dropped;
        Spare(int index){this.index=index;}
        @Override public void onServiceConnected(ComponentName name,IBinder value){}
        @Override public void onServiceDisconnected(ComponentName name){dropSpare(this);}
        @Override public void onBindingDied(ComponentName name){dropSpare(this);}
        @Override public void onNullBinding(ComponentName name){dropSpare(this);}
    }
    /** Idle-time prewarm so a click finds a worker with the 2 s library/hook preparation already done. Never runs ahead of a launch in flight. */
    void maintainSpare(){
        final Spare created;
        synchronized(broker){
            if(spare!=null)return;
            for(Launch l:launches.values())if(!l.ready.isDone())return;
            broker.services.reconcile(broker.host);
            int index=-1;for(int i=RuntimeSlot.CAPACITY-1;i>=0;i--){String stub=RuntimeSlot.activity(i).getName();
                if(!broker.stubUsed(stub)&&!occupied(i)&&!broker.providers.occupied(stub)&&!broker.broadcasts.occupied(stub)&&!broker.activities.processPinned(stub)&&!broker.activities.occupied(stub)&&!broker.services.occupied(stub)&&!broker.jobs.components.occupied(stub)){index=i;break;}}
            // The last free slot stays available for a real launch.
            int free=0;for(int i=0;i<RuntimeSlot.CAPACITY;i++){String stub=RuntimeSlot.activity(i).getName();if(!broker.stubUsed(stub)&&!occupied(i)&&!broker.providers.occupied(stub)&&!broker.broadcasts.occupied(stub)&&!broker.activities.processPinned(stub)&&!broker.services.occupied(stub)&&!broker.jobs.components.occupied(stub))free++;}
            if(index<0||free<2)return;
            created=new Spare(index);spare=created;
        }
        main.post(()->{
            boolean skip;synchronized(broker){skip=created.dropped;}
            if(skip)return;
            try{created.bound=broker.host.bindService(new Intent(broker.host,RuntimeSlot.worker(created.index)),created,Context.BIND_AUTO_CREATE);}
            catch(RuntimeException error){android.util.Log.w("TripoderRuntime","Spare worker bind failed",error);}
            if(!created.bound)dropSpare(created);
        });
    }
    private void dropSpare(Spare value){
        synchronized(broker){if(spare==value)spare=null;value.dropped=true;}
        main.post(()->{if(value.bound){value.bound=false;try{broker.host.unbindService(value);}catch(RuntimeException ignored){}}});
    }
    private static String key(String space,String pkg,String process){return space+"/"+pkg+"/"+process;}
    public RuntimeBroker.Slot ensure(String space,String pkg,String process)throws Exception{
        return acquire(space,pkg,process,true);
    }
    RuntimeBroker.Slot prepare(String space,String pkg,String process)throws Exception{return acquire(space,pkg,process,false);}
    /** Reservation only: the process starts in the background and the caller later waits with awaitReady. */
    RuntimeBroker.Slot begin(String space,String pkg,String process)throws Exception{return acquire(space,pkg,process,false,false);}
    /** Waits for the Application of an already reserved launch under the one shared startup deadline. */
    void awaitReady(String generation)throws Exception{
        Launch found=null;synchronized(broker){
            for(Launch l:launches.values())if(l.slot.allocation.session.processGeneration.equals(generation)){found=l;break;}
            if(found==null){if(broker.contract.getSession(generation).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("PROCESS_NOT_READY");return;}
        }
        Launch launch=found;
        try{launch.ready.get(RuntimeStartupDeadline.remaining(launch.slot.startupDeadlineElapsed,SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){IllegalStateException unknown=startTimeout(launch,timeout);drain(launch,unknown);throw unknown;}
        catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("PROCESS_START_FAILED",cause);}
    }
    private RuntimeBroker.Slot acquire(String space,String pkg,String process,boolean awaitApplication)throws Exception{return acquire(space,pkg,process,awaitApplication,true);}
    private RuntimeBroker.Slot acquire(String space,String pkg,String process,boolean awaitApplication,boolean wait)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("PROCESS_START_ON_MAIN_THREAD");
        RuntimeJobRetirement.reconcile(broker);
        RuntimeEmptyTasks.reclaim(broker,null);
        String key=key(space,pkg,process);Launch launch;boolean owner=false;
        var install=broker.apks.get(space,pkg);
        synchronized(broker){
            var current=broker.apks.row(space,pkg);
            if(current==null||!"COMMITTED".equals(current.state)||!install.generation.equals(current.generation)||!install.data.getName().equals(current.dataId))throw new IllegalStateException("STALE_INSTALL");
            if(!install.artifacts.processes.contains(process))throw new IllegalArgumentException("PROCESS_NOT_DECLARED");
            launch=launches.get(key);
            if(launch==null){
                var existing=broker.findProcess(space,pkg,process);
                if(existing!=null){var state=broker.contract.getSession(existing.allocation.session.processGeneration).state;if(state!=ExecutionContract.ProcessState.RUNNING&&(awaitApplication||state!=ExecutionContract.ProcessState.ATTACHED))throw new IllegalStateException("PROCESS_NOT_READY");return existing;}
                launch=reserveLaunch(install,process,key,false);owner=true;
            }
            if(!launch.slot.install.generation.equals(install.generation)||!launch.slot.install.data.equals(install.data))throw new IllegalStateException("STALE_INSTALL");
            if(launch.dead)throw new IllegalStateException("PROCESS_DRAINING");
        }
        if(owner){Launch starting=launch;starts.execute(()->start(starting));}
        if(!wait)return launch.slot;
        try{return (awaitApplication?launch.ready:launch.prepared).get(RuntimeStartupDeadline.remaining(launch.slot.startupDeadlineElapsed,SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){IllegalStateException unknown=startTimeout(launch,timeout);drain(launch,unknown);throw unknown;}
        catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("PROCESS_START_FAILED",cause);}
    }
    private Launch reserveLaunch(ApkStore.BundleInstall install,String process,String key,boolean external)throws Exception{
        int index=availableIndex(install,process,!external);
        Launch launch=new Launch(key,index,broker.reserve(install,process,RuntimeSlot.activity(index).getName()));launch.external=external;
        if(spare!=null&&spare.index==index){launch.spare=spare;spare=null;}
        launches.put(key,launch);
        watch(launch);
        return launch;
    }
    private int availableIndex(ApkStore.BundleInstall install,String process){return availableIndex(install,process,false);}
    /** allowSpare: a launch started by this authority may take over the prewarmed worker; component-driven entries and passive reservations never do. */
    private int availableIndex(ApkStore.BundleInstall install,String process,boolean allowSpare){
        broker.services.reconcile(broker.host);String retained=broker.retainedStub(install,process);
        int index=-1;
        if(allowSpare&&spare!=null&&fits(spare.index,install,process,retained))index=spare.index;
        for(int i=0;index<0&&i<RuntimeSlot.CAPACITY;i++)if((spare==null||spare.index!=i)&&fits(i,install,process,retained))index=i;
        if(index<0&&spare!=null){Spare idle=spare;dropSpare(idle);if(fits(idle.index,install,process,retained))index=idle.index;}
        if(index<0)throw new IllegalStateException("PROCESS_CAPACITY_EXHAUSTED");
        return index;
    }
    private boolean fits(int i,ApkStore.BundleInstall install,String process,String retained){
        String stub=RuntimeSlot.activity(i).getName();
        return !broker.stubUsed(stub)&&!occupied(i)&&!broker.providers.occupied(stub)&&!broker.broadcasts.occupied(stub)&&(retained==null||retained.equals(stub))&&!broker.activities.processPinnedByOther(stub,install,process)&&!broker.services.occupiedByOther(stub,install,process)&&!broker.jobs.components.occupiedByOther(stub,install,process);
    }
    /** bind without AUTO_CREATE owns a component, not a process allocation or startup timer. */
    ServiceLedger.Lease reservePassiveService(ApkStore.BundleInstall install,String process,String component)throws Exception{
        synchronized(broker){
            var current=broker.apks.get(install.space,install.info.packageName);
            if(!current.generation.equals(install.generation)||!current.data.equals(install.data))throw new SecurityException("STALE_INSTALL");
            if(!install.artifacts.processes.contains(process))throw new IllegalArgumentException("PROCESS_NOT_DECLARED");
            var live=broker.findProcess(install.space,install.info.packageName,process);
            if(live!=null){broker.requiredSlot(live.allocation.session.processGeneration);return broker.services.reserve(live,component);}
            String stub=RuntimeSlot.activity(availableIndex(install,process)).getName();
            return broker.services.reservePassive(install,process,component,stub);
        }
    }
    /** Future scheduling retains identity only: no session, bind, LOAD or startup watchdog. */
    String jobProcessStub(ApkStore.BundleInstall install,String process)throws Exception{
        synchronized(broker){
            var current=broker.apks.row(install.space,install.info.packageName);
            if(current==null||!"COMMITTED".equals(current.state)||!current.generation.equals(install.generation))throw new SecurityException("JOB_INSTALL_STALE");
            if(!install.artifacts.processes.contains(process))throw new IllegalArgumentException("PROCESS_NOT_DECLARED");
            var live=broker.findProcess(install.space,install.info.packageName,process);
            String stub;
            if(live!=null){broker.requiredSlot(live.allocation.session.processGeneration);stub=live.activityStub;}
            else stub=RuntimeSlot.activity(availableIndex(install,process)).getName();
            return stub;
        }
    }
    /** The shared budget is never extended; the error only names the phase that consumed it. */
    private IllegalStateException startTimeout(Launch launch,Throwable cause){
        String phase=launch.phase;android.util.Log.w("TripoderLaunch","PROCESS_START_TIMEOUT phase="+phase+" elapsed="+(SystemClock.elapsedRealtime()-launch.begin)+"ms");
        return new IllegalStateException("PROCESS_START_TIMEOUT_UNKNOWN:phase="+phase,cause);
    }
    private void watch(Launch launch){main.postDelayed(()->{if(!launch.ready.isDone())drain(launch,startTimeout(launch,null));},RuntimeStartupDeadline.remaining(launch.slot.startupDeadlineElapsed,SystemClock.elapsedRealtime()));}
    /** Reservation only: Android's component request starts the process, never a reverse Worker bind. */
    RuntimeBroker.Slot reserveComponent(ApkStore.BundleInstall install,String process)throws Exception{
        synchronized(broker){
            if(!install.artifacts.processes.contains(process))throw new IllegalArgumentException("PROCESS_NOT_DECLARED");
            String key=key(install.space,install.info.packageName,process);Launch existing=launches.get(key);
            if(existing!=null){if(existing.dead)throw new IllegalStateException("PROCESS_DRAINING");if(!existing.slot.install.generation.equals(install.generation))throw new SecurityException("STALE_INSTALL");return broker.requiredSlot(existing.slot.allocation.session.processGeneration);}
            RuntimeBroker.Slot live=broker.findProcess(install.space,install.info.packageName,process);if(live!=null){if(!live.install.generation.equals(install.generation))throw new SecurityException("STALE_INSTALL");return broker.requiredSlot(live.allocation.session.processGeneration);}
            return reserveLaunch(install,process,key,true).slot;
        }
    }
    Bundle claimComponent(RuntimeBroker.Slot slot,IBinder peer)throws Exception{
        synchronized(broker){
            String key=key(slot.install.space,slot.install.info.packageName,slot.allocation.session.identity.processName);Launch launch=launches.get(key);
            if(launch==null){launch=new Launch(key,RuntimeSlot.index(slot.activityStub),slot);launches.put(key,launch);watch(launch);}
            if(launch.slot!=slot||launch.dead)throw new SecurityException("COMPONENT_RESERVATION_NOT_CLAIMABLE");
            if(slot.peer!=null){if(!slot.peer.equals(peer))throw new SecurityException("COMPONENT_PEER_MISMATCH");Bundle joined=new Bundle();joined.putBoolean("awaitExisting",true);return joined;}
            if(launch.peer!=null&&!launch.peer.equals(peer))throw new SecurityException("COMPONENT_PEER_MISMATCH");
            // Binding may already be queued on this process's main thread. The component
            // atomically owns construction; a late Worker may join but cannot LOAD again.
            launch.external=true;Bundle bootstrap=broker.attachPeer(slot,peer);boolean observe=launch.peer==null;launch.peer=peer;launch.connected.countDown();
            Launch selected=launch;if(observe)try{peer.linkToDeath(()->main.post(selected::peerDied),0);}catch(RemoteException error){main.post(selected::peerDied);throw error;}return bootstrap;
        }
    }
    void event(String generation,String stage){
        for(Launch launch:launches.values())if(launch.external&&launch.slot.allocation.session.processGeneration.equals(generation)){
            if(launch.dead)throw new IllegalStateException("PROCESS_DRAINING");
            if(stage.equals("adapter-ready"))launch.prepared.complete(launch.slot);
            if(stage.equals("application-ready"))launch.ready.complete(launch.slot);
        }
    }
    private boolean occupied(int index){for(Launch l:launches.values())if(l.index==index)return true;return false;}
    /** Explain finite capacity, including historical uncertainty that still pins a slot. */
    public Bundle capacity(){synchronized(broker){
        broker.services.reconcile(broker.host);ArrayList<Bundle> rows=new ArrayList<>();int available=0,reserved=0;
        for(int i=0;i<RuntimeSlot.CAPACITY;i++){
            String stub=RuntimeSlot.activity(i).getName();Bundle row=new Bundle();boolean allocated=broker.stubUsed(stub)||occupied(i),activity=broker.activities.processPinned(stub),service=broker.services.occupied(stub),provider=broker.providers.occupied(stub),broadcast=broker.broadcasts.occupied(stub),job=broker.jobs.components.occupied(stub);
            row.putInt("index",i);row.putBoolean("allocated",allocated);row.putBoolean("activity",activity);row.putBoolean("activityResources",broker.activities.occupied(stub));row.putBoolean("service",service);row.putBoolean("provider",provider);row.putBoolean("broadcast",broadcast);row.putBoolean("job",job);row.putBoolean("spare",spare!=null&&spare.index==i);rows.add(row);
            if(allocated)reserved++;else if(!activity&&!service&&!provider&&!broadcast&&!job&&!(spare!=null&&spare.index==i))available++;
        }
        Bundle out=new Bundle();out.putInt("total",RuntimeSlot.CAPACITY);out.putInt("allocated",reserved);out.putInt("available",available);out.putInt("retained",RuntimeSlot.CAPACITY-reserved-available);out.putInt("spare",spare==null?0:1);out.putParcelableArrayList("slots",rows);return out;
    }}
    private void start(Launch launch){
        try{
            synchronized(broker){if(launch.external||launch.dead)return;}
            FutureTask<Boolean> bind=new FutureTask<>(()->{synchronized(broker){if(launch.external||launch.dead)return true;}boolean ok=broker.host.bindService(new Intent(broker.host,RuntimeSlot.worker(launch.index)).putExtra("tripoder.bootstrapAbort",launch.slot.allocation.bootstrapToken),launch,Context.BIND_AUTO_CREATE);launch.boundToSystem=ok;launch.bindResolved=true;return ok;});main.post(bind);
            if(!bind.get(5,TimeUnit.SECONDS))throw new IllegalStateException("WORKER_BIND_REJECTED");
            synchronized(broker){if(launch.external)return;}
            if(!launch.connected.await(15,TimeUnit.SECONDS)||launch.peer==null)throw new IllegalStateException("WORKER_BIND_TIMEOUT_UNKNOWN");
            if(launch.spare!=null){
                // A warm worker's onBind already ran without this launch's abort capability; hand it over, then let the idle bind go.
                Bundle abort=new Bundle();abort.putString("bootstrapAbort",launch.slot.allocation.bootstrapToken);RuntimeWire.call(launch.peer,RuntimeWire.ABORT_CONFIGURE,abort);
                Spare taken=launch.spare;dropSpare(taken);
            }
            Bundle bootstrap;synchronized(broker){if(launch.external)return;if(launch.dead)throw new IllegalStateException("PROCESS_DRAINING");bootstrap=broker.attachPeer(launch.slot,launch.peer);}
            launch.phase="LOAD";RuntimeWire.call(launch.peer,RuntimeWire.LOAD,bootstrap);
            synchronized(broker){if(launch.dead)throw new IllegalStateException("PROCESS_DRAINING");launch.prepared.complete(launch.slot);}
            launch.phase="APPLICATION";RuntimeWire.call(launch.peer,RuntimeWire.START_APPLICATION,new Bundle());
            synchronized(broker){
                if(launch.dead||broker.contract.getSession(launch.slot.allocation.session.processGeneration).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("WORKER_START_NOT_CONFIRMED");
                launch.ready.complete(launch.slot);
            }
            main.postDelayed(this::maintainSpare,SPARE_AFTER_LAUNCH_DELAY_MS);
        }catch(Exception error){
            synchronized(broker){if(launch.external)return;} // The component owns its watchdog and completion.
            drain(launch,error);
            if(launch.bindResolved&&!launch.boundToSystem){synchronized(broker){broker.dead(launch.slot.allocation.session.processGeneration);}main.post(()->release(launch));}
        }
    }
    private void drain(Launch launch,Exception error){
        boolean first;synchronized(broker){first=!launch.dead;launch.dead=true;launch.slot.closing=true;}
        if(launch.spare!=null){Spare idle=launch.spare;launch.spare=null;dropSpare(idle);}launch.ready.completeExceptionally(error);launch.prepared.completeExceptionally(error);
        // Neither a timeout nor an error response proves process death. STOP also stays off
        // the caller's thread so an unresponsive guest cannot prevent the UNKNOWN response.
        if(first){
            if(launch.peer!=null)requestStop(launch);
            else if(launch.external||(launch.bindResolved&&!launch.boundToSystem)){
                synchronized(broker){broker.dead(launch.slot.allocation.session.processGeneration);}
                main.post(()->release(launch));
            }
        }
    }
    private void requestStop(Launch launch){stops.execute(()->{try{Bundle request=new Bundle();request.putString("bootstrapAbort",launch.slot.allocation.bootstrapToken);RuntimeWire.call(launch.peer,RuntimeWire.STOP,request);}catch(Exception error){android.util.Log.w("TripoderRuntime","Worker termination not acknowledged; reservation remains pinned",error);}});}
    public void stop(String generation)throws Exception{terminate(generation,false);}
    public void close(String generation)throws Exception{terminate(generation,true);}
    private void terminate(String generation,boolean permanent)throws Exception{
        Launch selected=null;synchronized(broker){if(permanent)broker.store.requestClose(generation);for(Launch l:launches.values())if(l.slot.allocation.session.processGeneration.equals(generation)){selected=l;break;}if(selected!=null){selected.dead=true;selected.slot.closing=true;}}
        if(selected==null){
            RuntimeBroker.Slot cold;synchronized(broker){if(broker.store.isDead(generation))return;cold=broker.slotForTermination(generation);cold.closing=true;}
            CountDownLatch dead=new CountDownLatch(1);cold.peer.linkToDeath(dead::countDown,0);RuntimeWire.call(cold.peer,RuntimeWire.STOP,new Bundle());
            if(!dead.await(15,TimeUnit.SECONDS))throw new IllegalStateException("PROCESS_STOP_UNCONFIRMED");synchronized(broker){broker.dead(generation);}return;
        }
        if(selected.peer==null){drain(selected,new IllegalStateException("PROCESS_STOP_BEFORE_ATTACH_UNKNOWN"));throw new IllegalStateException("PROCESS_STOP_BEFORE_ATTACH_UNKNOWN");}
        Bundle stop=new Bundle();stop.putString("bootstrapAbort",selected.slot.allocation.bootstrapToken);RuntimeWire.call(selected.peer,RuntimeWire.STOP,stop);
        if(!selected.disconnected.await(15,TimeUnit.SECONDS))throw new IllegalStateException("PROCESS_STOP_UNCONFIRMED");
    }
    private void release(Launch launch){
        if(launch.boundToSystem){broker.host.unbindService(launch);launch.boundToSystem=false;}
        synchronized(broker){launches.remove(launch.key,launch);}launch.disconnected.countDown();main.postDelayed(this::maintainSpare,SPARE_AFTER_LAUNCH_DELAY_MS);
    }
    private final class Launch implements ServiceConnection {
        final String key;final int index;final RuntimeBroker.Slot slot;
        final CountDownLatch connected=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        final CompletableFuture<RuntimeBroker.Slot> ready=new CompletableFuture<>(),prepared=new CompletableFuture<>();
        volatile IBinder peer;volatile boolean dead,boundToSystem,bindResolved,external;private boolean deathObserved;
        volatile String phase="BIND";volatile Spare spare;final long begin=SystemClock.elapsedRealtime();
        Launch(String key,int index,RuntimeBroker.Slot slot){this.key=key;this.index=index;this.slot=slot;}
        @Override public void onServiceConnected(ComponentName name,IBinder value){boolean observe;synchronized(broker){observe=peer==null;if(!observe&&!peer.equals(value))throw new SecurityException("WORKER_PEER_CHANGED");peer=value;}if(observe)try{value.linkToDeath(()->main.post(this::peerDied),0);}catch(RemoteException error){main.post(this::peerDied);}connected.countDown();if(dead)requestStop(this);}
        private void peerDied(){synchronized(broker){if(deathObserved)return;deathObserved=true;dead=true;broker.dead(slot.allocation.session.processGeneration);}var error=new IllegalStateException("WORKER_DIED");ready.completeExceptionally(error);prepared.completeExceptionally(error);release(this);}
        @Override public void onServiceDisconnected(ComponentName name){if(peer!=null&&!peer.isBinderAlive())peerDied();else drain(this,new IllegalStateException("WORKER_DISCONNECTED_DEATH_UNCONFIRMED"));}
        @Override public void onNullBinding(ComponentName name){ready.completeExceptionally(new IllegalStateException("WORKER_NULL_BINDING"));connected.countDown();}
        @Override public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
    }
}
