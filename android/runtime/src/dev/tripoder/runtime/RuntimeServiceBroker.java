package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.Set;

/** Service admission and execution acknowledgement. Android remains the lifecycle scheduler. */
final class RuntimeServiceBroker {
    private final RuntimeBroker broker;
    RuntimeServiceBroker(RuntimeBroker broker){this.broker=broker;}
    Bundle route(Bundle input,int pid,int uid)throws Exception{
        String generation=RuntimeWire.required(input,"generation"),kind=RuntimeWire.required(input,"kind"),op=RuntimeWire.required(input,"operation"),connection=input.getString("connection","");
        if(!Set.of("start","stop","bind","unbind").contains(kind))throw new IllegalArgumentException("SERVICE_OPERATION_UNSUPPORTED");
        if(kind.equals("bind")||kind.equals("unbind"))java.util.UUID.fromString(connection);
        int flags=input.getInt("flags",0);if(kind.equals("bind"))RuntimeServiceBinding.validate(flags);else if(flags!=0)throw new IllegalArgumentException("SERVICE_NON_BIND_FLAGS_INVALID");
        ExecutionContract.Session source;ApkStore.BundleInstall target=null;String process=null,component=null;ServiceLedger.Lease unbind=null;
        synchronized(broker){
            source=broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(generation);
            if(kind.equals("unbind"))unbind=broker.services.binding(generation,connection);
            else{
                String pkg=RuntimeWire.required(input,"package");component=RuntimeWire.required(input,"component");target=broker.apks.get(source.identity.spaceId,pkg);
                var declared=RuntimeComponents.declared(target,"service",component);RuntimeComponents.authorize(declared,pkg,source.identity.packageName);process=declared.process;
                if(!Set.of("false","0").contains(declared.attributes.getOrDefault("android:isolatedProcess","false")))throw new IllegalStateException("ISOLATED_SERVICE_NOT_IMPLEMENTED");
                if(!Set.of("false","0").contains(declared.attributes.getOrDefault("android:externalService","false")))throw new IllegalStateException("EXTERNAL_SERVICE_NOT_IMPLEMENTED");
            }
            String digest=ApkStore.textDigest(kind+"\n"+flags+"\n"+connection+"\n"+(target==null?unbind.id:target.info.packageName+"\n"+component)+"\n"+input.getString("requestDigest",""));
            if(!broker.contract.accept(op,source.identity.key(),digest).created)throw new IllegalStateException("SERVICE_OPERATION_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(op);
            if(unbind!=null){broker.services.operation(op,unbind,generation,kind,connection);Bundle response=unbind.wire();response.putString("operation",op);return response;}
            if(kind.equals("stop")){
                // Stopping an absent/restarting service must never bootstrap its Application.
                var lease=broker.services.find(source.identity.spaceId,target.info.packageName,component);
                if(lease==null){broker.contract.succeed(op,"NO_SERVICE_RECORD");Bundle response=new Bundle();response.putBoolean("absent",true);return response;}
                broker.services.operation(op,lease,generation,kind,connection);Bundle response=lease.wire();response.putString("operation",op);return response;
            }
            if(kind.equals("bind")&&(flags&android.content.Context.BIND_AUTO_CREATE)==0){
                try{var lease=broker.processes.reservePassiveService(target,process,component);broker.services.operation(op,lease,generation,kind,connection);Bundle response=lease.wire();response.putString("operation",op);return response;}
                catch(Exception error){broker.contract.fail(op,"PASSIVE_SERVICE_RESERVATION_FAILED");throw error;}
            }
        }
        try{
            // start/bind acceptance must not wait for a destination Application or adapter.
            // Persist the owner, then let the caller submit the real Android service request.
            var slot=broker.processes.reserveComponent(target,process);
            synchronized(broker){
                broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(slot.allocation.session.processGeneration);
                if(!slot.install.generation.equals(target.generation))throw new IllegalStateException("STALE_INSTALL");
                var lease=broker.services.reserve(slot,component);broker.services.operation(op,lease,generation,kind,connection);Bundle response=lease.wire();response.putString("operation",op);return response;
            }
        }catch(Exception error){synchronized(broker){broker.contract.markUnknown(op,"SERVICE_PREPARATION_UNCONFIRMED");}throw error;}
    }
    Bundle acknowledge(Bundle input,String generation){String op=RuntimeWire.required(input,"operation");boolean applied=input.getBoolean("applied");broker.services.complete(op,generation,applied);if(applied)broker.contract.succeed(op,"ANDROID_REQUEST_ACCEPTED");else broker.contract.fail(op,"ANDROID_REQUEST_REJECTED");return new Bundle();}
}
