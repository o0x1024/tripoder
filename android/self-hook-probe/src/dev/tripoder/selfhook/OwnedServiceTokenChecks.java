package dev.tripoder.selfhook;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Probe-only access to the fixed group; the service and manager are owned fixtures. */
public final class OwnedServiceTokenChecks {
    public static final class OwnedService extends Service {
        @Override public IBinder onBind(Intent intent){return null;}
    }
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new IllegalStateException(reason);}
    public static JSONObject run() throws Exception {
        FrameworkAccess.prepare();Object[] group=FrameworkAccess.serviceTokenMembers();ServiceTokenAccess.prepare();
        Field managerField=(Field)group[2],tokenField=(Field)group[3];managerField.setAccessible(true);tokenField.setAccessible(true);
        OwnedService service=new OwnedService();IBinder token=new Binder();ComponentName component=new ComponentName("dev.tripoder.selfhookprobe","OwnedPhysicalService");
        AtomicInteger calls=new AtomicInteger();AtomicReference<Thread> expectedThread=new AtomicReference<>(Thread.currentThread());
        RemoteException original=new RemoteException("owned-service-token-exception");
        Object manager=Proxy.newProxyInstance(OwnedServiceTokenChecks.class.getClassLoader(),new Class<?>[]{(Class<?>)group[1]},(proxy,method,args)->{
            check(method.getName().equals("stopServiceToken")&&args.length==3,"SERVICE_EXACT_METHOD");
            check(args[0]==component&&args[1]==token&&Thread.currentThread()==expectedThread.get(),"SERVICE_ARGUMENT_AND_THREAD_IDENTITY");
            calls.incrementAndGet();int id=(Integer)args[2];if(id==Integer.MIN_VALUE)throw original;return id>=0;
        });
        managerField.set(service,manager);tokenField.set(service,token);
        for(int id:new int[]{0,1,Integer.MAX_VALUE,-1})check(ServiceTokenAccess.stop(service,component,id)==(id>=0),"SERVICE_BOOLEAN_AND_INT_PRECISION");
        Throwable caught=null;try{ServiceTokenAccess.stop(service,component,Integer.MIN_VALUE);}catch(Throwable error){caught=error;}
        check(caught==original,"SERVICE_ORIGINAL_EXCEPTION_IDENTITY");
        AtomicReference<Throwable> workerError=new AtomicReference<>();Thread worker=new Thread(()->{
            try{expectedThread.set(Thread.currentThread());check(ServiceTokenAccess.stop(service,component,77),"SERVICE_WORKER_RESULT");}
            catch(Throwable error){workerError.set(error);}
        },"owned-service-token-worker");worker.start();worker.join(5000);
        check(!worker.isAlive()&&workerError.get()==null,"SERVICE_WORKER_COMPLETED");
        check(managerField.get(service)==manager&&tokenField.get(service)==token,"SERVICE_PHYSICAL_FIELDS_UNCHANGED");
        tokenField.set(service,null);caught=null;
        try{ServiceTokenAccess.stop(service,component,1);}catch(IllegalStateException error){caught=error;}
        check(caught!=null&&caught.getMessage().equals("SERVICE_TOKEN_PHYSICAL_STATE_MISSING")&&calls.get()==6,"SERVICE_NULL_TOKEN_REJECTED_BEFORE_CALL");
        tokenField.set(service,token);managerField.set(service,null);caught=null;
        try{ServiceTokenAccess.stop(service,component,1);}catch(IllegalStateException error){caught=error;}
        check(caught!=null&&caught.getMessage().equals("SERVICE_TOKEN_PHYSICAL_STATE_MISSING")&&calls.get()==6,"SERVICE_NULL_MANAGER_REJECTED_BEFORE_CALL");
        caught=null;try{ServiceTokenAccess.prepare();}catch(IllegalStateException error){caught=error;}
        check(caught!=null&&caught.getMessage().equals("SERVICE_TOKEN_PREPARE_ALREADY_ATTEMPTED"),"SERVICE_PREPARE_ONE_SHOT");
        return new JSONObject().put("checks",checks).put("calls",calls.get()).put("threads",2).put("exactException",true)
            .put("physicalFieldsUnchanged",true).put("systemServiceInvoked",false).put("hookInstalled",false).put("productAccepted",false);
    }
}
