package dev.tripoder.runtime;

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
    private final Map<JobParameters,View> views=new WeakHashMap<>();
    private final Map<IBinder,Attempt> callbacks=new HashMap<>();
    private static final class Delegate {final RuntimeJobGateway gateway;final JobService service;final String component;Delegate(RuntimeJobGateway g,JobService s,String c){gateway=g;service=s;component=c;}}
    private static final class Attempt {final Delegate delegate;final String id,version;final int jobId;final IBinder callback;final PersistableBundle extras;final String namespace;JobParameters physical;boolean active=true,finishRequested;Attempt(Delegate d,String id,String version,JobParameters p,JobInfo logical,String namespace){delegate=d;this.id=id;this.version=version;jobId=p.getJobId();callback=JobFrameworkAccess.callback(p);physical=p;extras=new PersistableBundle(logical.getExtras());this.namespace=namespace;}}
    private static final class View {final Attempt attempt;View(Attempt a){attempt=a;}}
    RuntimeJobs(RuntimeEngine engine){this.engine=engine;}
    synchronized boolean ownsService(Object value){return services.containsKey(value);}
    synchronized boolean ownsParameters(Object value){return views.containsKey(value);}
    boolean start(RuntimeJobGateway gateway,JobParameters physical)throws Exception{
        String version=physical.getExtras().getString(RuntimeJobInfo.VERSION);IBinder callback=JobFrameworkAccess.callback(physical);
        Bundle request=engine.auth();request.putString("version",version);request.putString("instance",gateway.instance);request.putString("stub",gateway.getClass().getName());request.putInt("jobId",physical.getJobId());request.putString("namespace",JobFrameworkAccess.namespace(physical));request.putBinder("callback",callback);
        Bundle admitted=engine.brokerCall(RuntimeWire.JOB_BEGIN,request);JobInfo logical=RuntimeJobInfo.decode(admitted.getByteArray("logical"));
        engine.startApplication();Delegate delegate;
        synchronized(this){delegate=delegates.get(gateway);}
        if(delegate==null){
            String component=RuntimeWire.required(admitted,"component");var created=engine.componentFactory.instantiateService(engine.loader,component,new Intent().setComponent(new ComponentName(engine.packageName,component)));
            if(!(created instanceof JobService)||!engine.ownsLoader(created.getClass().getClassLoader()))throw new SecurityException("JOB_SERVICE_CLASS_REJECTED");
            JobService service=(JobService)created;delegate=new Delegate(gateway,service,component);
            synchronized(this){delegates.put(gateway,delegate);services.put(service,delegate);}
            JobFrameworkAccess.attach(service,engine.application,engine.application,component);service.onCreate();
        }else if(!delegate.component.equals(logical.getService().getClassName()))throw new SecurityException("JOB_GATEWAY_COMPONENT_CHANGED");
        Attempt attempt=new Attempt(delegate,RuntimeWire.required(admitted,"attempt"),version,physical,logical,admitted.getString("namespace"));
        synchronized(this){if(callbacks.putIfAbsent(callback,attempt)!=null)throw new SecurityException("JOB_CALLBACK_REUSED");}
        JobParameters view=view(attempt,physical,logical.getExtras(),admitted.getString("namespace"));
        boolean ongoing=delegate.service.onStartJob(view);event(attempt,"START_RETURNED",ongoing);
        synchronized(this){if(!ongoing)attempt.active=false;}
        return ongoing;
    }
    boolean stop(RuntimeJobGateway gateway,JobParameters parameters)throws Exception{
        Attempt attempt=resolve(gateway,parameters);event(attempt,"STOP_ENTERED",false);
        JobParameters view=followup(attempt,parameters);boolean retry=attempt.delegate.service.onStopJob(view);
        synchronized(this){attempt.active=false;}event(attempt,"STOP_RETURNED",retry);return retry;
    }
    void network(RuntimeJobGateway gateway,JobParameters parameters)throws Exception{Attempt attempt=resolve(gateway,parameters);event(attempt,"NETWORK_CHANGED",false);attempt.delegate.service.onNetworkChanged(followup(attempt,parameters));}
    private synchronized Attempt resolve(RuntimeJobGateway gateway,JobParameters parameters){
        Attempt attempt=callbacks.get(JobFrameworkAccess.callback(parameters));
        if(attempt==null||attempt.delegate.gateway!=gateway||attempt.jobId!=parameters.getJobId()||!attempt.version.equals(parameters.getExtras().getString(RuntimeJobInfo.VERSION)))throw new SecurityException("JOB_CALLBACK_ATTEMPT_STALE");return attempt;
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
        if(event.equals("FINISH_RPC_RETURNED")||event.equals("STOP_RPC_RETURNED")||event.equals("START_RPC_RETURNED")&&!value)synchronized(this){attempt.active=false;attempt.physical=null;callbacks.remove(callback);}
    }
    void destroyed(RuntimeJobGateway gateway)throws Exception{
        Delegate delegate;synchronized(this){delegate=delegates.remove(gateway);if(delegate==null)return;services.remove(delegate.service);}
        delegate.service.onDestroy();Bundle request=engine.auth();request.putString("instance",gateway.instance);request.putString("event","DESTROYED");engine.brokerCall(RuntimeWire.JOB_EVENT,request);
        synchronized(this){for(var iterator=callbacks.entrySet().iterator();iterator.hasNext();){Attempt attempt=iterator.next().getValue();if(attempt.delegate==delegate){attempt.active=false;attempt.physical=null;iterator.remove();}}}
    }
    private void event(Attempt attempt,String event,boolean value)throws Exception{Bundle request=engine.auth();request.putString("attempt",attempt.id);request.putString("instance",attempt.delegate.gateway.instance);request.putBinder("callback",attempt.callback);request.putString("event",event);request.putBoolean("value",value);engine.brokerCall(RuntimeWire.JOB_EVENT,request);}
}
