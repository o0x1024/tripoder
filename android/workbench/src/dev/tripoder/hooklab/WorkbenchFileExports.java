package dev.tripoder.hooklab;

import dev.tripoder.control.ControlWire;
import dev.tripoder.control.AgentCrypto;
import java.io.*;
import java.nio.file.*;
import org.json.*;

/** Resolve global paths, exact installations and persisted artifacts in the host UID. */
final class WorkbenchFileExports {
    private final WorkbenchApplication app;
    final FileExports store;
    WorkbenchFileExports(WorkbenchApplication app)throws Exception{
        this.app=app;store=new FileExports(new File(app.getFilesDir(),"mcp-exports").toPath(),this::resolve);
    }
    private Path resolve(JSONObject source)throws Exception{
        switch(source.getString("kind")){
            case "path":{
                AgentCrypto.fields(source,"kind","path");String raw=source.getString("path");
                Path path=Path.of(raw);if(!path.isAbsolute()||raw.indexOf('\0')>=0)throw new IllegalArgumentException("FILE_ABSOLUTE_PATH_REQUIRED");return path;
            }
            case "guest":{
                AgentCrypto.fields(source,"kind","target","root","path");JSONObject target=source.getJSONObject("target");AgentCrypto.fields(target,"space","package","installGeneration");
                var request=ControlWire.request("fileSource","space",AgentCrypto.text(target,"space"),"package",AgentCrypto.text(target,"package"),"generation",AgentCrypto.text(target,"installGeneration"),"root",source.getString("root"));
                Path root=Path.of(app.engine.executeBlocking(request).getString("path")).toRealPath();String relative=source.getString("path");Path suffix=Path.of(relative);
                if(suffix.isAbsolute()||relative.indexOf('\0')>=0)throw new IllegalArgumentException("FILE_RELATIVE_PATH_REQUIRED");Path path=root.resolve(suffix).normalize();
                if(!path.startsWith(root)||!path.toRealPath().startsWith(root))throw new IOException("FILE_GUEST_PATH_ESCAPE");return path;
            }
            case "artifact":{
                AgentCrypto.fields(source,"kind","processGeneration","artifactId");return dev.tripoder.runtime.RuntimeCodeArtifacts.file(app,source.getString("processGeneration"),source.getString("artifactId")).toPath();
            }
            default:throw new IllegalArgumentException("FILE_SOURCE_KIND_INVALID");
        }
    }
    JSONObject call(String method,JSONObject params,String operation)throws Exception{
        switch(method){
            case "file.stat":AgentCrypto.fields(params,"source");return store.stat(params.getJSONObject("source"));
            case "file.list":AgentCrypto.fields(params,"source","cursor","limit");return store.list(params.getJSONObject("source"),params.getInt("cursor"),params.getInt("limit"));
            case "file.exportBegin":AgentCrypto.fields(params,"source","followSymlinks");return store.begin(operation,params.getJSONObject("source"),params.getBoolean("followSymlinks"));
            case "file.exportStatus":AgentCrypto.fields(params,"exportId");return store.status(params.getString("exportId"));
            case "file.exportManifest":AgentCrypto.fields(params,"exportId","cursor","limit");return store.manifest(params.getString("exportId"),params.getInt("cursor"),params.getInt("limit"));
            case "file.exportRead":AgentCrypto.fields(params,"exportId","entryId","offset","length");return store.read(params.getString("exportId"),params.getString("entryId"),params.getLong("offset"),params.getInt("length"));
            case "file.exportCancel":AgentCrypto.fields(params,"exportId");return store.action(operation,params.getString("exportId"),false);
            case "file.exportRelease":AgentCrypto.fields(params,"exportId");return store.action(operation,params.getString("exportId"),true);
            default:throw new IllegalArgumentException("FILE_METHOD_INVALID");
        }
    }
    static JSONObject capabilities()throws Exception{return new JSONObject().put("version",1).put("maxChunkBytes",FileExports.CHUNK).put("maxFileBytes",FileExports.FILE_LIMIT).put("maxTotalBytes",FileExports.TOTAL_LIMIT).put("maxEntries",FileExports.ENTRIES).put("maxDepth",FileExports.DEPTH).put("sources",new JSONArray().put("path").put("guest").put("artifact")).put("atomicDirectorySnapshot",false);}
}
