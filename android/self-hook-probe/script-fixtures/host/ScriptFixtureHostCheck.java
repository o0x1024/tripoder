package dev.tripoder.selfhookprobe;

import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.dex.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Host codec/body/compiler oracle. Does not load or claim ART interception. Exclude from APK. */
public final class ScriptFixtureHostCheck {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("fixture host "+checks);}
    public static void main(String[] args)throws Throwable{
        Object exact=ScriptFixtureCodec.decode("long",9007199254740993L);check(exact.equals(new BigInteger("9007199254740993")));
        check(ScriptFixtureCodec.encode("long",exact).equals(9007199254740993L));
        check(ScriptFixtureCodec.encode("float",16777217d).equals(16777216f));
        check(Float.floatToRawIntBits((Float)ScriptFixtureCodec.encode("float",-0d))==Integer.MIN_VALUE);
        check(Double.doubleToRawLongBits((Double)ScriptFixtureCodec.decode("float",-0f))==Long.MIN_VALUE);
        int before=ScriptOwnedFixture.intCalls.get();check(ScriptOwnedCaller.callScriptInt(7)==8&&ScriptOwnedFixture.intCalls.get()==before+1);
        check(ScriptOwnedCaller.callScriptLong(9007199254740993L)==9007199254740993L);
        try{ScriptOwnedCaller.callScriptFailure(-1);throw new AssertionError("missing throwable");}catch(RuntimeException expected){check(expected==ScriptOwnedFixture.MARKER);}
        Map<String,Object> target=Map.of("packageName","owned.host","versionCode","1","apkSha256","a".repeat(64));
        Map<String,Object> symbol=Map.of("id","integer","kind","java","loaded",true,"className",ScriptOwnedFixture.class.getName(),"methodName","scriptIntOwned","argumentTypes",List.of("int"),"returnType","int","classLoaderId","owned-host");
        HookEvidence evidence=new HookEvidence(Map.of("id","host","source","fixture","target",target,"processInstanceId","owned-host:1","abi","arm64-v8a","capabilities",List.of("java-hook"),"symbols",List.of(symbol)));
        String source="import{definePlan}from'@abox/hook';export default definePlan({id:'host',target,params:{},setup(ctx){ctx.java.method(symbols.integer.className,'scriptIntOwned',['int']).before(({args})=>[args[0]+1]).after(({result})=>result+2);}});";
        DraftCompiler.Plan plan=DraftCompiler.compile(source,evidence);before=ScriptOwnedFixture.intCalls.get();
        Object result=plan.rules().get(0).execute(Map.of(),List.of(7d),values->ScriptFixtureCodec.decode("int",ScriptOwnedCaller.callScriptInt((Integer)ScriptFixtureCodec.encode("int",values.get(0)))));
        check(result.equals(11d)&&ScriptOwnedFixture.intCalls.get()==before+1);
        DexFile dex=new DexFile(Files.readAllBytes(Path.of(args[0])));DexCallerClosure graph=new DexCallerClosure(List.of(dex));
        String[] targets={"scriptIntOwned","scriptLongOwned","scriptFloatOwned","scriptFailureOwned"};
        String[] callers={"callScriptInt","callScriptLong","callScriptFloat","callScriptFailure"};String[] descriptors={"(I)I","(J)J","(F)F","(I)I"};
        for(int i=0;i<targets.length;i++){
            List<DexFile.MethodRef> found=graph.candidates("Ldev/tripoder/selfhookprobe/ScriptOwnedFixture;->"+targets[i]+descriptors[i]);
            check(found.size()==1&&found.get(0).key().equals("Ldev/tripoder/selfhookprobe/ScriptOwnedCaller;->"+callers[i]+descriptors[i]));
        }
        check(!graph.completeRuntimeCoverage);
        System.out.println("OWNED_SCRIPT_FIXTURE_HOST_PASS checks="+checks+" dex="+dex.sha256+" deviceTested=false");
    }
}
