package dev.tripoder.runtimeprobe;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** UI receipts are distinct from authority execution records; absent ACK is never success. */
final class PreviewRecords {
    private final File root;
    PreviewRecords(Context c)throws IOException {root=new File(c.getFilesDir(),"preview-records");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("PREVIEW_RECORD_DIRECTORY_FAILED");}
    synchronized void save(JSONObject record)throws Exception {
        String id=record.getString("id");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("PREVIEW_RECORD_ID_INVALID");
        byte[] data=record.toString(2).getBytes(StandardCharsets.UTF_8);if(data.length>32768)throw new IOException("PREVIEW_RECORD_TOO_LARGE");
        AtomicFile file=new AtomicFile(new File(root,id+".json"));FileOutputStream out=file.startWrite();try{out.write(data);file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
    }
    synchronized List<JSONObject> all()throws Exception {
        List<JSONObject> out=new ArrayList<>();File[] files=root.listFiles((dir,name)->name.endsWith(".json"));if(files==null)throw new IOException("PREVIEW_RECORD_READ_FAILED");
        for(File file:files){if(file.length()>32768)throw new IOException("PREVIEW_RECORD_TOO_LARGE");out.add(new JSONObject(Files.readString(file.toPath())));}
        out.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));return out;
    }
    synchronized boolean unresolved(String space)throws Exception {for(JSONObject r:all())if(space.equals(r.optString("space"))&&Set.of("PREPARING","RUNNING","UNKNOWN","RECEIVED").contains(r.optString("state")))return true;return false;}
    static void export(Context context,JSONObject record)throws Exception {
        File directory=context.getExternalFilesDir(null);if(directory==null)throw new IOException("PREVIEW_EXPORT_DIRECTORY_UNAVAILABLE");
        String content=record.toString(2);
        ProbeReports.publish(new File(directory,"preview-result-"+record.getString("id")+".json"),content);
        AtomicFile latest=new AtomicFile(new File(directory,"preview-latest.json"));FileOutputStream out=latest.startWrite();
        try{out.write(content.getBytes(StandardCharsets.UTF_8));latest.finishWrite(out);}catch(Exception e){latest.failWrite(out);throw e;}
    }
}
