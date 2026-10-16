package dev.tripoder.runtime;

import dalvik.system.DexClassLoader;
import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Executes the actual product observation and Java backend; test transport is explicitly Java-only. */
public final class OwnedJavaBackendChecks {
    private static final String TARGET="dev.tripoder.selfhookprobe.OwnedProductJavaTarget";
    private static int checks;
    private static native boolean bindNative(Class<?> target);
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    private static Map<String,Object> map(Object... args){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
    private static Map<String,Object> selector(String id,String method,String... arguments){return map("id",id,"kind","java","className",TARGET,"methodName",method,"argumentTypes",List.of(arguments));}
    private static Map<String,Object> request(SelfHookControl control,String generation,String command,Object... args){Map<String,Object> m=map("command",command,"processGeneration",generation);m.putAll(map(args));return HookWire.request(control.handle(HookWire.encode(m)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> success(Map<String,Object> response){check(Boolean.TRUE.equals(response.get("ok")),"CONTROL_RESPONSE:"+response);return (Map<String,Object>)response.get("result");}
    @SuppressWarnings("unchecked") private static String evidence(Map<String,Object> response){return (String)((Map<String,Object>)response.get("evidence")).get("id");}
    private static void rejected(Map<String,Object> response,String reason){check(Boolean.FALSE.equals(response.get("ok"))&&String.valueOf(response.get("error")).contains(reason),"CONTROL_REJECTION:"+response);}
    private static String plan(String setup){return "import {definePlan} from '@abox/hook';export default definePlan({id:'ownedProduct',target,params:{},setup(ctx){"+setup+"}});";}
    private static String method(String name,String types){return "ctx.java.method('"+TARGET+"','"+name+"',["+types+"])";}
    public static JSONObject run(String apk,String sha,String generation)throws Exception {
        check(System.getProperty("tripoder.owned.product-java-initialized")==null,"TARGET_NOT_INITIALIZED_BEFORE_START");ManagedHook.initializeProcess();
        ClassLoader loader=new DexClassLoader(apk,null,null,ClassLoader.getSystemClassLoader().getParent());AtomicBoolean live=new AtomicBoolean(true);
        Map<String,Object> identity=map("space","owned-space","installGeneration","owned-install","descriptorSha256",sha,
            "processGeneration",generation,"pid",(double)android.os.Process.myPid(),"target",map("packageName","dev.tripoder.selfhookprobe","versionCode","1","apkSha256",sha));
        RuntimeJavaObservation observation=new RuntimeJavaObservation(()->loader,owner->owner==loader,live::get,identity);
        RuntimeJavaHookBackend backend=new RuntimeJavaHookBackend(observation);
        SelfHookControl.Platform platform=new SelfHookControl.Platform(){
            public Map<String,Object> identity(){return observation.identity();}
            public List<Map<String,Object>> modules(){return List.of();}public List<Map<String,Object>> moduleEvents(){return List.of();}
            public Set<String> capabilities(){return Set.of("java-hook");}
            public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){try{return new SelfHookControl.Observation(backend.observe(selectors),List.of());}catch(Exception error){throw new IllegalStateException(error);}}
            public boolean evidenceLive(HookEvidence evidence){return backend.live(evidence);}
            public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){return backend.prepare(plan);}
            public void discardObservation(){backend.discard();}
        };
        SelfHookControl control=new SelfHookControl(identity,platform,android.os.SystemClock::elapsedRealtime);
        rejected(request(control,generation,"observe","selectors",List.of(map("id","host","kind","java","className","java.lang.System","methodName","currentTimeMillis","argumentTypes",List.of()))),"HOOK_CLASS_OUTSIDE_GUEST");
        success(request(control,generation,"observe","selectors",List.of(selector("native","unresolvedNative","int"))));
        check(observation.nativeMethod("native"),"PRODUCT_OBSERVES_NATIVE_DECLARATION");
        check(bindNative(Class.forName(TARGET,false,loader)),"PRODUCT_REAL_NATIVE_REGISTRATION");
        rejected(request(control,generation,"observe","selectors",List.of(selector("one","increment","int"),selector("two","increment","int"))),"HOOK_DUPLICATE_METHOD_IDENTITY");
        List<Map<String,Object>> selectors=List.of(selector("integer","increment","int"),selector("wide","wide","long"),selector("char","letter","char"),
            selector("text","greet","java.lang.String"),selector("nothing","touch"),selector("instance","instance","int"),selector("failure","throwSame","int"),
            selector("bool","flag","boolean"),selector("byte","tiny","byte"),selector("short","small","short"),selector("float","ratio","float"),selector("double","precise","double"),selector("nativeInt","nativeValue","int"),selector("nativeFloat","nativeFloat","float"),selector("nativeDouble","nativeDouble","double"),selector("unresolved","unresolvedNative","int"));
        Map<String,Object> observed=success(request(control,generation,"observe","selectors",selectors));
        check(System.getProperty("tripoder.owned.product-java-initialized")==null,"OBSERVATION_DOES_NOT_INITIALIZE_TARGET");
        String setup=method("increment","'int'")+".before(({args})=>[args[0]+1]).after(({result})=>result+5);"
            +method("wide","'long'")+".replace(({args})=>args[0]+1n);"+method("letter","'char'")+".replace(()=> '中');"
            +method("greet","'java.lang.String'")+".after(({result})=>result===null?null:result+'?');"
            +method("touch","")+".before(()=>[]);"+method("instance","'int'")+".replace(({args})=>args[0]+2);"
            +method("throwSame","'int'")+".before(({args})=>args);"
            +method("flag","'boolean'")+".replace(({args})=>!args[0]);"+method("tiny","'byte'")+".before(({args})=>[args[0]-1]).after(({result})=>result+2);"
            +method("small","'short'")+".replace(({args})=>args[0]+1);"+method("ratio","'float'")+".before(({args})=>[args[0]+1]);"
            +method("precise","'double'")+".after(({result})=>result*2);"
            +method("nativeValue","'int'")+".before(({args})=>[args[0]+1]).after(({result})=>result+5);"
            +method("nativeFloat","'float'")+".before(({args})=>[args[0]+1]);"
            +method("nativeDouble","'double'")+".after(({result})=>result*2);"
            +method("unresolvedNative","'int'")+".replace(()=>66);";
        Map<String,Object> prepared=success(request(control,generation,"prepare","source",plan(setup),"evidenceId",evidence(observed)));
        check(System.getProperty("tripoder.owned.product-java-initialized")==null,"SOURCE_PREPARATION_DOES_NOT_INITIALIZE_TARGET");
        int capacityBefore=ManagedHook.remainingCapacity();
        Map<String,Object> active=success(request(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check("ACTIVE".equals(active.get("state"))&&"yes".equals(System.getProperty("tripoder.owned.product-java-initialized")),"ACTIVATION_INITIALIZES_TARGET");
        Class<?> target=Class.forName(TARGET,false,loader);Method integer=target.getDeclaredMethod("increment",int.class);
        check((Integer)integer.invoke(null,7)==14,"PRODUCT_INTEGER_BEFORE_AFTER");
        check((Long)target.getDeclaredMethod("wide",long.class).invoke(null,Long.MAX_VALUE-1)==Long.MAX_VALUE,"PRODUCT_LONG_EXACT");
        check((Character)target.getDeclaredMethod("letter",char.class).invoke(null,'a')=='中',"PRODUCT_CHAR");
        check((Boolean)target.getDeclaredMethod("flag",boolean.class).invoke(null,false),"PRODUCT_BOOLEAN");
        check((Byte)target.getDeclaredMethod("tiny",byte.class).invoke(null,(byte)126)==Byte.MAX_VALUE,"PRODUCT_BYTE");
        check((Short)target.getDeclaredMethod("small",short.class).invoke(null,(short)32766)==Short.MAX_VALUE,"PRODUCT_SHORT");
        check((Float)target.getDeclaredMethod("ratio",float.class).invoke(null,16777216f)==16777216f,"PRODUCT_FLOAT_ABI_ROUNDING");
        Method precise=target.getDeclaredMethod("precise",double.class);check((Double)precise.invoke(null,Double.MIN_VALUE)==Double.MIN_VALUE*2,"PRODUCT_DOUBLE");
        check(Double.doubleToRawLongBits((Double)precise.invoke(null,-0.0d))==Long.MIN_VALUE,"PRODUCT_NEGATIVE_ZERO");
        Method text=target.getDeclaredMethod("greet",String.class);check(text.invoke(null,"owned").equals("owned!?"),"PRODUCT_STRING");
        check(text.invoke(null,new Object[]{null})==null,"PRODUCT_NULL_STRING");
        target.getDeclaredMethod("touch").invoke(null);check(target.getField("voidCalls").getInt(null)==1,"PRODUCT_VOID_ORIGINAL");
        Object receiver=target.getConstructor().newInstance();check((Integer)target.getDeclaredMethod("instance",int.class).invoke(receiver,7)==9,"PRODUCT_INSTANCE_RECEIVER");
        Throwable actual=null;try{target.getDeclaredMethod("throwSame",int.class).invoke(null,1);}catch(InvocationTargetException error){actual=error.getCause();}
        check(actual==target.getField("failure").get(null),"PRODUCT_ORIGINAL_THROWABLE_IDENTITY");
        Method nativeInt=target.getDeclaredMethod("nativeValue",int.class),unresolved=target.getDeclaredMethod("unresolvedNative",int.class);
        check((Integer)nativeInt.invoke(null,7)==24,"PRODUCT_NATIVE_INT_ORIGINAL");
        check((Float)target.getDeclaredMethod("nativeFloat",float.class).invoke(null,16777216f)==16777216f,"PRODUCT_FAST_NATIVE_FLOAT");
        Method nativeDouble=target.getDeclaredMethod("nativeDouble",double.class);
        check((Double)nativeDouble.invoke(null,Double.MIN_VALUE)==Double.MIN_VALUE*2,"PRODUCT_CRITICAL_NATIVE_DOUBLE");
        check(Double.doubleToRawLongBits((Double)nativeDouble.invoke(null,-0.0d))==Long.MIN_VALUE,"PRODUCT_CRITICAL_NATIVE_NEGATIVE_ZERO");
        check((Integer)unresolved.invoke(null,1)==66,"PRODUCT_REPLACE_UNRESOLVED_NATIVE");
        String revision=(String)active.get("revision");Map<String,Object> disabled=success(request(control,generation,"disable","revision",revision));
        check("DISABLED".equals(disabled.get("state"))&&(Integer)integer.invoke(null,7)==8,"PRODUCT_DISABLE_RESTORES_ORIGINAL");
        check((Integer)nativeInt.invoke(null,7)==18,"PRODUCT_NATIVE_DISABLE_CURRENT_BINDING");
        try{unresolved.invoke(null,1);throw new IllegalStateException("PRODUCT_UNRESOLVED_RETURNED");}
        catch(InvocationTargetException error){check(error.getCause() instanceof UnsatisfiedLinkError,"PRODUCT_NATIVE_DISABLE_LOOKUP_EXCEPTION");}
        check(capacityBefore-ManagedHook.remainingCapacity()==16,"PRODUCT_SIXTEEN_OWNED_HANDLES");int retainedCapacity=ManagedHook.remainingCapacity();
        observed=success(request(control,generation,"observe","selectors",List.of(selector("renamed","increment","int"))));
        prepared=success(request(control,generation,"prepare","evidenceId",evidence(observed),"source",plan(method("increment","'int'")+".replace(({args})=>args[0]+33);")));
        active=success(request(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check((Integer)integer.invoke(null,7)==40&&ManagedHook.remainingCapacity()==retainedCapacity,"PRODUCT_HANDLE_REUSED_BY_METHOD_IDENTITY");
        success(request(control,generation,"disable","revision",active.get("revision")));
        observed=success(request(control,generation,"observe","selectors",List.of(selector("stale","increment","int"))));live.set(false);
        rejected(request(control,generation,"prepare","evidenceId",evidence(observed),"source",plan(method("increment","'int'")+".replace(()=>1);")),"HOOK_EVIDENCE_STALE");
        return new JSONObject().put("checks",checks).put("methods",16).put("nativeDeclarations",4).put("activations",2).put("handleIdentityReuse",true).put("observationDidNotInitialize",true)
            .put("originalExceptionIdentity",true).put("actualProductJavaBackend",true).put("productAuthorityConnected",false).put("nativeBackendConnected",false).put("productAccepted",false);
    }
}
