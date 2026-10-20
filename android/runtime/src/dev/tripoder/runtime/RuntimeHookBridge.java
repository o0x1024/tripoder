package dev.tripoder.runtime;

import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import dev.tripoder.runtime.script.HookCommandDispatcher;
import dev.tripoder.runtime.script.HookWire;
import dev.tripoder.runtime.script.SelfHookControl;

/** Guest-loader evidence without class initialization. No host class search or executable source evaluation. */
public final class RuntimeHookBridge {
    private static final ConcurrentHashMap<String,CompletableFuture<String>> requests=new ConcurrentHashMap<>();
    private final RuntimeEngine engine;
    private final JSONObject identity;
    private final ArtifactSet artifacts;
    private final RuntimeNativeMappingEvidence runtimeMappings;
    private final String artifactCanonicalRoot;
    private final RuntimeJavaObservation javaObservation;
    private RuntimeNativeObservation nativeObservation;
    private volatile HookCommandDispatcher selfHookControl;
    RuntimeHookBridge(RuntimeEngine engine,Bundle input,ArtifactSet artifacts)throws Exception{
        this.engine=engine;this.artifacts=artifacts;artifactCanonicalRoot=artifacts.directory.getCanonicalPath();
        // Capture the physical native directory before the logical guest view is installed.
        runtimeMappings=new RuntimeNativeMappingEvidence(new java.io.File(engine.host.getApplicationInfo().nativeLibraryDir,"libtripoderselfhook.so"),engine.generation);
        identity=new JSONObject().put("space",RuntimeWire.required(input,"space"))
            .put("installGeneration",RuntimeWire.required(input,"installGeneration"))
            .put("descriptorSha256",RuntimeWire.required(input,"descriptorSha"))
            .put("processGeneration",engine.generation).put("pid",android.os.Process.myPid())
            .put("mode",engine.mode.wire)
            .put("target",new JSONObject().put("packageName",engine.packageName).put("versionCode",Long.toString(artifacts.versionCode)).put("apkSha256",RuntimeWire.required(input,"sha256")));
        javaObservation=new RuntimeJavaObservation(engine::guestLoader,engine::ownsLoader,()->RuntimeEngine.current==engine,identitySnapshot());
    }
    private static RuntimeHookBridge current(){RuntimeEngine e=RuntimeEngine.current;if(e==null||e.application==null)throw new IllegalStateException("HOOK_APPLICATION_NOT_READY");return e.hooks;}
    public static String identity(){return current().identity.toString();}
    public static String token(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    public static String digest(String source)throws Exception{return ApkStore.textDigest(source);}
    public static String execute(String request)throws Exception{
        current();String id=token();CompletableFuture<String> result=new CompletableFuture<>();
        synchronized(requests){if(requests.size()>=8)throw new IllegalStateException("HOOK_REQUEST_CAPACITY");requests.put(id,result);}
        try{submit(id,request);return result.get(12,TimeUnit.SECONDS);}
        catch(TimeoutException error){throw new IllegalStateException("HOOK_AGENT_TIMEOUT_UNKNOWN",error);}
        finally{requests.remove(id,result);}
    }
    /** Installed once by the self-hook bootstrap only after the compiler/backend contract is ready. */
    public synchronized void installSelfHookControl(HookCommandDispatcher.Handler handler){
        if(selfHookControl!=null)throw new IllegalStateException("HOOK_CONTROL_ALREADY_INSTALLED");
        selfHookControl=new HookCommandDispatcher(handler);
    }
    /** Existing prepare(source,evidenceId) is compiled inside this process; callers never supply ASTs. */
    public void installSelfHookControl(SelfHookControl.Platform platform){
        SelfHookControl control=new SelfHookControl(HookWire.request(identity.toString()),platform,android.os.SystemClock::elapsedRealtime);
        installSelfHookControl(raw->{
            String response=control.handle(raw);
            if(!"status".equals(HookWire.request(raw).get("command")))return response;
            Map<String,Object> wire=HookWire.request(response);
            if(!Boolean.TRUE.equals(wire.get("ok")))return response;
            @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)wire.get("result");
            result.put("runtimeNativeMappings",runtimeMappings.snapshot());
            result.put("nativeCoreResources",dev.tripoder.selfhook.NativeHook.resources());
            return HookWire.encode(wire);
        });
    }
    public static void submit(String id,String request){
        HookCommandDispatcher dispatcher=current().selfHookControl;
        if(dispatcher==null)throw new IllegalStateException("REQUIRED_HOOK_ADAPTER_MISSING");
        dispatcher.submit(id,request,new HookCommandDispatcher.Sink(){
            public void response(String requestId,String response){
                if(!complete(requestId,response))android.util.Log.e("TripoderHook","HOOK_ACK_NOT_DELIVERED:"+requestId);
            }
            public void rejected(String requestId,Exception error){
                try{response(requestId,new JSONObject().put("ok",false).put("error",String.valueOf(error).substring(0,Math.min(1000,String.valueOf(error).length()))).toString());}
                catch(JSONException impossible){throw new IllegalStateException("HOOK_RESPONSE_ENCODING",impossible);}
            }
            public void unknown(String requestId,Error error){
                android.util.Log.e("TripoderHook","HOOK_CONTROL_UNKNOWN:"+requestId,error);
            }
        });
    }
    public static boolean complete(String id,String response){CompletableFuture<String> pending=requests.get(id);return pending!=null&&pending.complete(response);}
    public static String observe(String selectors)throws Exception{return current().javaObservation.inspect(new JSONArray(selectors)).toString();}
    public static ClassLoader loader(String symbol){return current().type(symbol).getClassLoader();}
    public static boolean sameClass(String symbol,Class<?> value){return current().type(symbol)==value;}
    public static boolean nativeMethod(String symbol){current().type(symbol);return current().javaObservation.nativeMethod(symbol);}
    public static String libraryNames(){JSONArray names=new JSONArray();for(var file:current().artifacts.nativeFiles)names.put(new java.io.File(file.path).getName());return names.toString();}
    public static String library(String name)throws Exception{
        return current().verifiedLibrary(name).toString();
    }
    private String canonicalLibraryPath(String name)throws Exception{
        for(var item:artifacts.nativeFiles)if(item.path.equals("lib/"+name)){
            java.io.File file=new java.io.File(artifacts.directory,item.path);ArtifactSet.regular(file.toPath());
            String canonical=RuntimeSelfHookStorage.physicalArtifactPath(file.toString());
            if(!canonical.equals(artifactCanonicalRoot+"/"+item.path))throw new SecurityException("HOOK_MODULE_CANONICAL_ROOT_CHANGED");
            if(file.length()!=item.size)throw new SecurityException("HOOK_MODULE_ARTIFACT_CHANGED");
            return canonical;
        }
        throw new SecurityException("HOOK_MODULE_OUTSIDE_GUEST");
    }
    private JSONObject verifiedLibraryArtifact(String name)throws Exception{
        for(var item:artifacts.nativeFiles)if(item.path.equals("lib/"+name)){
            java.io.File file=new java.io.File(artifacts.directory,item.path);ArtifactSet.regular(file.toPath());
            String canonical=RuntimeSelfHookStorage.physicalArtifactPath(file.toString());
            if(!canonical.equals(artifactCanonicalRoot+"/"+item.path))throw new SecurityException("HOOK_MODULE_CANONICAL_ROOT_CHANGED");
            if(file.length()!=item.size||!ArtifactSet.digest(file).equals(item.sha256))throw new SecurityException("HOOK_MODULE_ARTIFACT_CHANGED");
            return new JSONObject().put("moduleName",name).put("path",canonical).put("sourcePath",file.toString()).put("sha256",item.sha256);
        }
        throw new SecurityException("HOOK_MODULE_OUTSIDE_GUEST");
    }
    private JSONObject verifiedLibrary(String name)throws Exception{
        JSONObject artifact=verifiedLibraryArtifact(name);JSONObject elf=RuntimeElfIdentity.inspect(new java.io.File(artifact.getString("path")));
        for(var keys=elf.keys();keys.hasNext();){String key=keys.next();artifact.put(key,elf.get(key));}return artifact;
    }
    public static long exportAddress(String name,String symbol)throws Exception{JSONObject verified=new JSONObject(library(name));return RuntimeElfExports.address(new java.io.File(verified.getString("path")),symbol);}
    private Class<?> type(String symbol){return javaObservation.type(symbol);}
    Map<String,Object> identitySnapshot(){return HookWire.request(identity.toString());}
    RuntimeJavaObservation javaObservation(){return javaObservation;}
    synchronized RuntimeNativeObservation nativeObservation()throws Exception {
        if(nativeObservation==null)nativeObservation=new RuntimeNativeObservation(new RuntimeNativeObservation.Inventory(){
            public List<String> names(){List<String> names=new ArrayList<>();for(var file:artifacts.nativeFiles)names.add(new java.io.File(file.path).getName());return List.copyOf(names);}
            public String path(String name)throws Exception{return canonicalLibraryPath(name);}
            public Map<String,Object> inspect(String name)throws Exception{return HookWire.request(verifiedLibrary(name).toString());}
        },()->RuntimeEngine.current==engine,identitySnapshot());
        return nativeObservation;
    }
    SelfHookControl.Platform selfHookPlatform()throws Exception{return new RuntimeSelfHookPlatform(identitySnapshot(),javaObservation,nativeObservation());}
}
