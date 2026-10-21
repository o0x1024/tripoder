package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import android.util.AtomicFile;
import dev.tripoder.control.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Global control surface. Source edits have their own durable receipts; runtime mutations use authority receipts. */
final class WorkbenchAgentLifecycle {
    private final WorkbenchApplication app;
    WorkbenchAgentLifecycle(WorkbenchApplication app){this.app=app;}
    static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private Bundle execute(String command,String...args)throws Exception{return app.engine.executeBlocking(ControlWire.request(command,args));}
    private JSONArray applications()throws Exception{
        Bundle catalog=execute("catalog");WorkbenchCatalog snapshot=new WorkbenchCatalog(catalog);snapshot.sync(app);return snapshot.applicationsMeta;
    }
    private String project(String pkg)throws Exception{
        JSONArray applications=applications();for(int i=0;i<applications.length();i++){JSONObject row=applications.getJSONObject(i);if(pkg.equals(row.getString("package")))return row.getString("project");}
        throw new IllegalStateException("APPLICATION_PROJECT_NOT_FOUND");
    }
    Bundle target(JSONObject identity,boolean process)throws Exception{
        if(process)AgentCrypto.fields(identity,"space","package","installGeneration","processGeneration");else AgentCrypto.fields(identity,"space","package","installGeneration");
        return execute("applicationTarget","space",AgentCrypto.text(identity,"space"),"package",AgentCrypto.text(identity,"package"),"generation",AgentCrypto.text(identity,"installGeneration"));
    }
    private Bundle installation(JSONObject identity)throws Exception{
        AgentCrypto.fields(identity,"space","package","installGeneration");JSONArray rows=new JSONArray(execute("catalog").getString("installs"));
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(identity.getString("space").equals(row.getString("space"))&&identity.getString("package").equals(row.getString("package"))&&identity.getString("installGeneration").equals(row.getString("generation"))){Bundle out=new Bundle();out.putString("space",row.getString("space"));out.putString("package",row.getString("package"));out.putString("generation",row.getString("generation"));return out;}}
        throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
    }
    static JSONObject json(Bundle value)throws Exception{
        JSONObject out=new JSONObject();for(String key:value.keySet()){Object item=value.get(key);if(item instanceof Bundle)item=json((Bundle)item);if(item instanceof String){String raw=(String)item;if(raw.startsWith("[")||raw.startsWith("{"))try{item=new JSONTokener(raw).nextValue();}catch(JSONException ignored){}}
            if(item==null||item instanceof String||item instanceof Number||item instanceof Boolean||item instanceof JSONObject||item instanceof JSONArray)out.put(key,item==null?JSONObject.NULL:item);
        }return out;
    }
    private File receipt(String id)throws Exception{AgentCrypto.id(id);File root=new File(app.getFilesDir(),"mcp-operations");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("MCP_JOURNAL_DIRECTORY");return new File(root,id+".json");}
    JSONObject receiptIfPresent(String id)throws Exception{File file=receipt(id);if(!file.exists())return null;return new JSONObject(Files.readString(file.toPath()));}
    private static void atomic(File file,JSONObject value)throws Exception{
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=atomic.startWrite();try{output.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output);}catch(Exception failure){atomic.failWrite(output);throw failure;}
    }
    void cleanupReceipt(JSONObject receipt)throws Exception{
        if(!"SUCCEEDED".equals(receipt.optString("state")))return;Object value=receipt.opt("result");
        if(value instanceof String&&((String)value).startsWith("{"))value=new JSONObject((String)value);
        if(value instanceof JSONObject){JSONArray deleted=((JSONObject)value).optJSONArray("deletedProjects");if(deleted!=null)for(int i=0;i<deleted.length();i++)app.projects.delete(deleted.getString(i));}
    }
    interface Mutation{JSONObject run()throws Exception;}
    synchronized JSONObject mutate(String id,String method,JSONObject params,Mutation action)throws Exception{
        String digest=sha(method+"\n"+params);JSONObject prior=receiptIfPresent(id);
        if(prior!=null){if(!digest.equals(prior.getString("digest")))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");if(!"SUCCEEDED".equals(prior.getString("state")))throw new IllegalStateException("LOCAL_OPERATION_QUERY_REQUIRED:"+id);return prior.getJSONObject("result");}
        try{execute("agentOperation","operation",id);throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");}catch(Exception failure){if(!String.valueOf(failure.getMessage()).contains("OPERATION_NOT_FOUND"))throw failure;}
        JSONObject record=new JSONObject().put("operationId",id).put("method",method).put("digest",digest).put("state","RUNNING");atomic(receipt(id),record);
        try{JSONObject result=action.run();record.put("state","SUCCEEDED").put("result",result);atomic(receipt(id),record);return result;}
        catch(Exception failure){record.put("state",java.util.Set.of("SOURCE_REVISION_CONFLICT","SELECTORS_REVISION_CONFLICT","PROJECT_MIGRATION_REVIEW_REQUIRED","SPACE_NOT_FOUND","SPACE_NAME_INVALID").contains(String.valueOf(failure.getMessage()))?"FAILED":"UNKNOWN").put("detail",String.valueOf(failure.getMessage()));atomic(receipt(id),record);throw failure;}
    }
    JSONObject call(String method,JSONObject params,String id)throws Exception{
        switch(method){
            case "runtime.status":AgentCrypto.fields(params);return json(execute("status"));
            case "runtime.resources":AgentCrypto.fields(params);return json(execute("resources"));
            case "spaces.list":{AgentCrypto.fields(params);Bundle catalog=execute("catalog");JSONArray spaces=new JSONArray(catalog.getString("spaces")),out=new JSONArray();for(int i=0;i<spaces.length();i++){String space=spaces.getString(i);out.put(new JSONObject().put("space",space).put("name",app.spaces.name(space)));}return new JSONObject().put("spaces",out);}
            case "space.create":{AgentCrypto.fields(params,"space","name");String space=params.getString("space");if(!space.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("SPACE_ID_INVALID");return mutate(id,method,params,()->{String name=params.getString("name");if(name.trim().isEmpty()||name.length()>24)throw new IllegalArgumentException("SPACE_NAME_INVALID");JSONObject result=json(execute("createSpace","operation",id,"space",space));app.spaces.rename(space,name);return result;});}
            case "space.rename":{AgentCrypto.fields(params,"space","name");return mutate(id,method,params,()->{JSONArray spaces=new JSONArray(execute("catalog").getString("spaces"));boolean found=false;for(int i=0;i<spaces.length();i++)found|=params.getString("space").equals(spaces.getString(i));if(!found)throw new IllegalStateException("SPACE_NOT_FOUND");String name=params.getString("name");if(name.trim().isEmpty()||name.length()>24)throw new IllegalArgumentException("SPACE_NAME_INVALID");app.spaces.rename(params.getString("space"),name);return new JSONObject().put("space",params.getString("space")).put("name",name);});}
            case "space.delete":{AgentCrypto.fields(params,"space");JSONObject result=json(execute("deleteSpace","operation",id,"space",params.getString("space")));WorkbenchSpaces.forget(app,params.getString("space"));return result;}
            case "applications.list":case "projects.list":{AgentCrypto.fields(params);Bundle catalog=execute("catalog");new WorkbenchCatalog(catalog).sync(app);JSONArray applications=new JSONArray(catalog.getString("applications"));for(int i=0;i<applications.length();i++){applications.getJSONObject(i).remove("selectedSpace");applications.getJSONObject(i).remove("legacyProjects");}JSONArray installs=new JSONArray(catalog.getString("installs"));for(int i=0;i<installs.length();i++)installs.getJSONObject(i).put("installGeneration",installs.getJSONObject(i).getString("generation"));return new JSONObject().put("applications",applications).put("projects",applications).put("installations",installs);}
            case "device.applications":{AgentCrypto.fields(params);JSONArray list=new JSONArray();var pm=app.getPackageManager();for(var info:pm.getInstalledApplications(0)){if(info.packageName.equals(app.getPackageName()))continue;list.put(new JSONObject().put("package",info.packageName).put("name",pm.getApplicationLabel(info).toString()));}return new JSONObject().put("applications",list);}
            case "application.remove":{AgentCrypto.fields(params,"target");JSONObject identity=params.getJSONObject("target");Bundle target=installation(identity);return json(execute("uninstallApplication","operation",id,"space",target.getString("space"),"package",target.getString("package"),"generation",target.getString("generation")));}
            case "application.delete":{AgentCrypto.fields(params,"package","installations");JSONObject result=json(execute("deleteApplication","operation",id,"package",params.getString("package"),"expectedInstalls",params.getJSONArray("installations").toString()));JSONArray deleted=result.optJSONArray("deletedProjects");if(deleted!=null)for(int i=0;i<deleted.length();i++)app.projects.delete(deleted.getString(i));return result;}
            case "application.mode":{AgentCrypto.fields(params,"target","mode");Bundle target=target(params.getJSONObject("target"),false);return json(execute("setApplicationMode","operation",id,"project",target.getString("project"),"space",target.getString("space"),"generation",target.getString("generation"),"mode",params.getString("mode")));}
            case "project.get":AgentCrypto.fields(params,"package");return source(project(params.getString("package")));
            case "project.scriptPut":{AgentCrypto.fields(params,"package","source","expectedSha256");String project=project(params.getString("package"));return mutate(id,method,params,()->{synchronized(app.projects){checkSource(project,params.getString("expectedSha256"));if(app.projects.get(project).optBoolean("migrationPending"))throw new IllegalStateException("PROJECT_MIGRATION_REVIEW_REQUIRED");app.projects.script(project,params.getString("source"));return source(project);}});}
            case "project.selectorsPut":{AgentCrypto.fields(params,"package","selectors","expectedSha256");String project=project(params.getString("package"));return mutate(id,method,params,()->{synchronized(app.projects){if(!sha(app.projects.selectors(project)).equals(params.getString("expectedSha256")))throw new IllegalStateException("SELECTORS_REVISION_CONFLICT");if(app.projects.get(project).optBoolean("migrationPending"))throw new IllegalStateException("PROJECT_MIGRATION_REVIEW_REQUIRED");app.projects.selectors(project,params.getJSONArray("selectors").toString());return source(project);}});}
            case "project.archives":{AgentCrypto.fields(params,"package");return new JSONObject().put("archives",new JSONArray(app.projects.archived(params.getString("package").isEmpty()?null:params.getString("package"))));}
            case "project.archiveRead":AgentCrypto.fields(params,"archiveId");return new JSONObject().put("archiveId",params.getString("archiveId")).put("source",app.projects.archivedScript(params.getString("archiveId")));
            case "project.archiveRestore":{AgentCrypto.fields(params,"package","archiveId","expectedSha256");String project=project(params.getString("package"));return mutate(id,method,params,()->{synchronized(app.projects){checkSource(project,params.getString("expectedSha256"));app.projects.restore(project,params.getString("archiveId"));app.projects.acceptMigration(project);return source(project);}});}
            case "project.migrationAccept":{AgentCrypto.fields(params,"package","expectedSha256");String project=project(params.getString("package"));return mutate(id,method,params,()->{synchronized(app.projects){checkSource(project,params.getString("expectedSha256"));app.projects.acceptMigration(project);return source(project);}});}
            case "launch.entries":{AgentCrypto.fields(params,"target");Bundle target=target(params.getJSONObject("target"),false);return json(execute("launchEntries","project",target.getString("project"),"bindingOperation",target.getString("bindingOperation")));}
            case "launch.recovery":{AgentCrypto.fields(params,"operationId","target");Bundle target=target(params.getJSONObject("target"),false);return json(execute("launchRecovery","operation",params.getString("operationId"),"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation")));}
            case "launch.close":{AgentCrypto.fields(params,"target","launchOperationId");Bundle target=target(params.getJSONObject("target"),false);return json(execute("closeLaunchEntry","operation",id,"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"launchOperation",params.getString("launchOperationId")));}
            case "application.launchTargets":{AgentCrypto.fields(params,"target");Bundle target=target(params.getJSONObject("target"),false);return json(execute("launchTargets","project",target.getString("project"),"bindingOperation",target.getString("bindingOperation")));}
            case "application.reverify":{AgentCrypto.fields(params,"target");Bundle target=installation(params.getJSONObject("target"));return mutate(id,method,params,()->json(execute("reverifyInstall","space",target.getString("space"),"package",target.getString("package"),"generation",target.getString("generation"))));}
            case "launch.abandon":{AgentCrypto.fields(params,"launchOperationId");return mutate(id,method,params,()->json(execute("abandonLaunch","operation",params.getString("launchOperationId"))));}
            default:throw new UnsupportedOperationException("GLOBAL_METHOD_UNSUPPORTED:"+method);
        }
    }
    private void checkSource(String project,String expected)throws Exception{if(!sha(app.projects.script(project)).equals(expected))throw new IllegalStateException("SOURCE_REVISION_CONFLICT");}
    private JSONObject source(String project)throws Exception{synchronized(app.projects){return new JSONObject().put("project",project).put("package",app.projects.get(project).getString("package")).put("source",app.projects.script(project)).put("sourceSha256",sha(app.projects.script(project))).put("selectors",new JSONArray(app.projects.selectors(project))).put("selectorsSha256",sha(app.projects.selectors(project))).put("migrationPending",app.projects.get(project).optBoolean("migrationPending"));}}
}
