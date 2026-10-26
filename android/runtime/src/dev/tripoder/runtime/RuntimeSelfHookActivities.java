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
    private final Set<Class<?>> instrumentations=Collections.newSetFromMap(new IdentityHashMap<>());
    private RuntimeSelfHookActivities(){}
    private RuntimeManagedInstaller installer;
    static void creation(RuntimeManagedInstaller installer)throws Exception{
        RuntimeSelfHookActivities value=PROCESS;if(value.installer!=null)throw new IllegalStateException("ACTIVITY_CREATION_INSTALL_ALREADY_ATTEMPTED");value.installer=installer;
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.ACTIVITY_CREATION)){
            if(target instanceof Constructor<?>)callbacks.put(target,(receiver,args,original)->{Object result=original.call(args);value.enroll((Instrumentation)receiver);return result;});
            else switch(target.getName()){
                case "preExecute":callbacks.put(target,(receiver,args,original)->{
                    prepareDelivery(receiver,args[0]);return original.call(args);
                });break;
                case "newActivity":{Method method=(Method)target;callbacks.put(target,value.newActivity(method));value.instrumentations.add(method.getDeclaringClass());break;}
                case "performLaunchActivity":callbacks.put(target,(receiver,args,original)->{
                    Intent physical=ActivityFrameworkAccess.recordIntent(args[0]);
                    if(RuntimeActivityCreation.physical(physical)){
                        ClassLoader extras=RuntimeSelfHookApplication.intentExtrasLoader();
                        if(extras!=null)physical.setExtrasClassLoader(extras);
                    }
                    if(!RuntimeActivityCreation.physical(physical)){
                        // The record's component may be the guest-visible name the platform resolved from the
                        // ActivityInfo it was given; its lease still names this process's slot. Adopt the lease.
                        Intent leased=RuntimeEngine.current==null?null:RuntimeEngine.current.leasedRecord(physical);
                        if(leased==null){
                            android.util.Log.w("TripoderActivities","unleased-record component="
                                +(physical==null||physical.getComponent()==null?"none":physical.getComponent().flattenToShortString())
                                +" ticket="+(physical==null?"none":String.valueOf(physical.getStringExtra("tripoder.ticket"))));
                            return original.call(args);
                        }
                        android.util.Log.i("TripoderActivities","adopted-record component="
                            +(physical.getComponent()==null?"none":physical.getComponent().flattenToShortString())
                            +" stub="+leased.getComponent().getClassName());
                        physical=leased;
                    }
                    RuntimeActivityCreation.Permit permit=RuntimeActivityCreation.begin(physical);
                    try{
                        // A rejected hot launch still runs the real performLaunchActivity so Android registers the
                        // client record for this token; newActivity below returns the terminal host RejectedActivity.
                        // The instrumentation must be enrolled first so that interception (not AppComponentFactory,
                        // which would re-enter begin()) produces that RejectedActivity.
                        value.enroll(ActivityFrameworkAccess.instrumentation());
                        if(!permit.rejected()){
                            permit.bindRecord(args[0],ActivityFrameworkAccess.recordToken(args[0]));
                            // ActivityClientRecord.state is unparcelled lazily by the framework. Guest
                            // view states (e.g. custom View.BaseSavedState) must resolve through the
                            // guest loader, not the boot loader, or Activity restore aborts the process.
                            Bundle restored=ActivityFrameworkAccess.recordState(args[0]);
                            if(restored!=null)restored.setClassLoader(permit.loader());
                        }
                        RuntimeEngine engine=RuntimeEngine.current;if(engine!=null)engine.packages.traceEndpoints("BEFORE_FRAMEWORK_ACTIVITY_CREATE");
                        Object result=original.call(args);
                        if(result instanceof Activity&&RuntimeBridge.owns((Activity)result))RuntimeEngine.current.activityCreated((Activity)result);
                        return result;
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
    /** Android calls this on its scheduling thread before the guest Handler
     * callback can inspect the transaction. No creation permit or guest code is
     * entered here; the later factory claim still owns admission. */
    private static void prepareDelivery(Object item,Object client)throws Exception{
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null)return;
        Intent physical=ActivityFrameworkAccess.launchIntent(item);
        if(!RuntimeActivityCreation.physical(physical))return;
        if(!ActivityFrameworkAccess.physicalClient(client))throw new SecurityException("ACTIVITY_CONSTRUCTION_CLIENT_MISMATCH");
        Bundle view=engine.brokerCall(RuntimeWire.ACTIVITY_RECORD,RuntimeActivityConstruction.request(engine.generation,physical,engine.guestLoader()));
        String stub=RuntimeWire.required(view,"stub");
        if(!new ComponentName(engine.host,stub).equals(physical.getComponent()))throw new SecurityException("ACTIVITY_CONSTRUCTION_STUB_MISMATCH");
        // Revocation has a terminal host path in the subsequent factory. It
        // grants no guest construction metadata and is never acknowledged here.
        if(view.getBoolean("closed"))return;
        String target=RuntimeWire.required(view,"constructionTarget");
        ActivityInfo projected=RuntimeActivityConstructionInfo.project(ActivityFrameworkAccess.launchInfo(item),engine.host.getPackageName(),android.os.Process.myUid(),stub,target);
        ActivityFrameworkAccess.launchInfo(item,projected);
        android.util.Log.i("TripoderActivityDelivery","constructionTarget="+target+";logicalAlias="+!target.equals(RuntimeWire.required(view,"component")));
    }
    private synchronized void enroll(Instrumentation instrumentation)throws Exception{
        Method method=RuntimeInstrumentationMethods.construction(instrumentation.getClass());if(instrumentations.contains(method.getDeclaringClass()))return;
        if(instrumentations.size()>=64)throw new IllegalStateException("INSTRUMENTATION_CLASS_LIMIT");
        // The permanent performLaunchActivity backup is interpreted. Its exact
        // virtual descriptor enters this method, including a covariant bridge;
        // late guest callbacks use the existing lifecycle publication policy.
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        installer.installLifecycle(Map.of(method,newActivity(method)));instrumentations.add(method.getDeclaringClass());
        android.util.Log.i("TripoderInstrumentation","owner="+method.getDeclaringClass().getName()+";elapsedNs="+(android.os.SystemClock.elapsedRealtimeNanos()-started));
    }
    private RuntimeManagedInstaller.Callback newActivity(Method target){return (receiver,args,original)->{
        RuntimeActivityCreation.Permit permit=RuntimeActivityCreation.current();
        if(permit==null){
            // The platform asked for an activity this process never leased. Log the loader it would have used; this is
            // the difference between "the guest class is missing" and "the runtime handed the platform the wrong world".
            android.util.Log.w("TripoderActivities","newActivity without permit name="+args[1]
                +" loader="+RuntimeSelfHookApplication.loaderChain((ClassLoader)args[0]));
            return original.call(args);
        }
        if(permit.rejected())return RuntimeActivityCreation.constructRejected((Intent)args[2]);
        boolean base=target.getDeclaringClass()==Instrumentation.class;
        if(permit.constructing())return base?RuntimeActivityCreation.constructDefault((Instrumentation)receiver,(ClassLoader)args[0],(String)args[1],(Intent)args[2]):original.call(args);
        try{permit.requireArguments((String)args[1],(Intent)args[2]);}
        catch(SecurityException rejected){
            if("ACTIVITY_CREATION_ARGUMENTS_MISMATCH".equals(rejected.getMessage()))permit.rejectedArguments(target,receiver,(ClassLoader)args[0],(String)args[1],(Intent)args[2]);
            throw rejected;
        }
        permit.enter();
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
        // attachBaseContext may install the application's own resource wrapper
        // (e.g. packed XML decoding). ContextThemeWrapper caches that context's
        // resources; replacing them with the engine root erases guest behavior.
        android.content.res.Resources resources=java.util.Objects.requireNonNull(activity.getBaseContext().getResources(),"ACTIVITY_CONTEXT_RESOURCES_REQUIRED");
        android.util.Log.i("TripoderResources","activity="+activity.getClass().getName()+";base="+activity.getBaseContext().getClass().getName()+";resources="+resources.getClass().getName());
        ActivityFrameworkAccess.apply(activity,resources,RuntimeBridge.application(),RuntimeBridge.intent(activity),RuntimeBridge.activityInfo(activity),RuntimeBridge.theme(activity));
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
            if(name.equals("setTaskDescription")){
                ActivityManager.TaskDescription decorated;
                try{decorated=RuntimeEngine.current.taskPresentation.decorate((ActivityManager.TaskDescription)args[0]);}
                catch(RuntimeException error){android.util.Log.w("TripoderActivities","guest-task-badge",error);return original.call(args);}
                return original.call(decorated);
            }
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
                Class<?>[] types=target.getParameterTypes();
                boolean supported=name.equals("execStartActivity")&&types.length>=7&&types[4]==Intent.class&&(types[3]==Activity.class||types[3]==String.class);
                if(!supported)throw new UnsupportedOperationException("ACTIVITY_START_SIGNATURE_UNSUPPORTED:"+name+":"+target);
                Activity activity=args[3] instanceof Activity?(Activity)args[3]:args[0] instanceof Activity?(Activity)args[0]:null;
                Intent intent=(Intent)args[4];
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
            if(name.equals("finish")){if(activity.getClass()==RuntimeRejectedActivity.class)return original.call(args);if(!RuntimeBridge.owns(activity)){if(activity.isDestroyed())return original.call(args);throw new SecurityException("RESULT_CALLER_UNSUPPORTED");}RuntimeBridge.returning(activity,ActivityFrameworkAccess.resultCode(activity),ActivityFrameworkAccess.resultData(activity));return original.call(args);}
            String operation=RuntimeBridge.receiving(activity,(Integer)args[1],(Integer)args[2],(ComponentCaller)args[4]);boolean success=false;Throwable failure=null;
            try{
                // Unparcel application-defined results in the admitted receiver's current
                // loader, including a legitimate packed/hot-fixed Application loader.
                if(args[3] instanceof Intent data)data.setExtrasClassLoader(RuntimeEngine.current.guestLoader());
                Object result=original.call(args);success=true;return result;
            }catch(Throwable error){failure=error;throw error;}
            finally{try{RuntimeBridge.received(activity,operation,(Integer)args[2],success);}catch(Throwable acknowledgement){if(failure==null)throw acknowledgement;if(failure!=acknowledgement)failure.addSuppressed(acknowledgement);}}
        });installer.install(callbacks);
    }
}
