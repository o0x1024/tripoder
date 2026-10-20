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
    public static JSONObject list(Context host,String generation)throws Exception{
        File directory=new File(new File(host.getFilesDir(),"code-artifacts"),id(generation));JSONArray rows=new JSONArray();
        if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(directory.getParentFile().toPath()))throw new IOException("ARTIFACT_PATH_INVALID");
        File[] files=directory.listFiles();if(files!=null)for(File file:files){if(!file.getName().matches("[A-Za-z0-9_-]{1,80}\\.json"))continue;
            if(Files.isSymbolicLink(file.toPath())||file.length()>8192)throw new IOException("ARTIFACT_METADATA_INVALID");JSONObject value=new JSONObject(Files.readString(file.toPath()));
            if(!generation.equals(value.getString("processGeneration")))throw new IOException("ARTIFACT_GENERATION_MISMATCH");rows.put(value);if(rows.length()>128)throw new IOException("ARTIFACT_PAGE_REQUIRED");
        }return new JSONObject().put("processGeneration",generation).put("artifacts",rows).put("source","PERSISTED_CAPTURE_RECEIPTS");
    }
    public static JSONObject read(Context host,String generation,String artifactId,int offset,int length)throws Exception{
        if(offset<0||length<1||length>16384)throw new IllegalArgumentException("ARTIFACT_CHUNK_RANGE");File directory=new File(new File(host.getFilesDir(),"code-artifacts"),id(generation));
        Path meta=new File(directory,id(artifactId)+".json").toPath(),data=new File(directory,id(artifactId)+".dex").toPath();
        if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(meta)||Files.isSymbolicLink(data)||Files.size(meta)>8192)throw new IOException("ARTIFACT_PATH_INVALID");
        JSONObject value=new JSONObject(Files.readString(meta));if(!generation.equals(value.getString("processGeneration"))||!artifactId.equals(value.getString("artifactId"))||!value.getString("state").equals("COMPLETE"))throw new IOException("ARTIFACT_NOT_COMPLETE");
        long size=value.getLong("size");if(offset>size||Files.size(data)!=size)throw new IOException("ARTIFACT_LENGTH_CHANGED");byte[] bytes=new byte[(int)Math.min(length,size-offset)];
        try(RandomAccessFile input=new RandomAccessFile(data.toFile(),"r")){input.seek(offset);input.readFully(bytes);}
        return new JSONObject().put("artifactId",artifactId).put("processGeneration",generation).put("offset",offset).put("size",size).put("sha256",value.getString("sha256")).put("encoding","base64").put("data",java.util.Base64.getEncoder().encodeToString(bytes)).put("nextOffset",offset+bytes.length).put("eof",offset+bytes.length==size);
    }
}
