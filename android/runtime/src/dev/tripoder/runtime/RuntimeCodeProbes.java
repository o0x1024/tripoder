package dev.tripoder.runtime;

import android.os.SystemClock;
import dev.tripoder.selfhook.CodeSnapshots;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.runtime.script.HookWire;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Optional process-local observation. No timer, loader hook, ART read or disk
 * write exists in an ordinary thin process until explicitly requested. */
final class RuntimeCodeProbes {
    private record Loader(String id,ClassLoader value,String origin){}
    private record Dex(String id,String loader,Object owner,long pointer,long size,int version){}
    private final RuntimeEngine engine;private final JSONObject config;private final String revision,project;
    private final IdentityHashMap<ClassLoader,Loader> loaders=new IdentityHashMap<>();
    private final LinkedHashMap<String,Dex> dexes=new LinkedHashMap<>();private final Map<String,String> dexKeys=new HashMap<>();
    private final LinkedHashMap<String,JSONObject> captures=new LinkedHashMap<>();
    private final ArrayDeque<JSONObject> events=new ArrayDeque<>();private long sequence,dropped,reservedBytes,scans;
    private final ThreadLocal<Boolean> observing=ThreadLocal.withInitial(()->false);
    private ThreadPoolExecutor writer;private int installedHooks;private volatile boolean active;private volatile String stage="BEFORE_APPLICATION";
    private List<ManagedHook.Handle> loaderHooks=List.of();
    private android.os.ParcelFileDescriptor artifactDirectory;
    private long loadedBytes;
    private final List<java.nio.ByteBuffer> loadedBuffers=new ArrayList<>();
    RuntimeCodeProbes(RuntimeEngine engine,String plan)throws Exception{
        this.engine=engine;stage=engine.ready?"APPLICATION_CREATED":"BEFORE_APPLICATION";JSONObject spec=plan==null?new JSONObject():new JSONObject(plan);revision=spec.optString("revision","");project=spec.optString("project","");config=RuntimeProbePlans.validate(spec.optJSONObject("configuration")==null?new JSONObject():spec.getJSONObject("configuration"));
    }
    void start()throws Exception{
        if(!config.getBoolean("enabled"))return;
        if(config.getBoolean("discoverLoaders")){
            Map<Executable,Executable[]> methods=new LinkedHashMap<>();Class<?> base=Class.forName("dalvik.system.BaseDexClassLoader");
            for(Constructor<?> constructor:base.getDeclaredConstructors())methods.put(constructor,new Executable[0]);
            // Compiled platform subclasses can inline/delegate past a base
            // constructor entry. Observe their public construction boundaries
            // as well, without disabling compilation for the whole process.
            for(String type:List.of("dalvik.system.PathClassLoader","dalvik.system.DexClassLoader","dalvik.system.InMemoryDexClassLoader"))for(Constructor<?> constructor:Class.forName(type).getDeclaredConstructors())methods.put(constructor,new Executable[0]);
            for(Method method:base.getDeclaredMethods())if(method.getName().equals("addDexPath"))methods.put(method,new Executable[0]);
            for(Executable member:methods.keySet())if(ManagedHook.ownsTarget(member))throw new IllegalStateException("PROBE_LOADER_TARGET_RESERVED");
            Map<Executable,ManagedHook.Handle> handles=ManagedHook.prepareBatch(methods);Map<ManagedHook.Handle,ManagedHook.Handler> callbacks=new LinkedHashMap<>();
            for(var item:handles.entrySet())callbacks.put(item.getValue(),call->{
                Object value=call.original(call.arguments());Object receiver=call.receiver();
                if(active&&receiver instanceof ClassLoader loader&&engine.ownsLoader(loader)&&!observing.get()){
                    observing.set(true);try{register(loader,"constructor");}catch(Throwable failure){event("discovery.error",new JSONObject().put("error",failure.getClass().getSimpleName()));}finally{observing.remove();}
                }
                return value;
            });
            ManagedHook.enableBatch(callbacks);loaderHooks=List.copyOf(handles.values());installedHooks=handles.size();
        }
        active=true;register(engine.artifactLoader(),"startup.root");event("probe.ready",new JSONObject().put("revision",revision).put("installedHooks",installedHooks));
    }
    void stage(String value){stage=value;event("process.stage",json("stage",value));}
    String currentStage(){return stage;}
    JSONObject stop()throws Exception{
        active=false;List<ManagedHook.Handle> retained=loaderHooks;if(!retained.isEmpty()){ManagedHook.disableBatch(retained);loaderHooks=List.of();}installedHooks=0;event("probe.stopped",new JSONObject());return status();
    }
    private static JSONObject json(String key,Object value){try{return new JSONObject().put(key,value);}catch(JSONException error){throw new IllegalStateException(error);}}
    private synchronized void event(String kind,JSONObject detail){
        try{JSONObject value=new JSONObject().put("seq",++sequence).put("kind",kind).put("timeNanos",SystemClock.elapsedRealtimeNanos()).put("stage",stage).put("processGeneration",engine.generation).put("detail",detail);
            if(events.size()==256){events.removeFirst();dropped++;}events.addLast(value);
        }catch(JSONException error){throw new IllegalStateException(error);}
    }
    synchronized ClassLoader loader(String id){for(Loader value:loaders.values())if(value.id.equals(id))return value.value;throw new IllegalArgumentException("CLASS_LOADER_REFERENCE_STALE");}
    private Loader register(ClassLoader value)throws Exception{return register(value,"query");}
    private synchronized Loader register(ClassLoader value,String origin)throws Exception{
        if(!engine.ownsLoader(value))throw new SecurityException("CLASS_LOADER_OUTSIDE_GUEST");
        Loader known=loaders.get(value);if(known==null){if(loaders.size()>=128)throw new IllegalStateException("CLASS_LOADER_CAPACITY");known=new Loader(engine.loaderReference(value),value,origin);loaders.put(value,known);event("classloader.created",new JSONObject().put("classLoaderId",known.id).put("type",value.getClass().getName()).put("origin",origin));}
        scan(known);return known;
    }
    private void scan(Loader loader)throws Exception{
        scans++;Object[] files=CodeSnapshots.dexFiles(loader.value);if(files==null)return;
        for(Object file:files){if(file==null)continue;long[] pointers=CodeSnapshots.cookies(file);if(pointers==null||pointers.length>257)continue;
            // Cookie[0] belongs to the OatFile; only the remaining pointers name DEX objects.
            for(int i=1;i<pointers.length;i++){
                String key=loader.id+":"+Long.toHexString(pointers[i]);if(dexKeys.containsKey(key))continue;
                if(dexes.size()>=512)throw new IllegalStateException("DEX_DISCOVERY_CAPACITY");long[] identity=CodeSnapshots.inspect(pointers[i]);
                if(identity==null){event("dex.unsupported",new JSONObject().put("classLoaderId",loader.id).put("cookieIndex",i));continue;}
                String id=UUID.randomUUID().toString();Dex dex=new Dex(id,loader.id,file,pointers[i],identity[1],(int)identity[2]);dexes.put(id,dex);dexKeys.put(key,id);
                event("dex.loaded",dexInfo(dex));
                if(active&&config.getBoolean("captureDexOnLoad"))try{capture(id);}catch(Exception failure){event("snapshot.rejected",new JSONObject().put("dexId",id).put("error",String.valueOf(failure.getMessage())));}
            }
        }
    }
    private JSONObject dexInfo(Dex dex)throws Exception{return new JSONObject().put("dexId",dex.id).put("classLoaderId",dex.loader).put("size",dex.size).put("version",dex.version).put("source","ART_DEX_MEMORY").put("processGeneration",engine.generation);}
    synchronized JSONObject status()throws Exception{return new JSONObject().put("enabled",active).put("revision",revision).put("stage",stage).put("installedHooks",installedHooks).put("loaderCount",loaders.size()).put("dexCount",dexes.size()).put("scans",scans).put("reservedBytes",reservedBytes).put("loadedBytes",loadedBytes).put("artifactCount",captures.size()).put("pendingCaptures",writer==null?0:writer.getQueue().size()+writer.getActiveCount()).put("droppedEvents",dropped).put("fullCallerCoverage",false).put("atomicMemorySnapshot",false).put("discovery","BASE_DEX_CLASS_LOADER_CONSTRUCTION_AND_ADD_DEX_PATH");}
    synchronized JSONObject loaders()throws Exception{
        register(engine.guestLoader());for(Loader value:new ArrayList<>(loaders.values()))scan(value);
        JSONArray rows=new JSONArray();for(Loader value:loaders.values()){
            Loader parent=loaders.get(value.value.getParent());rows.put(new JSONObject().put("classLoaderId",value.id).put("type",value.value.getClass().getName()).put("parentId",parent==null?JSONObject.NULL:parent.id).put("origin",value.origin).put("processGeneration",engine.generation));
        }return new JSONObject().put("classLoaders",rows);
    }
    synchronized JSONObject dexes(String loaderId,String cursor,int limit)throws Exception{
        if(loaderId.isEmpty())loaders();else scan(loaders.get(loader(loaderId)));
        JSONArray rows=new JSONArray();boolean found=cursor.isEmpty(),more=false;String last="";
        for(Dex dex:dexes.values())if(loaderId.isEmpty()||dex.loader.equals(loaderId)){
            if(!found){if(dex.id.equals(cursor))found=true;continue;}if(rows.length()>=limit){more=true;break;}rows.put(dexInfo(dex));last=dex.id;
        }
        if(!found)throw new IllegalArgumentException("DEX_CURSOR_STALE");return new JSONObject().put("dexFiles",rows).put("nextCursor",more?last:"").put("totalObserved",dexes.size());
    }
    synchronized List<String> classes(String prefix,int limit)throws Exception{
        loaders();TreeSet<String> result=new TreeSet<>();
        for(Dex dex:dexes.values()){
            @SuppressWarnings("unchecked") Enumeration<String> names=(Enumeration<String>)dex.owner.getClass().getMethod("entries").invoke(dex.owner);
            int examined=0;while(names.hasMoreElements()){if(++examined>500000)throw new IllegalStateException("DEX_CLASS_SCAN_BUDGET");String name=names.nextElement();if(name.startsWith(prefix))result.add(name);if(result.size()>=limit)break;}
            if(result.size()>=limit)break;
        }return List.copyOf(result);
    }
    synchronized JSONObject capture(String dexId)throws Exception{
        Dex dex=dexes.get(dexId);if(dex==null)throw new IllegalArgumentException("DEX_REFERENCE_STALE");
        if(dex.size>config.getInt("maxDexBytes")||reservedBytes+loadedBytes+dex.size>config.getInt("maxTotalBytes")||captures.size()>=config.getInt("maxArtifacts"))throw new IllegalStateException("CODE_SNAPSHOT_QUOTA");
        if(writer==null)writer=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread thread=new Thread(r,"TripoderCodeCapture");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        Map<String,Object> identity=engine.hooks.identitySnapshot();String id=UUID.randomUUID().toString();JSONObject metadata=new JSONObject().put("artifactId",id).put("kind","dex").put("project",project).put("space",identity.get("space")).put("package",engine.packageName).put("installGeneration",identity.get("installGeneration")).put("processGeneration",engine.generation).put("dexId",dexId).put("classLoaderId",dex.loader).put("size",dex.size).put("state","QUEUED").put("requestedAtNanos",SystemClock.elapsedRealtimeNanos()).put("triggerStage",stage).put("source","ART_DEX_MEMORY").put("consistency","BEST_EFFORT_HEADER_STABLE");
        reservedBytes+=dex.size;captures.put(id,metadata);
        try{writer.execute(()->write(dex,metadata));}catch(RejectedExecutionException full){reservedBytes-=dex.size;captures.remove(id);throw new IllegalStateException("CODE_CAPTURE_QUEUE_FULL");}
        return new JSONObject(metadata.toString());
    }
    private void write(Dex dex,JSONObject metadata){
        try{
            synchronized(this){metadata.put("state","CAPTURING");}
            int directory=directory();save(directory,metadata);
            if(!retains(dex))throw new IOException("DEX_OBJECT_CLOSED");
            byte[] bytes=CodeSnapshots.copy(dex.pointer,config.getInt("maxDexBytes"));if(bytes==null)throw new IOException("DEX_MEMORY_UNAVAILABLE_OR_CHANGED");
            if(!retains(dex))throw new IOException("DEX_OBJECT_CLOSED_DURING_CAPTURE");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            CodeSnapshots.writeArtifact(directory,metadata.getString("artifactId")+".dex",bytes);
            JSONObject complete;long captured=SystemClock.elapsedRealtimeNanos();
            synchronized(this){complete=new JSONObject(metadata.toString()).put("state","COMPLETE").put("sha256",digest).put("capturedAtNanos",captured);}
            // Disk flushes never hold the registry monitor used by callbacks.
            save(directory,complete);
            synchronized(this){metadata.put("state","COMPLETE").put("sha256",digest).put("capturedAtNanos",captured);event("artifact.complete",complete);}
        }catch(Exception failure){try{
            JSONObject receipt;synchronized(this){metadata.put("state","FAILED").put("error",failure.getClass().getSimpleName()+":"+failure.getMessage());receipt=new JSONObject(metadata.toString());event("artifact.failed",receipt);}
            save(directory(),receipt);
        }catch(Exception recording){android.util.Log.w("TripoderCode","Capture failure receipt unavailable",recording);}}
    }
    private static boolean retains(Dex dex){long[] cookies=CodeSnapshots.cookies(dex.owner);if(cookies!=null)for(int i=1;i<cookies.length;i++)if(cookies[i]==dex.pointer)return true;return false;}
    JSONObject createLoader(String dexId,String parentId)throws Exception{
        Dex source;ClassLoader parent;
        synchronized(this){source=dexes.get(dexId);if(source==null)throw new IllegalArgumentException("DEX_REFERENCE_STALE");parent=loader(parentId);
            if(loaders.size()>=128||source.size>config.getInt("maxDexBytes")||reservedBytes+loadedBytes+source.size>config.getInt("maxTotalBytes"))throw new IllegalStateException("CODE_LOAD_QUOTA");loadedBytes+=source.size;}
        if(!retains(source))throw new IllegalStateException("DEX_OBJECT_CLOSED");byte[] bytes=CodeSnapshots.copy(source.pointer,config.getInt("maxDexBytes"));if(bytes==null||!retains(source))throw new IllegalStateException("DEX_MEMORY_UNAVAILABLE_OR_CHANGED");
        // A read-only heap buffer does not expose array(), which ART's heap
        // input path may require. Direct read-only buffers have a stable native
        // address without exposing mutable bytes to the caller.
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocateDirect(bytes.length);buffer.put(bytes).flip();buffer=buffer.asReadOnlyBuffer();ClassLoader child=new dalvik.system.InMemoryDexClassLoader(buffer,parent);
        synchronized(this){loadedBuffers.add(buffer);Loader value=register(child,"command");return new JSONObject().put("classLoaderId",value.id).put("parentId",parentId).put("sourceDexId",dexId).put("origin",value.origin).put("processGeneration",engine.generation).put("classesInitialized",false).put("loadedBytes",loadedBytes);}
    }
    private int directory()throws Exception{if(artifactDirectory==null)artifactDirectory=engine.artifactDirectory();return artifactDirectory.getFd();}
    private static void save(int directory,JSONObject metadata)throws Exception{CodeSnapshots.writeArtifact(directory,metadata.getString("artifactId")+".json",metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    synchronized JSONObject artifacts()throws Exception{JSONArray rows=new JSONArray();for(JSONObject value:captures.values())rows.put(new JSONObject(value.toString()));return new JSONObject().put("artifacts",rows);}
    synchronized JSONObject events(long since,int limit)throws Exception{
        JSONArray rows=new JSONArray();long cursor=since;for(JSONObject event:events)if(event.getLong("seq")>since){if(rows.length()==limit)break;rows.put(new JSONObject(event.toString()));cursor=event.getLong("seq");}
        return new JSONObject().put("events",rows).put("nextCursor",cursor).put("latestSeq",sequence).put("droppedEvents",dropped).put("gap",!events.isEmpty()&&since<events.getFirst().getLong("seq")-1);
    }
    Map<String,Object> command(String name,Map<String,Object> params)throws Exception{
        JSONObject out=switch(name){case "probeStatus"->status();case "probeStop"->stop();case "classLoaders"->loaders();case "classLoaderCreate"->createLoader((String)params.get("dexId"),(String)params.get("parentClassLoaderId"));case "dexList"->dexes((String)params.get("classLoaderId"),(String)params.get("cursor"),((Number)params.get("limit")).intValue());case "dexSnapshot"->capture((String)params.get("dexId"));case "codeArtifacts"->artifacts();case "runtimeEvents"->events(((Number)params.get("sinceSeq")).longValue(),((Number)params.get("limit")).intValue());case "classInspect"->new JSONObject(engine.hooks.javaObservation().inspectClass((String)params.get("className"),(String)params.get("classLoaderId")));default->throw new IllegalArgumentException("CODE_COMMAND_UNSUPPORTED");};return HookWire.request(out.toString());
    }
}
