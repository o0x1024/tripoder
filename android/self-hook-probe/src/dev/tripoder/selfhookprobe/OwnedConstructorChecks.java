package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

final class OwnedConstructorChecks {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    static JSONObject run()throws Exception {
        check(System.getProperty("tripoder.owned.constructor.initialized")==null,"CONSTRUCTOR_OBSERVATION_NOT_INITIALIZED");
        Class<?> type=Class.forName("dev.tripoder.selfhookprobe.OwnedConstructorTarget",false,OwnedConstructorChecks.class.getClassLoader());
        Constructor<?> constructor=type.getDeclaredConstructor(int.class,String.class,long.class,Object.class,double.class);
        int capacity=ManagedHook.remainingCapacity();Map<Executable,Executable[]> invalid=new LinkedHashMap<>();
        invalid.put(constructor,new Executable[0]);invalid.put(Runnable.class.getDeclaredMethod("run"),new Executable[0]);
        try{ManagedHook.prepareBatch(invalid);throw new IllegalStateException("INVALID_BATCH_ACCEPTED");}
        catch(IllegalArgumentException expected){check("OWNED_MANAGED_TARGET_REQUIRED".equals(expected.getMessage()),"WHOLE_BATCH_PREFLIGHT");}
        check(capacity==ManagedHook.remainingCapacity()&&System.getProperty("tripoder.owned.constructor.initialized")==null,"FAILED_PREFLIGHT_NO_SLOTS_OR_INITIALIZATION");
        Method echo=type.getDeclaredMethod("echo",String.class,int.class);Map<Executable,Executable[]> request=new LinkedHashMap<>();
        request.put(constructor,new Executable[0]);request.put(echo,new Executable[0]);var prepared=ManagedHook.prepareBatch(request);
        ManagedHook.Handle hook=prepared.get(constructor),echoHook=prepared.get(echo);
        check(System.getProperty("tripoder.owned.constructor.initialized")==null,"CONSTRUCTOR_PREPARE_NOT_INITIALIZED");
        AtomicInteger calls=new AtomicInteger();Object root=new Object();
        hook.enable(call->{
            calls.incrementAndGet();OwnedConstructorTarget receiver=(OwnedConstructorTarget)call.receiver();Object[] args=call.arguments();
            check(receiver.number==0&&receiver.parentValue==0&&receiver.text==null,"BEFORE_SUPER_AND_FINAL_INITIALIZATION");
            args[0]=(Integer)args[0]+10;args[1]=args[1]+"!";args[2]=(Long)args[2]+5;args[4]=(Double)args[4]+0.25;
            System.gc();Object result=call.original(args);
            check(result==null&&receiver.number==(Integer)args[0]&&receiver.text.equals(args[1])&&receiver.parentValue==(Long)args[2],"ORIGINAL_CONSTRUCTOR_FINAL_FIELDS");
            try{call.original(args);throw new IllegalStateException("CONSTRUCTOR_ORIGINAL_REPLAYED");}
            catch(IllegalStateException expected){check("ORIGINAL_INVOCATION_NOT_OWNED".equals(expected.getMessage()),"CONSTRUCTOR_ORIGINAL_ONCE");}
            return null;
        });
        check(System.getProperty("tripoder.owned.constructor.initialized")!=null,"CONSTRUCTOR_ACTIVATION_INITIALIZES_CLASS");
        echoHook.enable(call->call.original(call.arguments())+":hook");
        OwnedConstructorTarget direct=new OwnedConstructorTarget(3,"direct",9007199254740993L,root,2.0);
        check(direct.number==13&&direct.parentValue==9007199254740998L&&direct.reference==root&&direct.fraction==2.25,"DIRECT_CONSTRUCTOR_CALL");
        check(direct.echo("e",2).equals("direct!:e:2:hook"),"BATCH_SHARED_LOADER_DIFFERENT_SIGNATURE_AND_RESULT");
        OwnedConstructorTarget reflected=(OwnedConstructorTarget)constructor.newInstance(4,"reflection",9007199254740993L,root,3.0);
        check(reflected.number==14&&reflected.reference==root&&reflected.fraction==3.25,"REFLECTED_CONSTRUCTOR_CALL");
        OwnedConstructorTarget delegated=new OwnedConstructorTarget(5);
        check(delegated.number==15&&delegated.text.equals("delegated!")&&delegated.fraction==1.75,"THIS_CONSTRUCTOR_DELEGATION");
        check(calls.get()==3&&OwnedConstructorTarget.bodies==3&&OwnedConstructorParent.parentCalls==3,"EXACT_CONSTRUCTOR_AND_SUPER_COUNTS");
        hook.disable();hook.enable(call->null);
        try{new OwnedConstructorTarget(6);throw new IllegalStateException("CONSTRUCTOR_WITHOUT_ORIGINAL_RETURNED");}
        catch(IllegalStateException expected){check("CONSTRUCTOR_ORIGINAL_REQUIRED".equals(expected.getMessage()),"SUCCESS_REQUIRES_COMPLETED_ORIGINAL");}
        check(OwnedConstructorTarget.bodies==3&&OwnedConstructorParent.parentCalls==3,"SKIPPED_ORIGINAL_NO_BODY");
        hook.disable();hook.enable(call->{try{call.original(-1,"failure",7L,root,1d);}catch(IllegalArgumentException ignored){}return null;});
        try{constructor.newInstance(7,"swallow",7L,root,1d);throw new IllegalStateException("FAILED_CONSTRUCTOR_WAS_SWALLOWED");}
        catch(InvocationTargetException expected){check(expected.getCause()==OwnedConstructorTarget.failure,"FAILED_ORIGINAL_CANNOT_BECOME_CONSTRUCTED_OBJECT");}
        hook.disable();OwnedConstructorTarget restored=new OwnedConstructorTarget(8,"restored",9L,root,4.0);
        check(restored.number==8&&restored.parentValue==9&&restored.fraction==4.0,"CONSTRUCTOR_AFTER_DISABLE");
        JSONObject nativeReport=new JSONObject(hook.report());
        check(nativeReport.getBoolean("backupRooted")&&!nativeReport.getBoolean("active")&&nativeReport.getLong("originals")==4,"CONSTRUCTOR_ROOTED_BACKUP_ORIGINAL_REPORT");
        check((nativeReport.getLong("targetFlags")&0x10000)!=0&&(nativeReport.getLong("backupFlags")&0x10000)!=0,"REAL_CONSTRUCTOR_FLAGS_PRESERVED");
        echoHook.disable();check(capacity-ManagedHook.remainingCapacity()==2,"CONSTRUCTOR_AND_METHOD_BATCH_SLOTS");
        return new JSONObject().put("checks",checks).put("directCalls",2).put("reflectedCalls",2).put("delegatedCalls",2)
            .put("movingGcRequested",3).put("constructorFlagsPreserved",true).put("native",nativeReport).put("productAccepted",false);
    }
}
