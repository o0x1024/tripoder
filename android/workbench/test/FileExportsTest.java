package dev.tripoder.hooklab;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class FileExportsTest {
    static void check(boolean b,String detail){if(!b)throw new AssertionError(detail);}
    static JSONObject source(Path p)throws Exception{return new JSONObject().put("kind","path").put("path",p.toString());}
    static JSONObject ready(FileExports store,String id)throws Exception{for(int i=0;i<500;i++){JSONObject row=store.status(id);if(!row.getString("state").equals("PREPARING"))return row;Thread.sleep(10);}throw new AssertionError("preparation timed out");}
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory("tripoder-export-test-"),input=Files.createDirectory(base.resolve("输入"));Files.createDirectory(input.resolve("empty"));byte[] bytes=new byte[600003];new Random(7).nextBytes(bytes);Files.write(input.resolve("sample.bin"),bytes);Files.write(input.resolve("zero"),new byte[0]);
        Path root=base.resolve("exports");
        try(FileExports store=new FileExports(root,s->Path.of(s.getString("path")))){
            store.begin("tree",source(input),false);check(ready(store,"tree").getString("state").equals("READY"),"directory failed");
            JSONArray entries=store.manifest("tree",0,128).getJSONArray("entries");check(entries.length()==4,"missing empty dirs/files");
            JSONObject file=entries.getJSONObject(2);check(file.getString("path").equals("sample.bin"),"ordering");
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();long offset=0;
            do{JSONObject chunk=store.read("tree",file.getString("entryId"),offset,FileExports.CHUNK);out.write(Base64.getDecoder().decode(chunk.getString("data")));offset=chunk.getLong("nextOffset");}while(offset<bytes.length);
            check(Arrays.equals(bytes,out.toByteArray()),"bytes differ");Files.delete(input.resolve("sample.bin"));check(store.read("tree",file.getString("entryId"),0,1).getInt("nextOffset")==1,"snapshot depends on live source");
            check(store.begin("tree",source(input),false).getString("state").equals("READY"),"same-id replay");
            try{store.begin("tree",source(input.resolve("zero")),false);throw new AssertionError("accepted changed command");}catch(IllegalArgumentException expected){}
            check(store.receipt("tree").getString("state").equals("SUCCEEDED"),"missing operation receipt");
            try{store.read("tree","../escape",0,1);throw new AssertionError("entry traversal");}catch(IllegalArgumentException expected){}
            Path link=input.resolve("link");Files.createSymbolicLink(link,input);store.begin("links",source(input),false);check(ready(store,"links").getString("state").equals("FAILED"),"implicit link follow");
            store.begin("cycle",source(input),true);check(ready(store,"cycle").getString("state").equals("FAILED"),"symlink cycle");Files.delete(link);
            Path outside=base.resolve("outside");Files.writeString(outside,"outside");Files.createSymbolicLink(input.resolve("outside-link"),outside);
            store.begin("guest-links",new JSONObject(source(input).toString()).put("kind","guest"),true);check(ready(store,"guest-links").getString("state").equals("FAILED"),"guest symlink escape");Files.delete(input.resolve("outside-link"));
            Path denied=input.resolve("denied");Files.writeString(denied,"private");Files.setPosixFilePermissions(denied,Set.of());store.begin("denied",source(denied),false);check(ready(store,"denied").getString("state").equals("FAILED"),"unreadable source accepted");Files.setPosixFilePermissions(denied,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try{store.begin("self",source(base),false);throw new AssertionError("self recursion");}catch(java.io.IOException expected){}
            store.action("release-tree","tree",true);check(store.action("release-tree","tree",true).getString("state").equals("RELEASED"),"release replay");check(store.receipt("release-tree").getString("state").equals("SUCCEEDED"),"release receipt");try{store.action("release-tree","links",true);throw new AssertionError("changed release command");}catch(IllegalArgumentException expected){}try{store.read("tree",file.getString("entryId"),0,1);throw new AssertionError("released read");}catch(java.io.IOException expected){}
        }
        Path interrupted=Files.createDirectory(root.resolve("interrupted"));Files.createDirectory(interrupted.resolve("data"));Files.writeString(interrupted.resolve("data/e0"),"half");Files.writeString(interrupted.resolve("export.json"),new JSONObject().put("exportId","interrupted").put("state","PREPARING").put("createdAt",System.currentTimeMillis()).put("bytes",4).toString());
        try(FileExports restarted=new FileExports(root,s->Path.of(s.getString("path")))){check(restarted.status("tree").getString("state").equals("RELEASED"),"persistent terminal state");check(restarted.status("interrupted").getString("state").equals("INTERRUPTED"),"preparation replayed after restart");check(!Files.exists(interrupted.resolve("data")),"half snapshot retained");}
        System.out.println("FileExports: binary chunks, empty dirs/files, immutable copy, idempotence, operation receipts, traversal, links, cycle, overlap, release and restart passed");
    }
}
