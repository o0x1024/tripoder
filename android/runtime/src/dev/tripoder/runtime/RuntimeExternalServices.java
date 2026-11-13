package dev.tripoder.runtime;

import android.os.*;
import java.util.*;
import dev.tripoder.selfhook.ExternalServiceFrameworkAccess;

/** Identity conversion at a declared remote interface, not at an app/package name.
 * The original BinderProxy remains visible to the guest (including parceling/death).
 */
final class RuntimeExternalServices {
    private final RuntimeEngine engine;
    private final java.util.concurrent.atomic.AtomicBoolean identityObserved=new java.util.concurrent.atomic.AtomicBoolean();
    /** First asynchronous (oneway) request seen per process: reported once, never thrown. */
    final java.util.concurrent.atomic.AtomicBoolean asyncObserved=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<IBinder,String> interfaces=Collections.synchronizedMap(new WeakHashMap<>());
    RuntimeExternalServices(RuntimeEngine engine){this.engine=engine;}
    void connected(IBinder binder){
        try{contract(Objects.requireNonNull(binder));}
        catch(RemoteException error){throw new IllegalStateException("EXTERNAL_SERVICE_DESCRIPTOR_UNAVAILABLE",error);}
    }
    private String contract(IBinder binder)throws RemoteException{
        String cached=interfaces.get(binder);if(cached!=null)return cached;
        // Service callbacks can return a new remote endpoint. Its own descriptor,
        // not the client package/class name, selects the declared wire contract.
        String descriptor=binder.getInterfaceDescriptor();
        String selected=RuntimeExternalServiceRequest.DESCRIPTOR.equals(descriptor)
                ||RuntimeExternalServiceRequest.MEASUREMENT.equals(descriptor)
                ||RuntimeAppOpsRequest.DESCRIPTOR.equals(descriptor)?descriptor:"";
        interfaces.put(binder,selected);return selected;
    }
    static void install(RuntimeManagedInstaller installer)throws Exception{
        installer.install(Map.of(ExternalServiceFrameworkAccess.transact(),(receiver,args,original)->{
            RuntimeEngine owner=RuntimeEngine.current;
            if(owner==null)return original.call(args);
            String contract=owner.externalServices.contract((IBinder)receiver);
            if(contract.isEmpty())return original.call(args);
            int code=(Integer)args[0];
            // Binder protocol control traffic carries no service request identity.
            if(code<IBinder.FIRST_CALL_TRANSACTION||code>IBinder.LAST_CALL_TRANSACTION)return original.call(args);
            // The platform never refuses a oneway transact: flags!=0 just means the caller does not
            // wait for the reply (IAppOpsService.noteOperationsInBatch is the one we hit). Converting
            // identity here is only needed to keep system_service's package/uid check happy, and the
            // guest cannot catch an exception at this call site, so an asynchronous request is passed
            // through unchanged instead of failing the process (same invariant as the broadcast and
            // activity facades: never throw where the platform does not).
            if((Integer)args[3]!=0){
                if(owner.externalServices.asyncObserved.compareAndSet(false,true))android.util.Log.i("TripoderFacade","EXTERNAL_SERVICE_REQUEST_ASYNC_PASSED:interface="+contract+":transaction="+code);
                return original.call(args);
            }
            Parcel request;
            if(RuntimeAppOpsRequest.DESCRIPTOR.equals(contract)){
                if(code!=RuntimeAppOpsRequest.checkOperationRawForDevice())return original.call(args);
                request=RuntimeAppOpsRequest.rebaseCheckOperationRawForDevice((Parcel)args[1],owner.packageName,owner.host.getPackageName());
                if(request==null)return original.call(args);
                android.util.Log.i("TripoderExternalService","APP_OPS_REQUEST_IDENTITY_CONVERTED:transaction="+code);
            }else request=RuntimeExternalServiceRequest.rebase((Parcel)args[1],owner.packageName,owner.host.getPackageName(),contract,code);
            try{Object[] physical=args.clone();physical[1]=request;
                if(owner.externalServices.identityObserved.compareAndSet(false,true))android.util.Log.i("TripoderExternalService","REMOTE_REQUEST_IDENTITY_CONVERTED:interface="+contract+":transaction="+code);
                return original.call(physical);
            }
            finally{request.recycle();}
        }));
    }
}
