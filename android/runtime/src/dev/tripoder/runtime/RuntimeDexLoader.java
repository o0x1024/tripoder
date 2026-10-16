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
    private static final long MAX_DEX_BYTES=256L*1024*1024;
    static ClassLoader create(ArtifactSet set,String libraries,ClassLoader parent)throws Exception{
        if(set.parts.size()!=1)throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        // The exact same physical name must reach ART, linker notifications and
        // Native observation. Resolve the platform's data-directory alias once,
        // before guest code; never admit alternate names in the module observer.
        if(!new File(set.directory,"lib").getCanonicalPath().equals(libraries))
            throw new SecurityException("NATIVE_LIBRARY_SEARCH_PATH_NOT_CANONICAL_ARTIFACT");
        TreeMap<Integer,ArtifactSet.FileRecord> dex=new TreeMap<>();
        for(var file:set.parts.get(0).entries){
            if(file.path.contains("/")||!file.path.endsWith(".dex"))continue;
            if(!file.path.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))throw new IOException("DEX_NAME_UNSUPPORTED");
            String suffix=file.path.substring(7,file.path.length()-4);int index=suffix.isEmpty()?1:Integer.parseInt(suffix);
            if(index>32||dex.put(index,file)!=null)throw new IOException("DEX_COUNT_LIMIT");
        }
        if(dex.isEmpty()||dex.lastKey()!=dex.size())throw new IOException("DEX_SEQUENCE_INCOMPLETE");
        long total=0;List<ByteBuffer> buffers=new ArrayList<>();
        try(ZipFile apk=new ZipFile(set.base)){
            for(var file:dex.values()){
                total+=file.size;if(file.size<=0||total>MAX_DEX_BYTES)throw new IOException("DEX_MEMORY_LIMIT");
                ZipEntry entry=apk.getEntry(file.path);if(entry==null||entry.getSize()!=file.size)throw new IOException("DEX_ENTRY_CHANGED");
                buffers.add(mapVerified(apk,entry,file));
            }
        }
        return new InMemoryDexClassLoader(buffers.toArray(new ByteBuffer[0]),libraries,parent);
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
