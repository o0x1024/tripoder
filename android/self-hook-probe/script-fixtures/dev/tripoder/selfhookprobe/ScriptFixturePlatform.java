package dev.tripoder.selfhookprobe;

import android.os.Process;
import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.dex.DexCallerClosure;
import dev.tripoder.selfhook.dex.DexFile;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Explicit owned-only Platform, not the production backend. No arbitrary target or caller acceptance. */
final class ScriptFixturePlatform implements SelfHookControl.Platform {
    record Target(String id,String name,String callerName,String type,Class<?> primitive,String descriptor,Method method,Method caller) {}
    private final Map<String,Object> identity;
    private final Map<String,Target> targets=new LinkedHashMap<>();
    private final Map<String,ManagedHook.Handle> handles=new LinkedHashMap<>();
    private final File directory;
    private final ClassLoader loader=ManagedHook.class.getClassLoader();
    private final String loaderId=UUID.randomUUID().toString();
    final JSONObject callerEvidence=new JSONObject();
    final JSONArray publicationEvents=new JSONArray();
    int prepares,publishes,retires;
    private boolean observed;
    ScriptFixturePlatform(Map<String,Object> identity,String apk,File directory,boolean explicitOwnedScope)throws Exception{
        ProbeFiles.require(explicitOwnedScope,"OWNED_SCRIPT_SCOPE_REQUIRED");this.identity=Collections.unmodifiableMap(new LinkedHashMap<>(identity));this.directory=directory;
        ProbeFiles.require(ScriptOwnedFixture.class.getClassLoader()==loader&&ScriptOwnedCaller.class.getClassLoader()==loader,"OWNED_SCRIPT_LOADER");
        add("integer","scriptIntOwned","callScriptInt","int",int.class,"(I)I");add("wide","scriptLongOwned","callScriptLong","long",long.class,"(J)J");
        add("single","scriptFloatOwned","callScriptFloat","float",float.class,"(F)F");add("failure","scriptFailureOwned","callScriptFailure","int",int.class,"(I)I");
        DexFile dex;try(ZipFile zip=new ZipFile(apk)){
            ZipEntry entry=zip.getEntry("classes.dex");ProbeFiles.require(entry!=null&&entry.getSize()>0&&entry.getSize()<=DexFile.MAX_BYTES&&zip.getEntry("classes2.dex")==null,"OWNED_SCRIPT_SINGLE_DEX");
            try(InputStream in=zip.getInputStream(entry)){byte[] bytes=in.readNBytes(DexFile.MAX_BYTES+1);ProbeFiles.require(bytes.length==entry.getSize(),"OWNED_SCRIPT_DEX_SIZE");dex=new DexFile(bytes);}
        }
        DexCallerClosure closure=new DexCallerClosure(Collections.singletonList(dex));JSONArray accepted=new JSONArray();
        for(Target target:targets.values()){
            String key="Ldev/tripoder/selfhookprobe/ScriptOwnedFixture;->"+target.name()+target.descriptor();
            List<DexFile.MethodRef> candidates=closure.candidates(key);String expected="Ldev/tripoder/selfhookprobe/ScriptOwnedCaller;->"+target.callerName()+target.descriptor();
            ProbeFiles.require(candidates.size()==1&&candidates.get(0).key().equals(expected),"OWNED_SCRIPT_CALLER_SET_CHANGED:"+target.id());
            accepted.put(new JSONObject().put("symbol",target.id()).put("target",key).put("caller",expected));
        }
        callerEvidence.put("dexSha256",dex.sha256).put("accepted",accepted).put("dynamicSites",closure.dynamicSites).put("externalSites",closure.externalSites)
            .put("completeRuntimeCoverage",false).put("scope","four-owned-direct-callers-only").put("productBackend",false).put("classLoaderId",loaderId).put("definingLoaderBound",true);
    }
    private void add(String id,String name,String callerName,String type,Class<?> primitive,String descriptor)throws Exception{
        Method method=ScriptOwnedFixture.class.getDeclaredMethod(name,primitive),caller=ScriptOwnedCaller.class.getDeclaredMethod(callerName,primitive);
        ProbeFiles.require(method.getReturnType()==primitive&&caller.getReturnType()==primitive,"OWNED_SCRIPT_SIGNATURE");targets.put(id,new Target(id,name,callerName,type,primitive,descriptor,method,caller));
    }
    Collection<Target> targets(){return Collections.unmodifiableCollection(targets.values());}
    Target target(String id){Target value=targets.get(id);ProbeFiles.require(value!=null,"OWNED_SCRIPT_TARGET_REQUIRED");return value;}
    List<Map<String,Object>> selectors(){List<Map<String,Object>> result=new ArrayList<>();for(Target t:targets.values())result.add(map("id",t.id(),"kind","java","className",ScriptOwnedFixture.class.getName(),"methodName",t.name(),"argumentTypes",List.of(t.type())));return result;}
    @Override public Map<String,Object> identity(){return identity;}
    @Override public List<Map<String,Object>> modules(){return List.of();}
    @Override public List<Map<String,Object>> moduleEvents(){return List.of();}
    @Override public Set<String> capabilities(){return Set.of("java-hook");}
    @Override public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){
        List<Map<String,Object>> symbols=new ArrayList<>();for(Map<String,Object> selector:selectors){
            Target target=target(String.valueOf(selector.get("id")));
            ProbeFiles.require("java".equals(selector.get("kind"))&&ScriptOwnedFixture.class.getName().equals(selector.get("className"))&&target.name().equals(selector.get("methodName"))&&List.of(target.type()).equals(selector.get("argumentTypes")),"OWNED_SCRIPT_TARGET_REQUIRED");
            symbols.add(map("id",target.id(),"kind","java","loaded",true,"className",ScriptOwnedFixture.class.getName(),"methodName",target.name(),"argumentTypes",List.of(target.type()),"returnType",target.type(),"classLoaderId",loaderId));
        }
        observed=true;return new SelfHookControl.Observation(List.copyOf(symbols),List.of());
    }
    @Override public boolean evidenceLive(HookEvidence evidence){
        return observed&&evidence.target.equals(identity.get("target"))&&evidence.processInstanceId.equals(ScriptValues.text(identity.get("pid"))+":"+identity.get("processGeneration"))
            &&ScriptOwnedFixture.class.getClassLoader()==loader&&ScriptOwnedCaller.class.getClassLoader()==loader;
    }
    @Override public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){
        ProbeFiles.require(evidenceLive(plan.evidence()),"OWNED_SCRIPT_EVIDENCE_STALE");List<Target> selected=new ArrayList<>();
        for(Map<String,Object> binding:plan.bindings()){
            Target target=target(String.valueOf(binding.get("id")));
            ProbeFiles.require("java".equals(binding.get("kind"))&&loaderId.equals(binding.get("classLoaderId"))&&ScriptOwnedFixture.class.getName().equals(binding.get("className"))&&target.name().equals(binding.get("methodName"))&&List.of(target.type()).equals(binding.get("argumentTypes"))&&target.type().equals(binding.get("returnType")),"OWNED_SCRIPT_BINDING_CHANGED");selected.add(target);
        }
        prepares++;
        return new HookInvocationSession.PreparedBackend(){
            private boolean published,retired;
            public void publish(HookInvocationSession.Dispatch dispatch)throws Exception{
                ProbeFiles.require(!published&&!retired&&evidenceLive(plan.evidence()),"OWNED_SCRIPT_PUBLISH_STATE");
                // Prepare all roots/helpers before enabling any entry. This method only runs during activate.
                for(Target target:selected)if(!handles.containsKey(target.id())){
                    ManagedHook.Handle handle=ManagedHook.prepare(target.method(),new Method[]{target.caller()});handles.put(target.id(),handle);
                    writeBytes(new File(directory,"script-helper-"+target.id()+".dex"),handle.helperDex());writeBytes(new File(directory,"script-relay-"+target.id()+".bin"),handle.relayBytes());
                }
                for(Target target:selected)handles.get(target.id()).enable(call->{
                    ProbeFiles.require(call.receiver()==null,"OWNED_SCRIPT_STATIC_RECEIVER");
                    Object result=dispatch.call(target.id(),ScriptFixtureCodec.decodeArguments(target.type(),call.arguments()),args->
                        ScriptFixtureCodec.decode(target.type(),call.original(ScriptFixtureCodec.encodeArguments(target.type(),args))));
                    return ScriptFixtureCodec.encode(target.type(),result);
                });
                published=true;publishes++;publicationEvents.put(new JSONObject().put("operation","publish").put("sourceSha256",plan.sourceSha256()).put("rules",selected.size()));
            }
            public boolean retire()throws Exception{
                ProbeFiles.require(published&&!retired,"OWNED_SCRIPT_RETIRE_STATE");for(Target target:selected)handles.get(target.id()).disable();retired=true;retires++;
                publicationEvents.put(new JSONObject().put("operation","retire").put("rules",selected.size()));
                return true;
            }
            public void failStop(Throwable error){android.util.Log.e("TripoderScriptProbe","OWNED_SCRIPT_BACKEND_UNCERTAIN",error);Process.killProcess(Process.myPid());System.exit(71);}
            public boolean nativeSymbol(String symbol){target(symbol);return false;}
        };
    }
    @Override public void discardObservation(){observed=false;}
    JSONArray nativeReports()throws Exception{
        JSONArray result=new JSONArray();for(var entry:handles.entrySet()){
            File dex=new File(directory,"script-helper-"+entry.getKey()+".dex"),relay=new File(directory,"script-relay-"+entry.getKey()+".bin");
            result.put(new JSONObject().put("symbol",entry.getKey()).put("native",new JSONObject(entry.getValue().report())).put("helperSha256",ProbeFiles.sha(dex)).put("relaySha256",ProbeFiles.sha(relay)));
        }return result;
    }
    private static void writeBytes(File file,byte[] value)throws IOException{ProbeFiles.require(file.createNewFile(),"OWNED_SCRIPT_ARTIFACT_EXISTS");try(FileOutputStream out=new FileOutputStream(file)){out.write(value);out.getFD().sync();}}
    static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
}
