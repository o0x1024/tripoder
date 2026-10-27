package dev.tripoder.runtime;

import android.app.Service;
import android.app.job.*;
import android.content.*;
import android.os.*;
import dev.tripoder.selfhook.JobFrameworkAccess;
import java.util.*;

/** Main-thread platform callbacks; object capabilities may request finish from any guest thread. */
final class RuntimeJobs {
    private final RuntimeEngine engine;
    private final Map<RuntimeJobGateway,Delegate> delegates=new IdentityHashMap<>();
    private final Map<JobService,Delegate> services=new IdentityHashMap<>();
    private final Map<Service,Delegate> boundServices=new IdentityHashMap<>();
    private final Map<JobParameters,View> views=new WeakHashMap<>();
    private final Map<IBinder,Attempt> callbacks=new HashMap<>(),terminalCallbacks=new HashMap<>();
    private final Map<JobWorkItem,Work> workItems=new IdentityHashMap<>();
    private static final class Work {final Attempt attempt;final JobWorkItem physical;boolean completing;Work(Attempt attempt,JobWorkItem physical){this.attempt=attempt;this.physical=physical;}}
    /** A JobService, or a plain Service declared BIND_JOB_SERVICE (androidx JobIntentService) that the platform would bind and drive through the IJobService binder returned by onBind. */
    private static final class Delegate {final RuntimeJobGateway gateway;final JobService service;final Service bound;final IBinder engine;final String component;Delegate(RuntimeJobGateway g,JobService s,Service b,IBinder e,String c){gateway=g;service=s;bound=b;engine=e;component=c;}}
    private static final String JOB_SERVICE_DESCRIPTOR="android.app.job.IJobService";
    private static final int START_JOB=IBinder.FIRST_CALL_TRANSACTION,STOP_JOB=IBinder.FIRST_CALL_TRANSACTION+1;
    /** The binder is the guest's own in-process JobServiceEngine, so this is a direct call into the guest, never a system Binder transaction. */
    private static void drive(Delegate delegate,int code,JobParameters view)throws Exception{
        Parcel data=Parcel.obtain();
        try{data.writeInterfaceToken(JOB_SERVICE_DESCRIPTOR);data.writeInt(1);view.writeToParcel(data,0);if(!delegate.engine.transact(code,data,null,IBinder.FLAG_ONEWAY))throw new IllegalStateException("JOB_ENGINE_REJECTED");}
        finally{data.recycle();}
    }
    private static final class Attempt {final Delegate delegate;final String id,version;final int jobId;final IBinder callback;final PersistableBundle extras;final String namespace;JobParameters physical;boolean active=true,finishRequested,workBlocked;Attempt(Delegate d,String id,String version,JobParameters p,JobInfo logical,String namespace){delegate=d;this.id=id;this.version=version;jobId=p.getJobId();callback=JobFrameworkAccess.callback(p);physical=p;extras=new PersistableBundle(logical.getExtras());this.namespace=namespace;}}
    private static final class View {final Attempt attempt;View(Attempt a){attempt=a;}}
    RuntimeJobs(RuntimeEngine engine){this.engine=engine;}
    synchronized boolean ownsService(Object value){if(services.containsKey(value)||boundServices.containsKey(value))return true;for(View view:views.values())if(view.attempt.delegate.service==value)return true;return false;}
    synchronized boolean ownsParameters(Object value){return viewOf(value)!=null;}
    /** A bound engine re-creates JobParameters from the parcel it was driven with, so those copies resolve by the one active attempt they describe. */
    private View viewOf(Object value){
        View known=views.get(value);if(known!=null||!(value instanceof JobParameters))return known;
        JobParameters copy=(JobParameters)value;
        for(Attempt attempt:callbacks.values())if(attempt.delegate.bound!=null&&attempt.active&&attempt.jobId==copy.getJobId()&&Objects.equals(attempt.namespace,JobFrameworkAccess.namespace(copy)))return new View(attempt);
        return null;
    }
    boolean start(RuntimeJobGateway gateway,JobParameters physical)throws Exception{
        String version=physical.getExtras().getString(RuntimeJobInfo.VERSION);IBinder callback=JobFrameworkAccess.callback(physical);
        Bundle request=engine.auth();request.putString("version",version);request.putString("instance",gateway.instance);request.putString("stub",gateway.getClass().getName());request.putInt("jobId",physical.getJobId());request.putString("namespace",JobFrameworkAccess.namespace(physical));request.putBinder("callback",callback);
        Bundle admitted=engine.brokerCall(RuntimeWire.JOB_BEGIN,request);JobInfo logical=RuntimeJobInfo.decode(admitted.getByteArray("logical"));
        engine.startApplication();Delegate delegate;
        synchronized(this){delegate=delegates.get(gateway);}
        if(delegate==null){
            String component=RuntimeWire.required(admitted,"component");var created=engine.componentFactory.instantiateService(engine.guestLoader(),component,new Intent().setComponent(new ComponentName(engine.packageName,component)));
            if(!(created instanceof Service)||!engine.ownsLoader(created.getClass().getClassLoader()))throw new SecurityException("JOB_SERVICE_CLASS_REJECTED");
            Service service=(Service)created;
            JobFrameworkAccess.attach(service,engine.application,engine.application,component);service.onCreate();
            if(service instanceof JobService){delegate=new Delegate(gateway,(JobService)service,null,null,component);synchronized(this){delegates.put(gateway,delegate);services.put((JobService)service,delegate);}}
            else{
                IBinder driver=service.onBind(new Intent().setComponent(new ComponentName(engine.packageName,component)));
                if(driver==null||!JOB_SERVICE_DESCRIPTOR.equals(driver.getInterfaceDescriptor())){service.onDestroy();throw new SecurityException("JOB_SERVICE_CLASS_REJECTED");}
                delegate=new Delegate(gateway,null,service,driver,component);synchronized(this){delegates.put(gateway,delegate);boundServices.put(service,delegate);}
            }
        }else if(!delegate.component.equals(logical.getService().getClassName()))throw new SecurityException("JOB_GATEWAY_COMPONENT_CHANGED");
        Attempt attempt=new Attempt(delegate,RuntimeWire.required(admitted,"attempt"),version,physical,logical,admitted.getString("namespace"));
        synchronized(this){if(callbacks.putIfAbsent(callback,attempt)!=null)throw new SecurityException("JOB_CALLBACK_REUSED");terminalCallbacks.remove(callback);}
        JobParameters view=view(attempt,physical,logical.getExtras(),admitted.getString("namespace"));
        boolean ongoing;
        if(delegate.bound!=null){drive(delegate,START_JOB,view);ongoing=true;}else ongoing=delegate.service.onStartJob(view);
        event(attempt,"START_RETURNED",ongoing);
        synchronized(this){if(!ongoing)attempt.active=false;}
        return ongoing;
    }
    boolean stop(RuntimeJobGateway gateway,JobParameters parameters)throws Exception{
        Attempt attempt;
        synchronized(this){Attempt closed=terminalCallbacks.get(JobFrameworkAccess.callback(parameters));if(matches(closed,gateway,parameters))return false;}
        attempt=resolve(gateway,parameters);event(attempt,"STOP_ENTERED",false);
        JobParameters view=followup(attempt,parameters);boolean retry=false;
        if(attempt.delegate.bound!=null)drive(attempt.delegate,STOP_JOB,view);else retry=attempt.delegate.service.onStopJob(view);
        synchronized(this){attempt.active=false;}event(attempt,"STOP_RETURNED",retry);return retry;
    }
    void network(RuntimeJobGateway gateway,JobParameters parameters)throws Exception{Attempt attempt=resolve(gateway,parameters);event(attempt,"NETWORK_CHANGED",false);if(attempt.delegate.service!=null)attempt.delegate.service.onNetworkChanged(followup(attempt,parameters));}
    private synchronized Attempt resolve(RuntimeJobGateway gateway,JobParameters parameters){
        Attempt attempt=callbacks.get(JobFrameworkAccess.callback(parameters));
        if(!matches(attempt,gateway,parameters))throw new SecurityException("JOB_CALLBACK_ATTEMPT_STALE");return attempt;
    }
    private static boolean matches(Attempt attempt,RuntimeJobGateway gateway,JobParameters parameters){return attempt!=null&&attempt.delegate.gateway==gateway&&attempt.jobId==parameters.getJobId()&&attempt.version.equals(parameters.getExtras().getString(RuntimeJobInfo.VERSION));}
    private synchronized Attempt workAttempt(JobParameters parameters){
        View view=viewOf(parameters);if(view==null||!view.attempt.active||view.attempt.finishRequested||view.attempt.physical==null)throw new SecurityException("JOB_WORK_CAPABILITY_STALE");
        if(view.attempt.workBlocked)throw new IllegalStateException("JOB_WORK_OUTCOME_UNKNOWN");return view.attempt;
    }
    JobWorkItem dequeue(JobParameters parameters)throws Exception{
        Attempt attempt;JobParameters physical;synchronized(this){attempt=workAttempt(parameters);physical=attempt.physical;}event(attempt,"WORK_DEQUEUE_ENTERED",false);
        try{
            // Only the actual system callback dequeues work; null is its real
            // queue observation, never an invented empty work list.
            JobWorkItem item=physical.dequeueWork();
            if(item!=null){if(item.getIntent()!=null)item.getIntent().setExtrasClassLoader(engine.guestLoader());synchronized(this){if(workItems.put(item,new Work(attempt,item))!=null)throw new SecurityException("JOB_WORK_OBJECT_REUSED");}}
            event(attempt,"WORK_DEQUEUE_RETURNED",item!=null);return item;
        }catch(Exception error){workUnknown(attempt,"WORK_DEQUEUE_UNCONFIRMED",error);throw error;}
    }
    void complete(JobParameters parameters,JobWorkItem item)throws Exception{
        Attempt attempt;JobParameters physical;Work work;
        synchronized(this){attempt=workAttempt(parameters);physical=attempt.physical;work=workItems.get(item);if(work==null||work.attempt!=attempt||work.completing)throw new SecurityException("JOB_WORK_ITEM_CAPABILITY_STALE");work.completing=true;}
        event(attempt,"WORK_COMPLETE_ENTERED",false);
        try{physical.completeWork(work.physical);event(attempt,"WORK_COMPLETE_RETURNED",true);synchronized(this){workItems.remove(item);}}
        catch(Exception error){workUnknown(attempt,"WORK_COMPLETE_UNCONFIRMED",error);throw error;}
    }
    private void workUnknown(Attempt attempt,String stage,Exception error){
        synchronized(this){attempt.workBlocked=true;}
        try{event(attempt,stage,false);}catch(Exception receipt){error.addSuppressed(receipt);}
    }
    private synchronized void retire(Attempt attempt){
        attempt.active=false;attempt.physical=null;callbacks.remove(attempt.callback);terminalCallbacks.put(attempt.callback,attempt);
        workItems.entrySet().removeIf(entry->entry.getValue().attempt==attempt);
    }
    private JobParameters followup(Attempt attempt,JobParameters parameters){return view(attempt,parameters,attempt.extras,attempt.namespace);}
    private synchronized JobParameters view(Attempt attempt,JobParameters physical,PersistableBundle extras,String namespace){JobParameters view=JobFrameworkAccess.view(physical,extras,namespace);views.put(view,new View(attempt));return view;}
    void finish(JobService service,JobParameters view,boolean retry)throws Exception{
        Attempt attempt;synchronized(this){View value=views.get(view);if(value==null||(attempt=value.attempt).delegate.service!=service||!attempt.active||attempt.finishRequested)throw new SecurityException("JOB_FINISH_CAPABILITY_STALE");attempt.finishRequested=true;attempt.active=false;}
        event(attempt,"FINISH_REQUESTED",retry);attempt.delegate.gateway.jobFinished(Objects.requireNonNull(attempt.physical),retry);
    }
    void rpcReturned(IBinder callback,String method,int jobId,boolean value)throws Exception{
        Attempt attempt;synchronized(this){attempt=callbacks.get(callback);if(attempt==null)return;if(attempt.jobId!=jobId)throw new SecurityException("JOB_CALLBACK_ID_MISMATCH");}
        String event=method.equals("jobFinished")?"FINISH_RPC_RETURNED":method.equals("acknowledgeStopMessage")?"STOP_RPC_RETURNED":"START_RPC_RETURNED";
        event(attempt,event,value);
        if(event.equals("FINISH_RPC_RETURNED")||event.equals("STOP_RPC_RETURNED")||event.equals("START_RPC_RETURNED")&&!value)synchronized(this){retire(attempt);}
    }
    void destroyed(RuntimeJobGateway gateway)throws Exception{
        Delegate delegate;synchronized(this){delegate=delegates.remove(gateway);if(delegate==null)return;if(delegate.service!=null)services.remove(delegate.service);else boundServices.remove(delegate.bound);}
        if(delegate.service!=null)delegate.service.onDestroy();else delegate.bound.onDestroy();Bundle request=engine.auth();request.putString("instance",gateway.instance);request.putString("event","DESTROYED");engine.brokerCall(RuntimeWire.JOB_EVENT,request);
        synchronized(this){for(var iterator=callbacks.entrySet().iterator();iterator.hasNext();){Attempt attempt=iterator.next().getValue();if(attempt.delegate==delegate){attempt.active=false;attempt.physical=null;iterator.remove();}}terminalCallbacks.entrySet().removeIf(entry->entry.getValue().delegate==delegate);workItems.entrySet().removeIf(entry->entry.getValue().attempt.delegate==delegate);}
    }
    private void event(Attempt attempt,String event,boolean value)throws Exception{Bundle request=engine.auth();request.putString("attempt",attempt.id);request.putString("instance",attempt.delegate.gateway.instance);request.putBinder("callback",attempt.callback);request.putString("event",event);request.putBoolean("value",value);engine.brokerCall(RuntimeWire.JOB_EVENT,request);}
}
