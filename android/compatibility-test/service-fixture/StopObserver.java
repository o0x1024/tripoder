package dev.tripoder.servicecontract;

import android.app.Service;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.os.IBinder;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Owned-fixture-only transparent observer. Forwards the exact arguments and result once. */
final class StopObserver {
    private static final List<JSONObject> observations=Collections.synchronizedList(new ArrayList<>());
    private static Field fixed(Method lookup,int slot,String name,Class<?> type)throws Exception{
        Field field=(Field)lookup.invoke(null,slot);
        if(field==null||field.getDeclaringClass()!=Service.class||!field.getName().equals(name)||field.getType()!=type||Modifier.isStatic(field.getModifiers()))throw new AssertionError("OWNED_STOP_OBSERVER_FIELD_IDENTITY");
        field.setAccessible(true);return field;
    }
    static void install(Service service)throws Exception{
        ClassLoader runtime;
        try(ContentProviderClient client=service.getContentResolver().acquireContentProviderClient("settings")){
            if(client==null||client.getLocalContentProvider()==null)throw new AssertionError("OWNED_STOP_OBSERVER_RUNTIME_REQUIRED");
            runtime=client.getLocalContentProvider().getClass().getClassLoader();
        }
        Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,runtime),contract=Class.forName("android.app.IActivityManager",false,null);
        Method lookup=access.getDeclaredMethod("fixedField",int.class);lookup.setAccessible(true);
        Field manager=fixed(lookup,12,"mActivityManager",contract),token=fixed(lookup,13,"mToken",IBinder.class);
        Object original=manager.get(service),expectedToken=token.get(service);
        if(original==null||expectedToken==null||!contract.isInstance(original))throw new AssertionError("OWNED_STOP_OBSERVER_ATTACHED_SERVICE_REQUIRED");
        Class<?> bridge=Class.forName("dev.tripoder.runtime.RuntimeBridge",false,runtime);
        ComponentName physical=(ComponentName)bridge.getMethod("serviceComponent",Service.class).invoke(null,service);
        Method members=access.getDeclaredMethod("fixedMembers",int.class);members.setAccessible(true);Method selected=null;
        for(Object member:(Object[])members.invoke(null,14))if(member instanceof Method){Method method=(Method)member;
            if(method.getDeclaringClass()==contract&&method.getName().equals("stopServiceToken")&&method.getReturnType()==boolean.class&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{ComponentName.class,IBinder.class,int.class})){
                if(selected!=null)throw new AssertionError("OWNED_STOP_OBSERVER_AMBIGUOUS_METHOD");selected=method;
            }
        }
        if(selected==null)throw new AssertionError("OWNED_STOP_OBSERVER_STOP_METHOD_REQUIRED");Method stop=selected;stop.setAccessible(true);
        Object observer=Proxy.newProxyInstance(service.getClass().getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
            boolean observed=method.equals(stop);JSONObject event=null;
            if(observed){
                ComponentName component=(ComponentName)args[0];event=new JSONObject().put("service",service.getClass().getSimpleName()).put("startId",args[2])
                    .put("physicalComponent",physical.equals(component)).put("sdkMixedComponent",new ComponentName(service.getPackageName(),physical.getClassName()).equals(component))
                    .put("exactToken",args[1]==expectedToken).put("forwardCount",1).put("observeOnly",true);
            }
            try{Object result=(observed?stop:method).invoke(original,args);if(event!=null){event.put("result",result);observations.add(event);}return result;}
            catch(InvocationTargetException error){if(event!=null){event.put("exception",error.getCause().getClass().getName());observations.add(event);}throw error.getCause();}
        });
        manager.set(service,observer);
    }
    static JSONArray snapshot(){synchronized(observations){return new JSONArray(observations);}}
    private StopObserver(){}
}
