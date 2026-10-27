package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.Objects;
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
        boolean isolated=false;String isolatedInstance=null;
        synchronized(broker){
            source=broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(generation);
            if(kind.equals("unbind"))unbind=broker.services.binding(generation,connection);
            else{
                String pkg=RuntimeWire.required(input,"package");component=RuntimeWire.required(input,"component");target=broker.apks.get(source.identity.spaceId,pkg);
                var declared=RuntimeComponents.findDeclared(target,"service",component,false);
                if(declared==null){Bundle response=new Bundle();response.putBoolean("absent",true);return response;}
                RuntimeComponents.authorize(declared,pkg,source.identity.packageName);process=declared.process;
                if(!Set.of("false","0").contains(declared.attributes.getOrDefault("android:externalService","false")))throw new IllegalStateException("EXTERNAL_SERVICE_NOT_IMPLEMENTED");
                isolated=!Set.of("false","0").contains(declared.attributes.getOrDefault("android:isolatedProcess","false"));
                if(!isolated&&input.containsKey("isolatedInstance"))throw new IllegalArgumentException("ISOLATED_DECLARATION_REQUIRED");
                if(isolated){
                    if(!kind.equals("bind")||(flags&android.content.Context.BIND_AUTO_CREATE)==0||!target.info.packageName.equals(source.identity.packageName))throw new UnsupportedOperationException("ISOLATED_BOUND_SERVICE_SHAPE_NOT_IMPLEMENTED");
                    String requested=input.getString("isolatedInstance");if(requested!=null&&(requested.isEmpty()||requested.length()>200))throw new IllegalArgumentException("ISOLATED_INSTANCE_INVALID");isolatedInstance=requested==null?"DEFAULT":"NAMED:"+requested;
                    RuntimeIsolatedCode.preflight(target);
                }
            }
            if(!isolated){
            if(unbind==null){
                if(kind.equals("stop")){
                    var lease=broker.services.find(source.identity.spaceId,target.info.packageName,component);
                    if(lease==null){Bundle response=new Bundle();response.putBoolean("absent",true);return response;}
                    Bundle response=lease.wire();response.putString("operation",op);return response;
                }
                if(kind.equals("bind")&&(flags&android.content.Context.BIND_AUTO_CREATE)==0){
                    var lease=broker.processes.reservePassiveService(target,process,component);
                    Bundle response=lease.wire();response.putString("operation",op);return response;
                }
            }else{
                String digest=ApkStore.textDigest(kind+"\n"+flags+"\n"+connection+"\n"+(target==null?unbind.id:target.info.packageName+"\n"+component)+"\n"+input.getString("requestDigest",""));
                if(!broker.contract.accept(op,source.identity.key(),digest).created)throw new IllegalStateException("SERVICE_OPERATION_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(op);
                broker.services.operation(op,unbind,generation,kind,connection);broker.isolated.unbinding(unbind.id);Bundle response=unbind.wire();response.putString("operation",op);return response;
            }
            }
        }
        if(isolated)return isolated(input,pid,uid,generation,op,connection,flags,target,process,component,isolatedInstance);
        {
            // start/bind acceptance must not wait for a destination Application or adapter.
            // Persist the owner, then let the caller submit the real Android service request.
            var slot=broker.processes.reserveComponent(target,process);
            synchronized(broker){
                broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(slot.allocation.session.processGeneration);
                if(!slot.install.generation.equals(target.generation))throw new IllegalStateException("STALE_INSTALL");
                var lease=broker.services.reserve(slot,component);
                Bundle response=lease.wire();response.putString("operation",op);return response;
            }
        }
    }
    private Bundle isolated(Bundle input,int pid,int uid,String generation,String op,String connection,int flags,ApkStore.BundleInstall target,String process,String component,String instance)throws Exception{
        // PM Binder calls and full public-code hashing must never hold the
        // authority lock needed by STOP, death observation and other owners.
        var nativeCode=RuntimePublishedNativeCode.capture(broker.host,target);var libraries=RuntimeIsolatedLibraries.capture(broker.host,target,input.getBundle("platformLibraries"));
        var nativeService=RuntimeIsolatedNativeService.capture(Objects.requireNonNull(RuntimeComponents.findDeclared(target,"service",component,false),"ISOLATED_COMPONENT_ABSENT"),nativeCode);
        synchronized(broker){
            var source=broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(generation);
            var current=broker.apks.row(target.space,target.info.packageName);
            if(current==null||!current.state.equals("COMMITTED")||!current.generation.equals(target.generation)||!current.dataId.equals(target.data.getName()))throw new SecurityException("ISOLATED_INSTALL_STALE");
            var declared=RuntimeComponents.findDeclared(target,"service",component,false);
            if(declared==null){Bundle response=new Bundle();response.putBoolean("absent",true);return response;}
            RuntimeComponents.authorize(declared,target.info.packageName,source.identity.packageName);
            if(!target.space.equals(source.identity.spaceId)||!target.info.packageName.equals(source.identity.packageName)||!declared.process.equals(process)||Set.of("false","0").contains(declared.attributes.getOrDefault("android:isolatedProcess","false")))throw new SecurityException("ISOLATED_COMPONENT_CHANGED");
            String digest=ApkStore.textDigest("isolated-bind\n"+flags+"\n"+connection+"\n"+component+"\n"+instance+"\n"+input.getString("requestDigest",""));
            if(!broker.contract.accept(op,source.identity.key(),digest).created)throw new IllegalStateException("SERVICE_OPERATION_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(op);
            try{Bundle response=broker.isolated.reserve(target,process,component,instance,nativeCode,libraries,nativeService);var lease=broker.services.get(response.getString("lease"));broker.services.operation(op,lease,generation,"bind",connection);response.putString("operation",op);return response;}
            catch(Exception error){broker.contract.fail(op,"ISOLATED_SERVICE_RESERVATION_FAILED");throw error;}
        }
    }
    Bundle acknowledge(Bundle input,String generation){String op=RuntimeWire.required(input,"operation");boolean applied=input.getBoolean("applied");broker.services.complete(op,generation,applied);if(applied)broker.contract.succeed(op,"ANDROID_REQUEST_ACCEPTED");else{broker.contract.fail(op,"ANDROID_REQUEST_REJECTED");broker.isolated.rejectedBeforeBootstrap(op,generation);}return new Bundle();}
}
