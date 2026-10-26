package dev.tripoder.hooklab;

import android.os.*;
import android.util.*;
import dev.tripoder.control.*;
import dev.tripoder.runtime.ArtifactSet;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Resumable, bounded original-APK staging. Uploads never execute guest code. Only commit admits an install operation. */
final class WorkbenchAgentImports {
    private final WorkbenchApplication app;private final File root;
    WorkbenchAgentImports(WorkbenchApplication app){this.app=app;root=new File(app.getFilesDir(),"mcp-imports");}
    private File directory(String id)throws Exception{AgentCrypto.id(id);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("UPLOAD_DIRECTORY");File dir=new File(root,id);if(Files.isSymbolicLink(dir.toPath()))throw new IOException("UPLOAD_DIRECTORY_TYPE");return dir;}
    private JSONObject metadata(String id)throws Exception{return new JSONObject(Files.readString(new File(directory(id),"upload.json").toPath()));}
    private JSONObject operation(String id)throws Exception{
        try{return new JSONObject(app.engine.executeBlocking(ControlWire.request("agentOperation","operation",id)).getString("result"));}
        catch(Exception failure){if(String.valueOf(failure.getMessage()).contains("OPERATION_NOT_FOUND"))return null;throw failure;}
    }
    private static void save(File file,JSONObject value)throws Exception{AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=atomic.startWrite();try{stream.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));atomic.finishWrite(stream);}catch(Exception failure){atomic.failWrite(stream);throw failure;}}
    synchronized JSONObject call(String method,JSONObject params,String id)throws Exception{
        if(method.equals("import.cleanup")){
            AgentCrypto.fields(params,"uploadId");String upload=AgentCrypto.id(params.getString("uploadId"));JSONObject meta=metadata(upload),prior=operation(upload);
            if(prior!=null&&!java.util.Set.of("SUCCEEDED","FAILED").contains(prior.optString("state")))throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+upload);
            if(prior==null){meta.put("cancelled",true);save(new File(directory(upload),"upload.json"),meta);}long removed=0;
            for(int i=0;i<meta.getJSONArray("files").length();i++){File file=new File(directory(upload),i+".apk");removed+=file.length();Files.deleteIfExists(file.toPath());}return new JSONObject().put("uploadId",upload).put("releasedBytes",removed).put("state",prior==null?"CANCELLED":"RELEASED");
        }
        if(method.equals("application.importStatus")){AgentCrypto.fields(params,"operationId");String upload=AgentCrypto.id(params.getString("operationId"));JSONObject operation=operation(upload);File file=new File(directory(upload),"upload.json");JSONObject out=new JSONObject().put("operation",operation==null?JSONObject.NULL:operation).put("staged",file.exists());if(file.exists())out.put("upload",status(upload));return out;}
        if(method.equals("application.importDevice")){
            AgentCrypto.fields(params,"space","package","expectedGeneration");var info=app.getPackageManager().getApplicationInfo(params.getString("package"),0);List<File> files=new ArrayList<>();files.add(new File(info.sourceDir));if(info.splitSourceDirs!=null)for(String split:info.splitSourceDirs)files.add(new File(split));
            JSONArray entries=new JSONArray();for(int i=0;i<files.size();i++){File file=files.get(i);entries.put(new JSONObject().put("name",i==0?"base.apk":"split-"+i+".apk").put("size",file.length()).put("sha256",digest(file)));}
            JSONObject begin=new JSONObject().put("space",params.getString("space")).put("expectedGeneration",params.getString("expectedGeneration")).put("files",entries);JSONObject result=begin(begin,id);if("SUCCEEDED".equals(result.optString("state")))return result;
            for(int i=0;i<files.size();i++){File target=new File(directory(id),i+".apk");if(target.length()!=files.get(i).length()||!digest(target).equals(entries.getJSONObject(i).getString("sha256")))try(InputStream input=new FileInputStream(files.get(i));FileOutputStream output=new FileOutputStream(target)){input.transferTo(output);output.getFD().sync();}}
            return commit(id);
        }
        if(method.equals("import.begin")){AgentCrypto.fields(params,"space","expectedGeneration","files");return begin(params,id);}
        if(method.equals("import.write")){
            AgentCrypto.fields(params,"uploadId","index","offset","data");String upload=AgentCrypto.id(params.getString("uploadId"));JSONObject meta=metadata(upload);if(meta.optBoolean("cancelled"))throw new IllegalStateException("UPLOAD_CANCELLED");int index=params.getInt("index");long offset=params.getLong("offset");JSONArray files=meta.getJSONArray("files");if(index<0||index>=files.length()||offset<0)throw new IllegalArgumentException("UPLOAD_RANGE");
            if(operation(upload)!=null)throw new IllegalStateException("UPLOAD_COMMITTED_QUERY_OPERATION");String encoded=params.getString("data");if(encoded.length()>16384)throw new IllegalArgumentException("UPLOAD_CHUNK_LIMIT");byte[] data=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);if(data.length<1||data.length>12288||offset+data.length>files.getJSONObject(index).getLong("size"))throw new IllegalArgumentException("UPLOAD_RANGE");File file=new File(directory(upload),index+".apk");
            try(RandomAccessFile output=new RandomAccessFile(file,"rw")){if(offset>output.length())throw new IllegalStateException("UPLOAD_OFFSET_CONFLICT");if(offset<output.length()){if(offset+data.length>output.length())throw new IllegalStateException("UPLOAD_OFFSET_CONFLICT");byte[] prior=new byte[data.length];output.seek(offset);output.readFully(prior);if(!Arrays.equals(prior,data))throw new IllegalStateException("UPLOAD_CONTENT_CONFLICT");}else{if(root.getUsableSpace()<data.length+64L*1024*1024)throw new IOException("UPLOAD_STORAGE_FULL");output.seek(offset);output.write(data);output.getFD().sync();}return new JSONObject().put("offset",output.length());}
        }
        if(method.equals("import.commit")){AgentCrypto.fields(params);return commit(id);}
        throw new UnsupportedOperationException("IMPORT_METHOD_UNSUPPORTED");
    }
    private JSONObject begin(JSONObject params,String id)throws Exception{
        AgentCrypto.id(params.getString("space"));JSONArray files=params.getJSONArray("files");if(files.length()<1||files.length()>64)throw new IllegalArgumentException("UPLOAD_FILE_COUNT");long total=0;
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);AgentCrypto.fields(file,"name","size","sha256");String expected=i==0?"base.apk":"split-"+i+".apk";if(!expected.equals(file.getString("name"))||!file.getString("sha256").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("UPLOAD_FILE_METADATA");long size=file.getLong("size");if(size<1||size>ArtifactSet.MAX_APK_BYTES)throw new IllegalArgumentException("UPLOAD_FILE_SIZE");total+=size;}
        if(total>ArtifactSet.MAX_SET_BYTES)throw new IllegalArgumentException("UPLOAD_TOTAL_SIZE");File dir=directory(id),meta=new File(dir,"upload.json");String digest=WorkbenchAgentLifecycle.sha(params.toString());
        if(meta.exists()){if(metadata(id).optBoolean("cancelled"))throw new IllegalStateException("UPLOAD_CANCELLED");if(!metadata(id).getString("digest").equals(digest))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");}
        else{if(operation(id)!=null)throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");if(root.getUsableSpace()<total+64L*1024*1024)throw new IOException("UPLOAD_STORAGE_FULL");if(!dir.isDirectory()&&!dir.mkdir())throw new IOException("UPLOAD_DIRECTORY");save(meta,new JSONObject(params.toString()).put("digest",digest));}
        JSONObject prior=operation(id);if(prior!=null){if(!"SUCCEEDED".equals(prior.getString("state")))throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+id);return completed(id,meta.exists()?metadata(id):params,prior);}
        return status(id);
    }
    private JSONObject status(String id)throws Exception{JSONObject meta=metadata(id);JSONArray offsets=new JSONArray();for(int i=0;i<meta.getJSONArray("files").length();i++)offsets.put(new File(directory(id),i+".apk").length());return new JSONObject().put("uploadId",id).put("state",meta.optBoolean("cancelled")?"CANCELLED":"STAGED").put("offsets",offsets);}
    private JSONObject commit(String id)throws Exception{
        JSONObject meta=metadata(id),prior=operation(id);if(meta.optBoolean("cancelled"))throw new IllegalStateException("UPLOAD_CANCELLED");if(prior!=null){if(!"SUCCEEDED".equals(prior.getString("state")))throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+id);return completed(id,meta,prior);}
        Bundle input=ControlWire.request("install","operation",id,"space",meta.getString("space"));String expected=meta.getString("expectedGeneration");if(!expected.isEmpty())input.putString("expectedGeneration",AgentCrypto.id(expected));JSONArray files=meta.getJSONArray("files");ArrayList<String> names=new ArrayList<>(),hashes=new ArrayList<>();List<ParcelFileDescriptor> descriptors=new ArrayList<>();
        try{
            for(int i=0;i<files.length();i++){JSONObject entry=files.getJSONObject(i);File file=new File(directory(id),i+".apk");if(file.length()!=entry.getLong("size")||!digest(file).equals(entry.getString("sha256")))throw new IllegalStateException("UPLOAD_INCOMPLETE_OR_CHANGED");ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);descriptors.add(descriptor);input.putParcelable("fd"+i,descriptor);names.add(entry.getString("name"));hashes.add(entry.getString("sha256"));}
            var archiveInfo=app.getPackageManager().getPackageArchiveInfo(new File(directory(id),"0.apk").toString(),0);if(archiveInfo==null)throw new IllegalArgumentException("UPLOAD_APK_METADATA_INVALID");meta.put("package",archiveInfo.packageName);save(new File(directory(id),"upload.json"),meta);
            input.putStringArrayList("names",names);input.putStringArrayList("sha256",hashes);JSONObject result=WorkbenchAgentLifecycle.json(app.engine.executeBlocking(input));WorkbenchCatalog catalog=new WorkbenchCatalog(app.engine.executeBlocking(ControlWire.request("catalog")));catalog.sync(app);return new JSONObject().put("operationId",id).put("state","SUCCEEDED").put("space",meta.getString("space")).put("package",meta.getString("package")).put("generation",result.getString("generation"));
        }finally{for(ParcelFileDescriptor descriptor:descriptors)descriptor.close();}
    }
    private JSONObject completed(String id,JSONObject meta,JSONObject operation)throws Exception{return new JSONObject().put("operationId",id).put("state","SUCCEEDED").put("space",meta.getString("space")).put("package",meta.getString("package")).put("generation",operation.getString("result"));}
    private static String digest(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] data=new byte[65536];int count;while((count=input.read(data))!=-1)digest.update(data,0,count);}return HexFormat.of().formatHex(digest.digest());}
}
