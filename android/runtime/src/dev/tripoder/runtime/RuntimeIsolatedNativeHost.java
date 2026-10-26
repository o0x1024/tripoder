package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import dev.tripoder.selfhook.NativeServiceHost;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** The guest's {@code nativeService} inside the real isolated process: no Application or Service object
 * exists, as on the platform. The entry point and library are the exact ones the authority admitted. */
final class RuntimeIsolatedNativeHost {
    private final NativeServiceHost host;private final ComponentName logical;private final Map<Intent.FilterComparison,Long> tokens=new HashMap<>();private long next=1;
    RuntimeIsolatedNativeHost(Bundle bootstrap,String packageName,String component)throws Exception{
        if(!android.os.Process.isIsolated())throw new SecurityException("ISOLATED_PROCESS_REQUIRED");
        RuntimePlatform.requireValidated();System.loadLibrary("tripoderselfhook");
        Bundle service=Objects.requireNonNull(bootstrap.getBundle("nativeService"),"ISOLATED_NATIVE_SERVICE_REQUIRED");
        Bundle publication=Objects.requireNonNull(bootstrap.getBundle("nativeCode"),"ISOLATED_NATIVE_PUBLICATION_REQUIRED");
        String library=RuntimeIsolatedNativeService.library(service),function=RuntimeIsolatedNativeService.function(service);
        // The same exact-file verification the Java guest path performs. The hold lasts as long as the process.
        var verified=RuntimePublishedNativeCode.verify(publication,RuntimeWire.required(bootstrap,"space"),packageName,RuntimeWire.required(bootstrap,"installGeneration"),RuntimeWire.required(bootstrap,"descriptorSha"));
        var located=verified.locate(library);
        logical=new ComponentName(packageName,component);
        host=NativeServiceHost.start(located.file.fd.getFd(),located.offset,library,function);
        android.util.Log.i("TripoderIsolated","NATIVE_SERVICE_STARTED package="+packageName+" component="+component+" library="+library+" function="+function+" uid="+android.os.Process.myUid()+" pid="+android.os.Process.myPid()+" isolated="+android.os.Process.isIsolated());
    }
    private long token(Intent physical){return tokens.computeIfAbsent(new Intent.FilterComparison(physical),key->next++);}
    /** A native service sees only the action; data, extras and flags are not part of its contract. */
    private String action(Intent physical){return RuntimeServiceIntent.delivery(physical,logical,RuntimeIsolatedNativeHost.class.getClassLoader()).getAction();}
    IBinder onBind(Intent physical){
        long token=token(physical);String action=action(physical);IBinder binder=host.onBind(token,action,null);
        String descriptor;try{descriptor=binder==null?null:binder.getInterfaceDescriptor();}catch(android.os.RemoteException error){descriptor="(unavailable)";}
        android.util.Log.i("TripoderIsolated","NATIVE_SERVICE_BOUND token="+token+" action="+action+" descriptor="+descriptor);
        return binder;
    }
    boolean onUnbind(Intent physical){return host.onUnbind(token(physical));}
    void onRebind(Intent physical){host.onRebind(token(physical));}
    void onTrimMemory(int level){host.onTrimMemory(level);}
    void onDestroy(){host.onDestroy();android.util.Log.i("TripoderIsolated","NATIVE_SERVICE_DESTROYED pid="+android.os.Process.myPid());}
}
