package dev.tripoder.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Process-local bindings to declared services of the selected platform WebView.
 * Android owns renderer isolation/lifetime; no guest Service slot is fabricated. */
final class RuntimeWebViewServices {
    private final RuntimeEngine engine;
    private final RuntimeWebView provider;
    private final Set<ServiceConnection> connections=Collections.newSetFromMap(new IdentityHashMap<>());
    RuntimeWebViewServices(RuntimeEngine engine,RuntimeWebView provider){this.engine=engine;this.provider=provider;}
    boolean targets(Intent intent){return intent!=null&&intent.getComponent()!=null&&provider.owns(intent.getComponent().getPackageName());}
    synchronized boolean owns(ServiceConnection connection){return connections.contains(connection);}
    synchronized boolean bind(Intent supplied,ServiceConnection connection,long flags,Executor executor,String instance){
        if(!targets(supplied))throw new SecurityException("WEBVIEW_SERVICE_TARGET_REQUIRED");
        Intent intent=new Intent(supplied);ServiceInfo service=provider.serviceInfo(intent.getComponent(),PackageManager.GET_META_DATA,RuntimePackageBridge.userId());
        if(instance!=null&&((service.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)==0||instance.isEmpty()||instance.length()>200))
            throw new SecurityException("WEBVIEW_ISOLATED_RENDERER_REQUIRED");
        Objects.requireNonNull(connection,"WEBVIEW_SERVICE_CONNECTION_REQUIRED");
        if(!connections.add(connection))throw new IllegalStateException("WEBVIEW_CONNECTION_ALREADY_REGISTERED");
        // Retain registration on an exceptional/unknown platform outcome. Only
        // false or a successful unbind confirms release; never replay a bind.
        boolean bound;
        if(instance!=null){
            // ContextWrapper exposes only the int isolated overload; its ContextImpl applies the same BIND_EXTERNAL_SERVICE long mapping.
            long external=flags&Context.BIND_EXTERNAL_SERVICE_LONG;long rest=flags&~Context.BIND_EXTERNAL_SERVICE_LONG;
            if(rest<0||rest>0xFFFFFFFFL||(rest&Integer.toUnsignedLong(Context.BIND_EXTERNAL_SERVICE))!=0)throw new UnsupportedOperationException("WEBVIEW_ISOLATED_BIND_FLAGS_UNSUPPORTED:0x"+Long.toHexString(flags));
            int physical=(int)rest;if(external!=0)physical|=Context.BIND_EXTERNAL_SERVICE;
            bound=engine.host.bindIsolatedService(intent,physical,instance,Objects.requireNonNull(executor),connection);
        }
        else if(executor!=null)bound=engine.host.bindService(intent,Context.BindServiceFlags.of(flags),executor,connection);
        else bound=engine.host.bindService(intent,connection,Context.BindServiceFlags.of(flags));
        if(!bound)connections.remove(connection);return bound;
    }
    synchronized void unbind(ServiceConnection connection){require(connection);engine.host.unbindService(connection);connections.remove(connection);}
    synchronized void rebind(ServiceConnection connection,Context.BindServiceFlags flags){require(connection);engine.host.rebindService(connection,flags);}
    synchronized void updateGroup(ServiceConnection connection,int group,int importance){require(connection);engine.host.updateServiceGroup(connection,group,importance);}
    private void require(ServiceConnection connection){if(!connections.contains(connection))throw new SecurityException("WEBVIEW_CONNECTION_NOT_OWNED");}
}
