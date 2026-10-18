package dev.tripoder.runtime;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import dev.tripoder.selfhook.ActivityFrameworkAccess;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.*;
import java.util.*;
/** Framework construction/delivery uses RuntimeActivityCreation's existing
 * Permit and Engine ledgers; this class only owns exact interceptor bindings. */
final class RuntimeSelfHookActivities {
    private static final RuntimeSelfHookActivities PROCESS=new RuntimeSelfHookActivities();
    private final Set<Method> instrumentations=new HashSet<>();
    private RuntimeSelfHookActivities(){}
    private RuntimeManagedInstaller installer;
    private static final Class<?>[] NEW_ACTIVITY={ClassLoader.class,String.class,Intent.class};
    static void creation(RuntimeManagedInstaller installer)throws Exception{
        RuntimeSelfHookActivities value=PROCESS;if(value.installer!=null)throw new IllegalStateException("ACTIVITY_CREATION_INSTALL_ALREADY_ATTEMPTED");value.installer=installer;
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.ACTIVITY_CREATION)){
            if(target instanceof Constructor<?>)callbacks.put(target,(receiver,args,original)->{Object result=original.call(args);value.enroll((Instrumentation)receiver);return result;});
            else switch(target.getName()){
                case "newActivity":{Method method=(Method)target;callbacks.put(target,value.newActivity(method));value.instrumentations.add(method);break;}
                case "performLaunchActivity":callbacks.put(target,(receiver,args,original)->{
                    Intent physical=ActivityFrameworkAccess.recordIntent(args[0]);if(!RuntimeActivityCreation.physical(physical))return original.call(args);
                    RuntimeActivityCreation.Permit permit=RuntimeActivityCreation.begin(physical);
                    try{
                        // A rejected hot launch still runs the real performLaunchActivity so Android registers the
                        // client record for this token; newActivity below returns the terminal host RejectedActivity.
                        // The instrumentation must be enrolled first so that interception (not AppComponentFactory,
                        // which would re-enter begin()) produces that RejectedActivity.
                        value.enroll(ActivityFrameworkAccess.instrumentation());
                        if(!permit.rejected())permit.bindRecord(args[0],ActivityFrameworkAccess.recordToken(args[0]));
                        return original.call(args);
                    }finally{permit.end();}
                });break;
                case "attach":callbacks.put(target,(receiver,args,original)->{
                    Activity activity=(Activity)receiver;if(!RuntimeBridge.owns(activity)){
                        if(RuntimeActivityCreation.physical((Intent)args[6])&&!RuntimeActivityCreation.attachRejected(activity,(Intent)args[6]))throw new SecurityException("ACTIVITY_CREATION_NOT_ADMITTED");return original.call(args);
                    }
                    RuntimeActivityCreation.attaching(activity,(Intent)args[6],(ActivityInfo)args[7],(android.os.IBinder)args[3]);Object[] logical=args.clone();
                    logical[0]=RuntimeBridge.context((Context)args[0]);logical[5]=RuntimeBridge.application();logical[6]=RuntimeBridge.intent(activity);logical[7]=RuntimeBridge.activityInfo(activity);
                    Object result=original.call(logical);attach(activity);RuntimeBridge.attachedActivity(activity);return result;
                });break;
                case "getThemeResource":callbacks.put(target,(receiver,args,original)->{Integer mapped=RuntimeActivityCreation.theme((ActivityInfo)receiver);return mapped==null?original.call(args):mapped;});break;
                default:throw new IllegalStateException("ACTIVITY_CREATION_FIXED_BINDING_UNHANDLED:"+target);
            }
        }
        installer.install(callbacks);value.enroll(ActivityFrameworkAccess.instrumentation());
    }
    private synchronized void enroll(Instrumentation instrumentation)throws Exception{
        Method method=instrumentation.getClass().getMethod("newActivity",NEW_ACTIVITY);if(instrumentations.contains(method))return;
        if(instrumentations.size()>=64)throw new IllegalStateException("INSTRUMENTATION_CLASS_LIMIT");
        installer.install(Map.of(method,newActivity(method)));instrumentations.add(method);
    }
    private RuntimeManagedInstaller.Callback newActivity(Method target){return (receiver,args,original)->{
        RuntimeActivityCreation.Permit permit=RuntimeActivityCreation.current();if(permit==null)return original.call(args);
        if(permit.rejected())return RuntimeActivityCreation.constructRejected((Intent)args[2]);
        boolean base=target.getDeclaringClass()==Instrumentation.class;
        if(permit.constructing())return base?RuntimeActivityCreation.constructDefault((Instrumentation)receiver,(ClassLoader)args[0],(String)args[1],(Intent)args[2]):original.call(args);
        permit.requireArguments((String)args[1],(Intent)args[2]);permit.enter();
        Activity result=base?RuntimeActivityCreation.constructDefault((Instrumentation)receiver,permit.loader(),permit.name(),permit.intent())
            :(Activity)original.call(permit.loader(),permit.name(),permit.intent());return permit.complete(result);
    };}
    static Activity construct(RuntimeActivityCreation.Permit permit)throws Exception{
        if(permit==null||permit!=RuntimeActivityCreation.current())throw new SecurityException("ACTIVITY_CREATION_PERMIT_INVALID");
        Instrumentation instrumentation=ActivityFrameworkAccess.instrumentation();PROCESS.enroll(instrumentation);
        return instrumentation.newActivity(permit.loader(),RuntimeActivitySlots.target(permit.physical.getComponent().getClassName()),permit.physical);
    }
    static void attach(Activity activity){
        if(!RuntimeBridge.owns(activity))throw new SecurityException("NOT_GUEST_ACTIVITY");RuntimeBridge.requireActivityContext(activity.getBaseContext());
        ActivityFrameworkAccess.apply(activity,RuntimeBridge.resources(),RuntimeBridge.application(),RuntimeBridge.intent(activity),RuntimeBridge.activityInfo(activity),RuntimeBridge.theme(activity));
    }
    static void delivery(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.ACTIVITY_DELIVERY))callbacks.put(target,(receiver,args,original)->{
            String name=target.getName();if(receiver instanceof ComponentCaller){ComponentCaller caller=(ComponentCaller)receiver;
                if(!RuntimeBridge.ownsCaller(caller))return original.call(args);if(name.equals("getPackage"))return RuntimeBridge.callerPackage(caller);throw new UnsupportedOperationException("URI_GRANT_NOT_IMPLEMENTED");}
            Activity activity=(Activity)receiver;
            if(name.equals("performNewIntent")){
                if(!RuntimeBridge.owns(activity))return original.call(args);if(args.length==1)throw new UnsupportedOperationException("ACTIVITY_CALLER_DELIVERY_REQUIRED");
                Intent physical=(Intent)args[0];String delivery=physical.getStringExtra("tripoder.delivery");Intent visible=RuntimeBridge.newIntent(activity,physical,(ComponentCaller)args[1]);if(visible==null)return null;
                boolean success=false;Throwable failure=null;
                try{Object result=original.call(visible,args[1]);success=true;return result;}catch(Throwable error){failure=error;throw error;}
                finally{try{RuntimeBridge.newIntentComplete(activity,delivery,success);}catch(Throwable acknowledgement){if(failure==null)throw acknowledgement;if(failure!=acknowledgement)failure.addSuppressed(acknowledgement);}}
            }
            if(name.equals("getCaller")||name.equals("getInitialCaller")||name.equals("getCurrentCaller")){ComponentCaller caller=(ComponentCaller)original.call(args);return RuntimeBridge.owns(activity)?RuntimeBridge.activityCaller(activity,caller,name.equals("getCurrentCaller")):caller;}
            if(!RuntimeBridge.owns(activity))return original.call(args);
            if(name.equals("getReferrer"))return RuntimeBridge.activityReferrer(activity);
            if(name.equals("getCallingActivity"))return RuntimeBridge.callingActivity(activity,(ComponentName)original.call(args));
            if(name.equals("getCallingPackage")){ComponentName caller=activity.getCallingActivity();return caller==null?null:caller.getPackageName();}
            throw new IllegalStateException("ACTIVITY_DELIVERY_FIXED_BINDING_UNHANDLED:"+target);
        });installer.install(callbacks);
    }
    static void routing(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.ACTIVITY_ROUTING))callbacks.put(target,(receiver,args,original)->{
            String name=target.getName();
            if(name.equals("checkStartActivityResult")){RuntimeBridge.activityStartResult(args[1],(Integer)args[0]);return original.call(args);}
            if(name.startsWith("execStartActivit")){
                Class<?>[] types=target.getParameterTypes();boolean supported=name.equals("execStartActivity")&&types.length==7&&types[3]==Activity.class;
                if(!supported)throw new UnsupportedOperationException("ACTIVITY_START_SIGNATURE_UNSUPPORTED");Activity activity=(Activity)args[3];Intent intent=(Intent)args[4];
                // A null Activity is a start through an Application or Service Context.
                if(activity!=null&&!RuntimeBridge.owns(activity))throw new SecurityException("ACTIVITY_START_CALLER_UNOWNED");
                if(intent==null||RuntimeBridge.startsHost(intent))throw new SecurityException("ACTIVITY_START_HOST_COMPONENT_DENIED");
                // A component of a guest in this space is routed through the ledger. Anything else (an implicit action, a real installed app, the system) is the platform's to resolve, as it is for any app.
                // An implicit intent that one of the guest's own filters matches is a start of that component, exactly as Android would resolve it.
                if(intent.getComponent()==null){android.content.ComponentName own=RuntimeBridge.resolveOwnActivity(intent);if(own!=null){intent=new Intent(intent).setComponent(own);args=args.clone();args[4]=intent;}}
                if(intent.getComponent()!=null){
                    try{Object[] routed=args.clone();routed[4]=activity!=null?RuntimeBridge.route(activity,intent,(Integer)args[5]):RuntimeBridge.routeFromContext(intent);return original.call(routed);}
                    catch(android.content.ActivityNotFoundException absent){if(!String.valueOf(absent.getMessage()).startsWith("PACKAGE_NOT_IN_SPACE"))throw absent;}
                }
                // A result requested from the platform (permission dialog, picker) comes back without an authority operation; remember it so the return is accepted.
                if(activity!=null&&args[5] instanceof Integer&&(Integer)args[5]>=0)RuntimeBridge.noteSystemResult(activity,(Integer)args[5]);
                return original.call(args);
            }
            Activity activity=(Activity)receiver;
            if(name.equals("finish")){if(activity.getClass()==RuntimeRejectedActivity.class)return original.call(args);if(!RuntimeBridge.owns(activity))throw new SecurityException("RESULT_CALLER_UNSUPPORTED");RuntimeBridge.returning(activity,ActivityFrameworkAccess.resultCode(activity),ActivityFrameworkAccess.resultData(activity));return original.call(args);}
            String operation=RuntimeBridge.receiving(activity,(Integer)args[1],(Integer)args[2],(ComponentCaller)args[4]);boolean success=false;Throwable failure=null;
            try{Object result=original.call(args);success=true;return result;}catch(Throwable error){failure=error;throw error;}
            finally{try{RuntimeBridge.received(activity,operation,(Integer)args[2],success);}catch(Throwable acknowledgement){if(failure==null)throw acknowledgement;if(failure!=acknowledgement)failure.addSuppressed(acknowledgement);}}
        });installer.install(callbacks);
    }
}
