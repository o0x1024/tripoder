package dev.tripoder.runtime.script;

import java.util.*;
import java.util.function.*;

/** Managed source uses the same observe/prepare/activate/disable path as user Hooks. */
final class NetworkCaptureControl {
    private final SelfHookControl.Platform platform;
    private final Function<Map<String,Object>,Map<String,Object>> command;
    private final String generation;
    private final Supplier<String> token;
    private volatile NetworkCaptureSession session;
    private volatile int configuredConnections=NetworkCaptureSession.DEFAULT_CONNECTIONS,configuredMessages=NetworkCaptureSession.DEFAULT_MESSAGES,configuredTotalBytes=NetworkCaptureSession.DEFAULT_TOTAL_BYTES;
    private volatile String sourceSha="",revision="";private boolean stopping;private String moduleName="",moduleBuild="";
    NetworkCaptureControl(SelfHookControl.Platform platform,Function<Map<String,Object>,Map<String,Object>> command,String generation,Supplier<String> token){this.platform=platform;this.command=command;this.generation=generation;this.token=token;}
    boolean ownsHook(){return session!=null&&!session.status().get("captureState").equals("STOPPED");}
    void event(Map<String,Object> event){NetworkCaptureSession current=session;if(current!=null){if(revision.isEmpty())revision=(String)event.get("revision");current.event(event);}}
    private Map<String,Object> invoke(String name,Map<String,Object> args){Map<String,Object> input=new LinkedHashMap<>(args);input.put("command",name);input.put("processGeneration",generation);return command.apply(input);}
    Map<String,Object> inspect(String module){
        if("auto".equals(module))return inspectAuto();
        return inspectSingle(module);
    }
    private Map<String,Object> inspectSingle(String module){
        List<String> missing=new ArrayList<>();String build="";
        boolean liveLoaded=false;
        for(Map<String,Object> mod:platform.modules()){
            Object rawName=mod.get("moduleName");if(!(rawName instanceof String))rawName=mod.get("name");
            if(module.equals(rawName)){liveLoaded=true;break;}
        }
        for(String name:List.of("SSL_new","SSL_free","SSL_read","SSL_write")){
            List<Map<String,Object>> matches=platform.searchNativeExports(module,name,16).stream().filter(row->name.equals(row.get("name"))||name.equals(row.get("symbol"))).toList();
            if(matches.size()!=1){missing.add(name);continue;}
            String id=String.valueOf(matches.get(0).get("buildId"));if(build.isEmpty())build=id;else if(!build.equals(id))throw new HookPreflightRejected("NETWORK_MODULE_BUILD_ID_MISMATCH");
        }
        boolean candidate=missing.isEmpty()&&liveLoaded;
        return Map.of("moduleName",module,"buildId",build,"profile","openssl-ssl-read-write-v1","candidate",candidate,"loaded",liveLoaded,"missingExports",missing,"signatureConfirmationRequired",true,"automaticAbiInference",false);
    }
    private Map<String,Object> inspectAuto(){
        List<String> candidates=new ArrayList<>();
        for(Map<String,Object> mod:platform.modules()){
            Object rawName=mod.get("moduleName");if(!(rawName instanceof String))rawName=mod.get("name");
            if(rawName instanceof String mName&&mName.startsWith("lib")&&mName.endsWith(".so")&&!mName.startsWith("libtripoder")&&!mName.equals("libc.so")&&!mName.equals("libm.so")&&!mName.equals("libdl.so")){
                if(!candidates.contains(mName))candidates.add(mName);
            }
        }
        for(String preferred:List.of("libssl.so","libconscrypt_jni.so","libjavacrypto.so","libcronet.so")){
            if(!candidates.contains(preferred))candidates.add(preferred);
        }
        List<String> scanned=new ArrayList<>();
        for(String mName:candidates){
            scanned.add(mName);
            try{
                Map<String,Object> res=inspectSingle(mName);
                if(Boolean.TRUE.equals(res.get("candidate"))){
                    Map<String,Object> out=new LinkedHashMap<>(res);
                    out.put("resolvedFrom","auto");
                    out.put("scannedModules",List.copyOf(scanned));
                    return Collections.unmodifiableMap(out);
                }
            }catch(Throwable ignored){}
        }
        return Map.of("moduleName","auto","buildId","","profile","openssl-ssl-read-write-v1","candidate",false,"missingExports",List.of("SSL_new","SSL_free","SSL_read","SSL_write"),"scannedModules",List.copyOf(scanned),"signatureConfirmationRequired",true,"automaticAbiInference",false);
    }
    Map<String,Object> start(String module,String buildId,String profile,String confirmation){
        if(ownsHook())throw new HookPreflightRejected("NETWORK_CAPTURE_ALREADY_ACTIVE");
        if(!profile.equals("openssl-ssl-read-write-v1")||!confirmation.equals("confirmed"))throw new HookPreflightRejected("NETWORK_PROFILE_NOT_CONFIRMED");
        Map<String,Object> candidate=inspect(module);
        String resolvedModule=String.valueOf(candidate.get("moduleName"));
        String resolvedBuild=String.valueOf(candidate.get("buildId"));
        String expectedBuild="auto".equals(buildId)?resolvedBuild:buildId;
        if(!Boolean.TRUE.equals(candidate.get("candidate"))||resolvedBuild.isEmpty()||!expectedBuild.equals(resolvedBuild))throw new HookPreflightRejected("NETWORK_PROFILE_UNAVAILABLE_OR_STALE");
        List<Map<String,Object>> selectors=List.of(selector("open",resolvedModule,"SSL_new",List.of("uint64"),"uint64",resolvedBuild),selector("close",resolvedModule,"SSL_free",List.of("uint64"),"void",resolvedBuild),selector("read",resolvedModule,"SSL_read",List.of("uint64","uint64","int32"),"int32",resolvedBuild),selector("write",resolvedModule,"SSL_write",List.of("uint64","uint64","int32"),"int32",resolvedBuild));
        Map<String,Object> observed;
        try{
            observed=invoke("_networkObserve",Map.of("selectors",selectors));
        }catch(Exception failure){
            throw new HookPreflightRejected("NETWORK_MODULE_NOT_OBSERVABLE: "+failure.getMessage());
        }
        @SuppressWarnings("unchecked") String evidence=(String)((Map<String,Object>)observed.get("evidence")).get("id");
        Map<String,Object> prepared=invoke("_networkPrepare",Map.of("evidenceId",evidence,"source",source(resolvedModule,resolvedBuild)));
        moduleName=resolvedModule;moduleBuild=resolvedBuild;sourceSha=(String)prepared.get("sourceSha256");revision="";stopping=false;
        session=new NetworkCaptureSession(token.get(),(tag,payload)->platform.persistNetworkCapture(tag,payload,sourceSha,revision),configuredConnections,configuredMessages,configuredTotalBytes);
        Map<String,Object> activated=invoke("_networkActivate",Map.of("token",prepared.get("token"),"sourceSha256",sourceSha));
        revision=(String)activated.get("revision");return status();
    }
    /** Applies to the next capture only. Values above the defaults need explicit confirmation; per-direction bytes stay fixed by the 32 KiB evidence artifact and 128 KiB response ceilings. */
    Map<String,Object> configure(int connections,int messages,int totalBytes,boolean confirmHigherLimits){
        if(ownsHook())throw new HookPreflightRejected("NETWORK_CAPTURE_ALREADY_ACTIVE");
        if(connections<1||connections>64||messages<1||messages>256||totalBytes<4096||totalBytes>262144)throw new HookPreflightRejected("NETWORK_LIMITS_RANGE");
        boolean raised=connections>NetworkCaptureSession.DEFAULT_CONNECTIONS||messages>NetworkCaptureSession.DEFAULT_MESSAGES||totalBytes>NetworkCaptureSession.DEFAULT_TOTAL_BYTES;
        if(raised&&!confirmHigherLimits)throw new HookPreflightRejected("NETWORK_LIMITS_CONFIRMATION_REQUIRED");
        configuredConnections=connections;configuredMessages=messages;configuredTotalBytes=totalBytes;
        return Map.of("configured",true,"appliesTo","NEXT_CAPTURE","limits",Map.of("connections",(double)connections,"messagesPerCapture",(double)messages,"totalBytes",(double)totalBytes,"bytesPerDirection",(double)NetworkCaptureSession.STREAM_BYTES),"higherThanDefault",raised);
    }
    Map<String,Object> har(String id,long cursor,int limit){requireSession(id);return session.har(cursor,limit);}
    Map<String,Object> stop(String id){
        requireSession(id);if(!ownsHook())return status();
        if(!stopping){stopping=true;invoke("_networkDisable",Map.of("revision",revision));}
        return status();
    }
    Map<String,Object> status(){
        Map<String,Object> hook=invoke("_networkStatus",Map.of());String state=(String)hook.get("state");
        if(state.equals("ACTIVE")||state.equals("DRAINING"))revision=(String)hook.get("revision");
        if(session==null)return Map.of("state",state,"captureState","IDLE");
        if(stopping&&Set.of("IDLE","DISABLED").contains(state))session.stop();
        if(!stopping&&!Set.of("ACTIVE","DRAINING").contains(state)&&ownsHook())session.fail("CAPTURE_HOOK_NOT_ACTIVE");
        Map<String,Object> result=new LinkedHashMap<>(session.status());result.put("state",state);result.put("sourceSha256",sourceSha);result.put("revision",revision);result.put("stopping",stopping);result.put("moduleName",moduleName);result.put("buildId",moduleBuild);result.put("profile","openssl-ssl-read-write-v1");result.put("otherTlsImplementations","NOT_OBSERVED");return result;
    }
    Map<String,Object> query(String id,long cursor,int limit){requireSession(id);return session.query(cursor,limit);}
    Map<String,Object> messages(String id,long cursor,int limit){requireSession(id);return session.messages(cursor,limit);}
    private void requireSession(String id){if(session==null||!session.id.equals(id))throw new HookPreflightRejected("NETWORK_CAPTURE_ID_MISMATCH");}
    private static Map<String,Object> selector(String id,String module,String symbol,List<String> arguments,String result,String build){return Map.of("id",id,"kind","native","moduleName",module,"symbol",symbol,"argumentTypes",arguments,"returnType",result,"buildId",build,"signatureSource","confirmed");}
    private static String source(String module,String build){
        // Both values have already passed exact native selector schema checks; quote through HookWire.
        String m=HookWire.encode(module),b=HookWire.encode(build),prefix="ctx.native.function("+m+",";
        StringBuilder source=new StringBuilder("import {definePlan} from '@abox/hook';export default definePlan({id:'network-capture',target,params:{},setup(ctx){");
        source.append(prefix).append("'SSL_new',{argumentTypes:['uint64'],returnType:'uint64',buildId:").append(b).append("}).after(({result})=>{ctx.debug.emitEvent('network.open',String(result));return result;});");
        source.append(prefix).append("'SSL_free',{argumentTypes:['uint64'],returnType:'void',buildId:").append(b).append("}).before(({args})=>{ctx.debug.emitEvent('network.close',String(args[0]));return args;});");
        for(String direction:List.of("read","write"))source.append(prefix).append("'SSL_").append(direction).append("',{argumentTypes:['uint64','uint64','int32'],returnType:'int32',buildId:").append(b)
            .append("}).before(({args})=>{ctx.debug.emitEvent('network.begin',{connection:String(args[0]),direction:'").append(direction).append("'});return args;}).after(({args,result})=>{ctx.debug.emitEvent('network.bytes',{connection:String(args[0]),direction:'").append(direction).append("',buffer:ctx.native.captureBuffer(1,2,1024)});return result;});");
        return source.append("}});").toString();
    }
}
