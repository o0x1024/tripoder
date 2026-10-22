package dev.tripoder.hooklab;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Host-testable export store. Source bytes never travel through a shell or a guest worker.
 * Only READY snapshots are readable. A restarted preparation is INTERRUPTED, never replayed. */
final class FileExports implements AutoCloseable {
    static final int CHUNK=262144, ENTRIES=10000, DEPTH=64;
    static final long FILE_LIMIT=1L<<30, TOTAL_LIMIT=4L<<30, TTL=86400000L;
    interface Resolver { Path resolve(JSONObject source)throws Exception; }
    private final Path root;
    private final Resolver resolver;
    private final Map<String,Job> jobs=new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong storedBytes=new java.util.concurrent.atomic.AtomicLong();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor();
    private static final class Job {
        JSONObject meta; final Path dir; boolean cancelled; long staged; long used=System.currentTimeMillis();
        Job(Path dir,JSONObject meta){this.dir=dir;this.meta=meta;}
    }
    FileExports(Path root,Resolver resolver)throws Exception{
        Path candidate=root.toAbsolutePath().normalize();this.resolver=resolver;
        if(Files.isSymbolicLink(candidate))throw new IOException("EXPORT_STORE_SYMLINK");
        Files.createDirectories(candidate);this.root=candidate.toRealPath();
        try(var dirs=Files.newDirectoryStream(this.root)){
            for(Path dir:dirs){if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("EXPORT_STORE_INVALID");
                Path meta=dir.resolve("export.json");if(!Files.exists(meta))continue;
                Job job=new Job(dir,new JSONObject(Files.readString(meta)));id(job.meta.getString("exportId"));
                job.used=job.meta.optLong("lastUsedAt",job.meta.getLong("createdAt"));
                job.staged=job.meta.optLong("storedBytes",job.meta.optLong("bytes"));storedBytes.addAndGet(job.staged);
                if("PREPARING".equals(job.meta.getString("state"))){job.meta.put("state","INTERRUPTED").put("errorCode","EXPORT_PREPARATION_INTERRUPTED");save(job);clearData(job);}
                jobs.put(job.meta.getString("exportId"),job);
            }
        }
        reap();reaper.scheduleWithFixedDelay(()->{try{reap();}catch(Exception ignored){}},60,60,TimeUnit.SECONDS);
    }
    static String id(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("EXPORT_ID_INVALID");return id;}
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject object){TreeMap<String,String> fields=new TreeMap<>();for(var keys=object.keys();keys.hasNext();){String key=keys.next();fields.put(JSONObject.quote(key),canonical(object.get(key)));}return fields.toString();}
        if(value instanceof JSONArray array){List<String> values=new ArrayList<>();for(int i=0;i<array.length();i++)values.add(canonical(array.get(i)));return values.toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static void save(Job job)throws Exception{
        Path temp=job.dir.resolve("export.tmp");byte[] bytes=job.meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(temp.toFile())){out.write(bytes);out.getFD().sync();}
        Files.move(temp,job.dir.resolve("export.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private synchronized Job job(String id)throws Exception{Job job=jobs.get(id(id));if(job==null)throw new IOException("EXPORT_NOT_FOUND");return job;}
    private static JSONObject summary(Job job)throws Exception{
        JSONObject out=new JSONObject(job.meta.toString());out.remove("entries");return out;
    }
    JSONObject receipt(String operation)throws Exception{
        Job job; synchronized(this){job=jobs.get(operation);}if(job==null){Path action=root.resolve("actions").resolve(id(operation)+".json");return Files.exists(action)?new JSONObject(Files.readString(action)):null;}
        synchronized(job){return new JSONObject().put("operationId",operation).put("state",job.meta.has("completedAt")?"SUCCEEDED":switch(job.meta.getString("state")){case "PREPARING"->"RUNNING";case "READY"->"SUCCEEDED";default->"FAILED";}).put("result",summary(job));}
    }
    synchronized JSONObject action(String operation,String exportId,boolean release)throws Exception{
        id(operation);id(exportId);if(jobs.containsKey(operation))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
        Path actions=root.resolve("actions");Files.createDirectories(actions);Path path=actions.resolve(operation+".json");
        String request=(release?"file.exportRelease":"file.exportCancel")+":"+exportId;
        if(Files.exists(path)){JSONObject old=new JSONObject(Files.readString(path));if(!request.equals(old.getString("request")))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");return old.getJSONObject("result");}
        JSONObject result=finish(exportId,release);Job record=new Job(actions,new JSONObject().put("operationId",operation).put("request",request).put("state","SUCCEEDED").put("result",result));
        Path temp=actions.resolve(operation+".tmp");try(FileOutputStream out=new FileOutputStream(temp.toFile())){out.write(record.meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE);return result;
    }
    JSONObject stat(JSONObject source)throws Exception{
        Path path=resolver.resolve(source);BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class);
        return new JSONObject().put("name",path.getFileName()==null?"root":path.getFileName().toString()).put("type",attrs.isDirectory()?"directory":attrs.isRegularFile()?"file":"special").put("size",attrs.size()).put("mtimeMs",attrs.lastModifiedTime().toMillis());
    }
    JSONObject list(JSONObject source,int cursor,int limit)throws Exception{
        if(cursor<0||limit<1||limit>128)throw new IllegalArgumentException("FILE_LIST_RANGE");
        Path path=resolver.resolve(source);List<Path> children=new ArrayList<>();
        try(var stream=Files.newDirectoryStream(path)){for(Path child:stream){if(children.size()>=ENTRIES)throw new IOException("FILE_LIST_LIMIT");children.add(child);}}
        children.sort(Comparator.comparing(p->p.getFileName().toString()));JSONArray rows=new JSONArray();
        if(cursor>children.size())throw new IllegalArgumentException("FILE_LIST_RANGE");
        int end=cursor,budget=0;while(end<children.size()&&end<cursor+limit){Path child=children.get(end);BasicFileAttributes a=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);JSONObject row=new JSONObject().put("name",child.getFileName().toString()).put("type",a.isSymbolicLink()?"symlink":a.isDirectory()?"directory":a.isRegularFile()?"file":"special").put("size",a.size());int bytes=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(budget+bytes>65536&&end>cursor)break;budget+=bytes;rows.put(row);end++;}
        return new JSONObject().put("entries",rows).put("nextCursor",end<children.size()?end:-1).put("consistency","LIVE_DIRECTORY");
    }
    synchronized JSONObject begin(String operation,JSONObject source,boolean follow)throws Exception{
        id(operation);String fingerprint=digest(canonical(new JSONObject().put("source",source).put("followSymlinks",follow)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(Files.exists(root.resolve("actions").resolve(operation+".json")))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
        Job prior=jobs.get(operation);if(prior!=null){synchronized(prior){if(!fingerprint.equals(prior.meta.getString("requestSha256")))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");return summary(prior);}}
        reap();long occupied=storedBytes.get();
        long live=0;for(Job j:jobs.values())synchronized(j){if(Set.of("PREPARING","READY").contains(j.meta.optString("state")))live++;}
        if(occupied>=TOTAL_LIMIT||live>=128)throw new IOException("EXPORT_STORE_QUOTA");
        Path path=resolver.resolve(source).toRealPath();
        if(root.startsWith(path)||path.startsWith(root))throw new IOException("EXPORT_SOURCE_OVERLAPS_STORE");
        Path dir=root.resolve(operation);Files.createDirectory(dir);
        Job job=new Job(dir,new JSONObject().put("exportId",operation).put("operationId",operation).put("source",source).put("requestSha256",fingerprint).put("followSymlinks",follow).put("state","PREPARING").put("createdAt",System.currentTimeMillis()).put("bytes",0).put("files",0).put("entries",new JSONArray()));
        jobs.put(operation,job);save(job);
        try{workers.execute(()->prepare(job,path,follow));}
        catch(RejectedExecutionException busy){job.meta.put("state","FAILED").put("errorCode","EXPORT_PREPARATION_BUSY");save(job);}
        synchronized(job){return summary(job);}
    }
    private void prepare(Job job,Path source,boolean follow){
        try{
            Files.createDirectory(job.dir.resolve("data"));
            walk(job,source,"",source.toRealPath(),follow,new HashSet<>(),0);
            synchronized(job){check(job);job.meta.put("state","READY").put("completedAt",System.currentTimeMillis());save(job);}
        }catch(Exception error){synchronized(job){try{if(!job.cancelled){job.meta.put("state","FAILED").put("errorCode",error instanceof AccessDeniedException?"FILE_ACCESS_DENIED":error instanceof NoSuchFileException?"FILE_NOT_FOUND":String.valueOf(error.getMessage())).put("failedPath",error instanceof FileSystemException?((FileSystemException)error).getFile():"");save(job);}clearData(job);}catch(Exception ignored){}}}
    }
    private static void check(Job job)throws IOException{if(job.cancelled||Thread.currentThread().isInterrupted())throw new IOException("EXPORT_CANCELLED");}
    private void walk(Job job,Path input,String relative,Path boundary,boolean follow,Set<Path> ancestors,int depth)throws Exception{
        synchronized(job){check(job);if(depth>DEPTH||job.meta.getJSONArray("entries").length()>=ENTRIES)throw new IOException("EXPORT_ENTRY_LIMIT");}
        if(Files.isSymbolicLink(input)&&!follow)throw new IOException("EXPORT_SYMLINK_REQUIRES_FOLLOW:"+relative);
        Path real=input.toRealPath();if(real.startsWith(root)||root.startsWith(real))throw new IOException("EXPORT_SOURCE_OVERLAPS_STORE");
        // Guest sources cannot use a symlink to escape their selected storage root.
        if("guest".equals(job.meta.getJSONObject("source").getString("kind"))&&!real.startsWith(boundary))throw new IOException("EXPORT_GUEST_PATH_ESCAPE");
        BasicFileAttributes before=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        String entryId; synchronized(job){entryId="e"+job.meta.getJSONArray("entries").length();}
        JSONObject entry=new JSONObject().put("entryId",entryId).put("path",relative).put("type",before.isDirectory()?"directory":"file").put("mtimeMs",before.lastModifiedTime().toMillis());
        if(before.isDirectory()){
            if(!ancestors.add(real))throw new IOException("EXPORT_SYMLINK_CYCLE");
            synchronized(job){job.meta.getJSONArray("entries").put(entry);}
            List<Path> children=new ArrayList<>();try(var stream=Files.newDirectoryStream(real)){for(Path child:stream){if(children.size()>=ENTRIES)throw new IOException("EXPORT_ENTRY_LIMIT");children.add(child);}}
            children.sort(Comparator.comparing(p->p.getFileName().toString()));
            for(Path child:children)walk(job,child,relative.isEmpty()?child.getFileName().toString():relative+"/"+child.getFileName(),boundary,follow,ancestors,depth+1);
            ancestors.remove(real);
        }else{
            if(!before.isRegularFile())throw new IOException("EXPORT_SPECIAL_FILE_UNSUPPORTED:"+relative);
            if(before.size()>FILE_LIMIT)throw new IOException("EXPORT_FILE_LIMIT");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
            try(SeekableByteChannel in=Files.newByteChannel(real,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));FileOutputStream out=new FileOutputStream(job.dir.resolve("data").resolve(entryId).toFile())){
                ByteBuffer buffer=ByteBuffer.allocate(65536);int count;
                while((count=in.read(buffer))!=-1){if(count==0)continue;size+=count;synchronized(job){check(job);long total=job.meta.getLong("bytes")+count;if(size>FILE_LIMIT||total>TOTAL_LIMIT||root.toFile().getUsableSpace()<count+(64L<<20))throw new IOException("EXPORT_STORAGE_QUOTA");if(storedBytes.addAndGet(count)>TOTAL_LIMIT){storedBytes.addAndGet(-count);throw new IOException("EXPORT_STORE_QUOTA");}job.staged+=count;job.meta.put("storedBytes",job.staged).put("bytes",total);}
                    sha.update(buffer.array(),0,count);out.write(buffer.array(),0,count);buffer.clear();}
                out.getFD().sync();
            }
            if(size!=before.size()&&before.size()!=0)throw new IOException("EXPORT_SOURCE_CHANGED:"+relative);
            entry.put("size",size).put("sha256",HexFormat.of().formatHex(sha.digest()));
            synchronized(job){job.meta.getJSONArray("entries").put(entry);job.meta.put("files",job.meta.getInt("files")+1);save(job);}
        }
        BasicFileAttributes after=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||before.size()!=after.size()||!input.toRealPath().equals(real))throw new IOException("EXPORT_SOURCE_CHANGED:"+relative);
    }
    private static void touch(Job job)throws Exception{job.used=System.currentTimeMillis();if(job.used-job.meta.optLong("lastUsedAt")>60000){job.meta.put("lastUsedAt",job.used);save(job);}}
    JSONObject status(String id)throws Exception{Job job=job(id);synchronized(job){touch(job);return summary(job);}}
    JSONObject manifest(String id,int cursor,int limit)throws Exception{
        Job job=job(id);synchronized(job){ready(job);JSONArray all=job.meta.getJSONArray("entries"),page=new JSONArray();if(cursor<0||cursor>all.length()||limit<1||limit>128)throw new IllegalArgumentException("EXPORT_MANIFEST_RANGE");int end=cursor,budget=0;while(end<all.length()&&end<cursor+limit){String value=all.getJSONObject(end).toString();int bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(budget+bytes>65536&&end>cursor)break;budget+=bytes;page.put(new JSONObject(value));end++;}return new JSONObject().put("exportId",id).put("entries",page).put("nextCursor",end<all.length()?end:-1).put("entryCount",all.length());}
    }
    private static void ready(Job job)throws Exception{if(!"READY".equals(job.meta.getString("state")))throw new IOException("EXPORT_NOT_READY:"+job.meta.getString("state"));touch(job);}
    JSONObject read(String id,String entryId,long offset,int length)throws Exception{
        if(offset<0||length<1||length>CHUNK)throw new IllegalArgumentException("EXPORT_CHUNK_RANGE");Job job=job(id);
        synchronized(job){ready(job);JSONArray entries=job.meta.getJSONArray("entries");if(!entryId.matches("e[0-9]{1,5}"))throw new IllegalArgumentException("EXPORT_ENTRY_ID_INVALID");int index=Integer.parseInt(entryId.substring(1));if(index>=entries.length())throw new IOException("EXPORT_ENTRY_NOT_FOUND");JSONObject entry=entries.getJSONObject(index);if(!entryId.equals(entry.getString("entryId"))||!"file".equals(entry.getString("type")))throw new IOException("EXPORT_ENTRY_NOT_FILE");long size=entry.getLong("size");if(offset>size)throw new IllegalArgumentException("EXPORT_CHUNK_RANGE");
            byte[] bytes=new byte[(int)Math.min(length,size-offset)];Path path=job.dir.resolve("data").resolve(entryId);if(Files.isSymbolicLink(path)||Files.size(path)!=size)throw new IOException("EXPORT_SNAPSHOT_CHANGED");try(RandomAccessFile in=new RandomAccessFile(path.toFile(),"r")){in.seek(offset);in.readFully(bytes);}
            return new JSONObject().put("exportId",id).put("entryId",entryId).put("offset",offset).put("nextOffset",offset+bytes.length).put("size",size).put("sha256",entry.getString("sha256")).put("encoding","base64").put("data",Base64.getEncoder().encodeToString(bytes)).put("eof",offset+bytes.length==size);
        }
    }
    JSONObject finish(String id,boolean release)throws Exception{Job job=job(id);synchronized(job){job.cancelled=true;job.meta.put("state",release?"RELEASED":"CANCELLED");save(job);clearData(job);return summary(job);}}
    private void clearData(Job job)throws Exception{Path data=job.dir.resolve("data");if(Files.exists(data,LinkOption.NOFOLLOW_LINKS)){try(var paths=Files.walk(data)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}storedBytes.addAndGet(-job.staged);job.staged=0;job.meta.put("storedBytes",0);if(!"READY".equals(job.meta.optString("state")))job.meta.put("entries",new JSONArray());save(job);}
    private synchronized void reap()throws Exception{long now=System.currentTimeMillis();for(Job job:jobs.values())synchronized(job){if(!"PREPARING".equals(job.meta.getString("state"))&&now-Math.max(job.used,job.meta.getLong("createdAt"))>TTL&&!Set.of("EXPIRED","RELEASED").contains(job.meta.getString("state"))){job.cancelled=true;job.meta.put("state","EXPIRED");save(job);clearData(job);}}}
    @Override public void close(){reaper.shutdownNow();workers.shutdownNow();}
}
