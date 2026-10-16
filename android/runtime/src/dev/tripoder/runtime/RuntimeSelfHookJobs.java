package dev.tripoder.runtime;

import android.app.Service;
import android.app.job.*;
import android.os.IInterface;
import dev.tripoder.selfhook.JobFrameworkAccess;
import java.lang.reflect.Method;
import java.util.*;

final class RuntimeSelfHookJobs {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Method,RuntimeManagedInstaller.Callback> bindings=new LinkedHashMap<>();
        for(Method method:JobFrameworkAccess.hooks())bindings.put(method,(receiver,args,original)->{
            RuntimeJobs jobs=RuntimeEngine.current.jobs;
            if(method.getDeclaringClass()==JobService.class&&jobs.ownsService(receiver)){
                if(method.getName().equals("jobFinished")){jobs.finish((JobService)receiver,(JobParameters)args[0],(Boolean)args[1]);return null;}
                throw new UnsupportedOperationException("JOB_SERVICE_CAPABILITY_UNSUPPORTED:"+method.getName());
            }
            if(method.getDeclaringClass()==JobParameters.class&&jobs.ownsParameters(receiver))throw new UnsupportedOperationException("JOB_PARAMETER_CAPABILITY_UNSUPPORTED:"+method.getName());
            if(receiver instanceof IInterface&&method.getDeclaringClass().getName().equals("android.app.job.IJobCallback$Stub$Proxy")){
                Object result=original.call(args);jobs.rpcReturned(((IInterface)receiver).asBinder(),method.getName(),(Integer)args[0],(Boolean)args[1]);return result;
            }
            return original.call(args);
        });installer.install(bindings);
    }
    static void rejectServiceControl(Service service){if(RuntimeEngine.current!=null&&RuntimeEngine.current.jobs.ownsService(service))throw new UnsupportedOperationException("JOB_SERVICE_TOKEN_CONTROL_DENIED");}
    private RuntimeSelfHookJobs(){}
}
