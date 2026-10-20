package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.*;
import org.json.*;

/** Explicit per-install next-process configuration. No plan exists by default;
 * authority restart clears plans instead of silently replaying startup Hooks. */
final class RuntimeProbePlans {
    private record Plan(String project,String binding,String revision,String configuration){}
    private final Map<String,Plan> plans=new HashMap<>();private final Set<String> launching=new HashSet<>();
    private static String key(Bundle target){return target.getString("space")+"\n"+target.getString("package")+"\n"+target.getString("generation");}
    static JSONObject validate(JSONObject raw)throws Exception{
        Set<String> allowed=Set.of("enabled","discoverLoaders","captureDexOnLoad","maxDexBytes","maxTotalBytes","maxArtifacts","processName","startupHook");
        for(var keys=raw.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("PROBE_CONFIGURATION_FIELD");
        JSONObject out=new JSONObject().put("enabled",bool(raw,"enabled",false)).put("discoverLoaders",bool(raw,"discoverLoaders",true)).put("captureDexOnLoad",bool(raw,"captureDexOnLoad",false))
            .put("maxDexBytes",number(raw,"maxDexBytes",16*1024*1024,112,64*1024*1024)).put("maxTotalBytes",number(raw,"maxTotalBytes",64*1024*1024,112,256*1024*1024)).put("maxArtifacts",number(raw,"maxArtifacts",32,1,128));
        String process=raw.optString("processName","");if(process.length()>256||process.contains("\n"))throw new IllegalArgumentException("PROBE_PROCESS_NAME");out.put("processName",process);
        if(raw.has("startupHook")){
            JSONObject hook=raw.getJSONObject("startupHook");if(hook.length()!=2||!hook.has("selectors")||!hook.has("source"))throw new IllegalArgumentException("PROBE_STARTUP_HOOK_FIELDS");
            JSONArray selectors=hook.getJSONArray("selectors");String source=hook.getString("source");if(selectors.length()<1||selectors.length()>32||source.isEmpty()||source.length()>24000)throw new IllegalArgumentException("PROBE_STARTUP_HOOK_SIZE");
            out.put("startupHook",new JSONObject(hook.toString()));
        }
        if(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>26000)throw new IllegalArgumentException("PROBE_CONFIGURATION_SIZE");return out;
    }
    private static boolean bool(JSONObject raw,String key,boolean fallback)throws Exception{if(!raw.has(key))return fallback;Object value=raw.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("PROBE_BOOLEAN:"+key);return (Boolean)value;}
    private static int number(JSONObject raw,String key,int fallback,int min,int max)throws Exception{if(!raw.has(key))return fallback;Object value=raw.get(key);if(!(value instanceof Number)||((Number)value).doubleValue()!=Math.rint(((Number)value).doubleValue())||((Number)value).doubleValue()<min||((Number)value).doubleValue()>max)throw new IllegalArgumentException("PROBE_LIMIT:"+key);return ((Number)value).intValue();}
    synchronized JSONObject configure(Bundle target,String expected,JSONObject raw)throws Exception{
        String key=key(target);if(launching.contains(key))throw new IllegalStateException("PROBE_LAUNCH_IN_FLIGHT");Plan prior=current(target);String revision=prior==null?"":prior.revision;
        if(!revision.equals(expected))throw new IllegalStateException("PROBE_REVISION_STALE");JSONObject value=validate(raw);
        if(prior==null&&plans.size()>=64)throw new IllegalStateException("PROBE_PLAN_CAPACITY");
        Plan next=new Plan(target.getString("project"),target.getString("bindingOperation"),UUID.randomUUID().toString(),value.toString());plans.put(key,next);return describe(target);
    }
    private Plan current(Bundle target){Plan value=plans.get(key(target));return value!=null&&value.project.equals(target.getString("project"))&&value.binding.equals(target.getString("bindingOperation"))?value:null;}
    synchronized JSONObject describe(Bundle target)throws Exception{Plan value=current(target);return new JSONObject().put("project",target.getString("project")).put("bindingOperation",target.getString("bindingOperation")).put("installGeneration",target.getString("generation")).put("revision",value==null?"":value.revision).put("configuration",value==null?new JSONObject().put("enabled",false):new JSONObject(value.configuration)).put("appliesTo","NEW_PROCESSES").put("survivesAuthorityRestart",false);}
    synchronized void beginLaunch(Bundle target,String expected)throws Exception{if(!describe(target).getString("revision").equals(expected))throw new IllegalStateException("PROBE_REVISION_STALE");if(!launching.add(key(target)))throw new IllegalStateException("PROBE_LAUNCH_IN_FLIGHT");}
    synchronized void endLaunch(Bundle target){launching.remove(key(target));}
    synchronized String forProcess(RuntimeBroker broker,String space,String pkg,String install,String process)throws Exception{
        Plan value=plans.get(space+"\n"+pkg+"\n"+install);if(value==null)return null;JSONObject config=new JSONObject(value.configuration);
        try(var cursor=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs WHERE id=? AND operation=? AND space=? AND package=? AND generation=? AND kind='DIRECT' AND state='COMMITTED'",new String[]{value.project,value.binding,space,pkg,install})){if(!cursor.moveToFirst())return null;}
        if(!config.getBoolean("enabled")||!config.getString("processName").isEmpty()&&!config.getString("processName").equals(process))return null;
        return new JSONObject().put("revision",value.revision).put("project",value.project).put("bindingOperation",value.binding).put("configuration",config).toString();
    }
}
