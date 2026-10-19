package dev.tripoder.runtime;

import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import android.system.Os;
import android.system.OsConstants;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Loads the complete verified DEX sequence without letting ART create sidecars in sealed artifacts. */
final class RuntimeDexLoader {
    private static final long MAX_DEX_BYTES=512L*1024*1024;
    private record DexTarget(File apk,ArtifactSet.FileRecord file,File extracted){}
    static ClassLoader create(ArtifactSet set,String libraries,ClassLoader parent)throws Exception{
        if(set.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        // The exact same physical name must reach ART, linker notifications and
        // Native observation. Resolve the platform's data-directory alias once,
        // before guest code; never admit alternate names in the module observer.
        if(!new File(set.directory,"lib").getCanonicalPath().equals(libraries))
            throw new SecurityException("NATIVE_LIBRARY_SEARCH_PATH_NOT_CANONICAL_ARTIFACT");
        List<DexTarget> records=new ArrayList<>();
        List<File> apkFiles=new ArrayList<>(set.parts.size());
        long total=0;
        for(int p=0;p<set.parts.size();p++){
            ArtifactSet.Part part=set.parts.get(p);apkFiles.add(part.file);
            TreeMap<Integer,ArtifactSet.FileRecord> dex=new TreeMap<>();
            for(var file:part.entries){
                if(file.path.contains("/")||!file.path.endsWith(".dex"))continue;
                if(!file.path.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))throw new IOException("DEX_NAME_UNSUPPORTED");
                String suffix=file.path.substring(7,file.path.length()-4);int index=suffix.isEmpty()?1:Integer.parseInt(suffix);
                if(index>128||dex.put(index,file)!=null)throw new IOException("DEX_COUNT_LIMIT");
            }
            if(p==0?(dex.isEmpty()||dex.lastKey()!=dex.size()):(!dex.isEmpty()&&dex.lastKey()!=dex.size()))throw new IOException("DEX_SEQUENCE_INCOMPLETE");
            for(var file:dex.values()){
                if(records.size()>=128)throw new IOException("DEX_COUNT_LIMIT");
                total+=file.size;if(file.size<=0||total>MAX_DEX_BYTES)throw new IOException("DEX_MEMORY_LIMIT");
                records.add(new DexTarget(part.file,file,new File(set.directory,ArtifactSet.dexExtractedPath(p,file.path))));
            }
        }
        ByteBuffer[] buffers=new ByteBuffer[records.size()];
        // One central-directory parse per APK with DEX entries: ZipFile is safe for concurrent getInputStream,
        // and parsing it per DEX (interpreted) dominated LOAD for large applications.
        Map<File,ZipFile> openZips=new LinkedHashMap<>();
        try{
            for(var target:records)if(!target.extracted().isFile()&&!openZips.containsKey(target.apk()))openZips.put(target.apk(),new ZipFile(target.apk()));
            if(records.size()==1){
                DexTarget target=records.get(0);
                buffers[0]=target.extracted().isFile()?mapExtracted(target.extracted(),target.file()):mapEntry(openZips.get(target.apk()),target.file());
            }else{
                int threads=Math.min(records.size(),Math.max(2,Runtime.getRuntime().availableProcessors()));
                var pool=java.util.concurrent.Executors.newFixedThreadPool(threads);
                try{
                    List<java.util.concurrent.Future<ByteBuffer>> futures=new ArrayList<>(records.size());
                    for(var target:records){ZipFile apk=openZips.get(target.apk());futures.add(pool.submit(()->target.extracted().isFile()?mapExtracted(target.extracted(),target.file()):mapEntry(apk,target.file())));}
                    for(int i=0;i<records.size();i++){
                        try{buffers[i]=futures.get(i).get();}
                        catch(java.util.concurrent.ExecutionException error){
                            Throwable cause=error.getCause();
                            if(cause instanceof Exception ex)throw ex;
                            if(cause instanceof Error err)throw err;
                            throw error;
                        }
                    }
                }finally{pool.shutdownNow();}
            }
        }finally{
            for(ZipFile zip:openZips.values())try{zip.close();}catch(IOException ignored){}
        }
        return new InMemoryDexClassLoader(buffers,libraries,new RuntimeResourceLoader(parent,apkFiles));
    }
    private static ByteBuffer mapExtracted(File extracted,ArtifactSet.FileRecord expected)throws Exception{
        ArtifactSet.regular(extracted.toPath());ArtifactSet.readonly(extracted.toPath());
        FileDescriptor fd=Os.memfd_create("tripoder-dex",OsConstants.MFD_CLOEXEC|MFD_ALLOW_SEALING);
        try{
            long total=0;
            try(FileInputStream in=new FileInputStream(extracted)){
                byte[] chunk=new byte[131072];int n;
                while((n=in.read(chunk))!=-1){
                    total+=n;if(total>expected.size)throw new IOException("DEX_LENGTH_CHANGED");
                    for(int offset=0;offset<n;){int written=Os.write(fd,chunk,offset,n-offset);if(written<=0)throw new IOException("DEX_WRITE_INCOMPLETE");offset+=written;}
                }
            }
            if(total!=expected.size)throw new IOException("DEX_LENGTH_CHANGED");
            if(Os.fcntlInt(fd,F_ADD_SEALS,REQUIRED_SEALS)!=0||(Os.fcntlInt(fd,F_GET_SEALS,0)&REQUIRED_SEALS)!=REQUIRED_SEALS)throw new IOException("DEX_SEAL_FAILED");
            try(FileInputStream view=new FileInputStream(fd)){return view.getChannel().map(FileChannel.MapMode.READ_ONLY,0,expected.size);}
        }finally{try{if(fd.valid())Os.close(fd);}catch(Exception ignored){}}
    }
    private static ByteBuffer mapEntry(ZipFile apk,ArtifactSet.FileRecord file)throws Exception{
        ZipEntry entry=apk.getEntry(file.path);if(entry==null||entry.getSize()!=file.size)throw new IOException("DEX_ENTRY_CHANGED");
        return mapVerified(apk,entry,file);
    }
    /** Linux UAPI constants from the pinned NDK linux/fcntl.h and linux/memfd.h. */
    private static final int MFD_ALLOW_SEALING=2,F_ADD_SEALS=1033,F_GET_SEALS=1034,REQUIRED_SEALS=15;
    private static ByteBuffer mapVerified(ZipFile apk,ZipEntry entry,ArtifactSet.FileRecord expected)throws Exception{
        FileDescriptor fd=Os.memfd_create("tripoder-dex",OsConstants.MFD_CLOEXEC|MFD_ALLOW_SEALING);
        boolean transferred=false;
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream input=apk.getInputStream(entry)){
                byte[] chunk=new byte[65536];int n;
                while((n=input.read(chunk))!=-1){total+=n;if(total>expected.size)throw new IOException("DEX_LENGTH_CHANGED");digest.update(chunk,0,n);
                    for(int offset=0;offset<n;){int written=Os.write(fd,chunk,offset,n-offset);if(written<=0)throw new IOException("DEX_WRITE_INCOMPLETE");offset+=written;}
                }
            }
            if(total!=expected.size)throw new IOException("DEX_LENGTH_CHANGED");
            if(!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256))throw new SecurityException("DEX_DIGEST_MISMATCH");
            Os.fcntlInt(fd,F_ADD_SEALS,REQUIRED_SEALS);
            if((Os.fcntlInt(fd,F_GET_SEALS,0)&REQUIRED_SEALS)!=REQUIRED_SEALS)throw new SecurityException("DEX_SEAL_NOT_CONFIRMED");
            // Map only after write/grow/shrink seals. Closing the anonymous fd leaves
            // the read-only mapping alive, without a mutable filesystem sidecar.
            FileInputStream mapped=new FileInputStream(fd);transferred=true;
            try(mapped){return mapped.getChannel().map(FileChannel.MapMode.READ_ONLY,0,total);}
        }finally{if(!transferred)Os.close(fd);}
    }
    private RuntimeDexLoader(){}
}
