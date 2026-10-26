package dev.tripoder.runtime;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

/** Immutable completed artifacts remain readable after their worker exits.
 * Client strings are IDs, never paths. Binary bytes are transferred in chunks. */
public final class RuntimeCodeArtifacts {
    private static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("ARTIFACT_REFERENCE_INVALID");return value;}
    public static File file(Context host,String generation,String artifactId)throws Exception{
        Path directory=host.getFilesDir().toPath().resolve("code-artifacts").resolve(id(generation));
        Path meta=directory.resolve(id(artifactId)+".json");
        JSONObject value=metadata(meta);Path data=data(directory,value,artifactId);
        if(Files.isSymbolicLink(directory.getParent())||Files.isSymbolicLink(directory)||Files.isSymbolicLink(meta)||Files.isSymbolicLink(data)||Files.size(meta)>8192)throw new IOException("ARTIFACT_PATH_INVALID");
        if(!generation.equals(value.getString("processGeneration"))||!artifactId.equals(value.getString("artifactId"))||!"COMPLETE".equals(value.getString("state"))||Files.size(data)!=value.getLong("size"))throw new IOException("ARTIFACT_NOT_COMPLETE");
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(data)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)sha.update(bytes,0,count);}
        if(!java.util.HexFormat.of().formatHex(sha.digest()).equals(value.getString("sha256")))throw new IOException("ARTIFACT_HASH_MISMATCH");
        return data.toFile();
    }
    public static JSONObject list(Context host,String generation,int cursor,int limit)throws Exception{
        File directory=new File(new File(host.getFilesDir(),"code-artifacts"),id(generation));JSONArray rows=new JSONArray();
        if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(directory.getParentFile().toPath()))throw new IOException("ARTIFACT_PATH_INVALID");
        if(cursor<0||limit<1||limit>64)throw new IllegalArgumentException("ARTIFACT_PAGE_RANGE");
        File[] files=directory.listFiles((dir,name)->name.matches("[A-Za-z0-9_-]{1,80}\\.json"));
        if(files==null){if(directory.exists())throw new IOException("ARTIFACT_DIRECTORY_UNREADABLE");files=new File[0];}
        java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName));if(cursor>files.length)throw new IllegalArgumentException("ARTIFACT_CURSOR_INVALID");
        int next=cursor,bytes=0;while(next<files.length&&rows.length()<limit){File file=files[next];JSONObject value=metadata(file.toPath());
            if(!generation.equals(value.getString("processGeneration")))throw new IOException("ARTIFACT_GENERATION_MISMATCH");int size=value.toString().getBytes(StandardCharsets.UTF_8).length;
            if(bytes+size>48000)break;rows.put(value);bytes+=size;next++;
        }
        return new JSONObject().put("processGeneration",generation).put("artifacts",rows).put("source","PERSISTED_CAPTURE_RECEIPTS").put("total",files.length).put("nextCursor",next==files.length?-1:next);
    }
    public static JSONObject read(Context host,String generation,String artifactId,int offset,int length)throws Exception{
        if(offset<0||length<1||length>16384)throw new IllegalArgumentException("ARTIFACT_CHUNK_RANGE");File directory=new File(new File(host.getFilesDir(),"code-artifacts"),id(generation));
        Path meta=new File(directory,id(artifactId)+".json").toPath();JSONObject value=metadata(meta);Path data=data(directory.toPath(),value,artifactId);
        if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(meta)||Files.isSymbolicLink(data)||Files.size(meta)>8192)throw new IOException("ARTIFACT_PATH_INVALID");
        if(!generation.equals(value.getString("processGeneration"))||!artifactId.equals(value.getString("artifactId"))||!value.getString("state").equals("COMPLETE"))throw new IOException("ARTIFACT_NOT_COMPLETE");
        long size=value.getLong("size");if(offset>size||Files.size(data)!=size)throw new IOException("ARTIFACT_LENGTH_CHANGED");byte[] bytes=new byte[(int)Math.min(length,size-offset)];
        try(RandomAccessFile input=new RandomAccessFile(data.toFile(),"r")){input.seek(offset);input.readFully(bytes);}
        return new JSONObject().put("artifactId",artifactId).put("processGeneration",generation).put("offset",offset).put("size",size).put("sha256",value.getString("sha256")).put("encoding","base64").put("data",java.util.Base64.getEncoder().encodeToString(bytes)).put("nextOffset",offset+bytes.length).put("eof",offset+bytes.length==size);
    }
    private static JSONObject metadata(Path path)throws Exception{if(Files.isSymbolicLink(path)||Files.size(path)>8192)throw new IOException("ARTIFACT_METADATA_INVALID");return new JSONObject(Files.readString(path));}
    private static Path data(Path directory,JSONObject metadata,String artifactId)throws Exception{
        String extension=switch(metadata.getString("kind")){case "dex"->".dex";case "hook-evidence"->".snapshot";default->throw new IOException("ARTIFACT_KIND_UNSUPPORTED");};
        return directory.resolve(id(artifactId)+extension);
    }
    /** Explicit synchronous JSON capture: COMPLETE is returned only after bytes and metadata are synced. */
    private static int evidenceCaptures;
    public static synchronized JSONObject capture(int directory,JSONObject identity,String tag,Object payload,java.util.Map<String,Object> invocation)throws Exception{
        String generation=id(identity.getString("processGeneration"));if(!generation.equals(invocation.get("processGeneration")))throw new SecurityException("EVIDENCE_PROCESS_MISMATCH");
        if(!tag.matches("[a-zA-Z0-9_.:-]{1,64}"))throw new IllegalArgumentException("HOOK_EVENT_TAG");
        if(evidenceCaptures>=64)throw new IOException("EVIDENCE_CAPTURE_CAPACITY");
        evidenceCaptures++;
        String artifact=java.util.UUID.randomUUID().toString();
        JSONObject snapshot=new JSONObject().put("tag",tag).put("identity",identity).put("invocation",new JSONObject(invocation)).put("payload",new JSONTokener(dev.tripoder.runtime.script.HookWire.encode(payload)).nextValue());
        byte[] bytes=snapshot.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>32768)throw new IOException("EVIDENCE_CAPTURE_SIZE");
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        JSONObject meta=new JSONObject().put("artifactId",artifact).put("kind","hook-evidence").put("processGeneration",generation).put("space",identity.getString("space"))
            .put("package",identity.getJSONObject("target").getString("packageName")).put("installGeneration",identity.getString("installGeneration")).put("revision",invocation.get("revision")).put("sourceSha256",invocation.get("sourceSha256"))
            .put("symbol",invocation.get("symbol")).put("tag",tag).put("state","COMPLETE").put("size",bytes.length).put("sha256",digest).put("mediaType","application/json");
        dev.tripoder.selfhook.CodeSnapshots.writeArtifact(directory,artifact+".snapshot",bytes);
        dev.tripoder.selfhook.CodeSnapshots.writeArtifact(directory,artifact+".json",meta.toString().getBytes(StandardCharsets.UTF_8));
        return meta;
    }
}
