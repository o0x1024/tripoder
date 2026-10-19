package dev.tripoder.runtime;

import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Authority-local verified objects. Any inode, mode, size, nanosecond time or tree change revalidates. */
final class ArtifactIntegrityCache {
    private static final long MAX_BYTES=128L*1024*1024;
    private static final int MAX_SETS=64;
    private static final int STABILITY_ATTEMPTS=4;
    /** The committed tree moved mid-verification; nothing is proven wrong with its bytes, so the install stays usable and the read is simply retried. */
    static final class VerificationUnstable extends IOException{VerificationUnstable(){super("ARTIFACT_CHANGED_DURING_VERIFICATION");}}
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private long bytes,hits,verified;
    private static final class Entry {
        final ArtifactSet set;final Map<String,String> stamp;final long cost;
        Entry(ArtifactSet set,Map<String,String> stamp){this.set=set;this.stamp=stamp;cost=2L*set.descriptorJson.length()+512L*stamp.size();}
    }
    ArtifactSet get(File directory,String digest)throws Exception{
        String key=directory.getCanonicalPath()+":"+digest;Map<String,String> before=stamp(directory);
        synchronized(this){Entry old=entries.get(key);
            if(old!=null&&old.stamp.equals(before)){hits++;return old.set;}
            if(old!=null){entries.remove(key);bytes-=old.cost;}
        }
        // Full byte verification never monopolizes the cache used by unrelated running apps.
        // Concurrent misses may verify independently; neither receives unverified shared state.
        // The tree is immutable after commit, so a stamp change across the verification means
        // it was touched concurrently (e.g. publication reconciliation during a cold-broker boot).
        // The class contract is to REVALIDATE on any change, not to condemn: re-verify against the
        // settled tree a bounded number of times. Verification always fully digests every file, so
        // a genuinely altered byte still fails as ARTIFACT_FILE_CORRUPT regardless of the retry.
        for(int attempt=0;;attempt++){
            ArtifactSet set=ArtifactSet.verify(directory,digest);
            Map<String,String> after=stamp(directory);
            if(before.equals(after)){synchronized(this){verified++;put(key,new Entry(set,after));}return set;}
            if(attempt>=STABILITY_ATTEMPTS)throw new VerificationUnstable();
            before=after;
        }
    }
    private void put(String key,Entry entry){Entry old=entries.remove(key);if(old!=null)bytes-=old.cost;if(entry.cost>MAX_BYTES)return;entries.put(key,entry);bytes+=entry.cost;
        while(entries.size()>MAX_SETS||bytes>MAX_BYTES){var oldest=entries.entrySet().iterator();var item=oldest.next();bytes-=item.getValue().cost;oldest.remove();}
    }
    synchronized android.os.Bundle stats(){android.os.Bundle b=new android.os.Bundle();b.putLong("hits",hits);b.putLong("fullVerifications",verified);b.putLong("retainedBytes",bytes);b.putInt("sets",entries.size());return b;}
    /** Stable digest of the immutable tree stamp; any inode, size, mode or time change alters it. */
    static String stampDigest(File directory)throws Exception{
        StringBuilder text=new StringBuilder();for(var e:stamp(directory).entrySet())text.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static Map<String,String> stamp(File directory)throws Exception{
        if(Files.isSymbolicLink(directory.toPath()))throw new SecurityException("ARTIFACT_SYMLINK");
        Map<String,String> values=new TreeMap<>();Path root=directory.toPath();
        try(var paths=Files.walk(root)){
            for(Iterator<Path> it=paths.iterator();it.hasNext();){
                Path path=it.next();if(values.size()>ArtifactSetZip.MAX_ENTRIES+ArtifactSet.MAX_PARTS+2)throw new SecurityException("ARTIFACT_FILE_COUNT_LIMIT");
                StructStat s=Os.lstat(path.toString());if(!OsConstants.S_ISREG(s.st_mode)&&!OsConstants.S_ISDIR(s.st_mode))throw new SecurityException("ARTIFACT_NOT_REGULAR");
                if((s.st_mode&0222)!=0)throw new SecurityException("ARTIFACT_NOT_IMMUTABLE:"+root.relativize(path)+":"+Integer.toOctalString(s.st_mode&07777));
                values.put(root.relativize(path).toString(),s.st_dev+":"+s.st_ino+":"+s.st_mode+":"+s.st_nlink+":"+s.st_uid+":"+s.st_gid+":"+s.st_size+":"+s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec+":"+s.st_ctim.tv_sec+":"+s.st_ctim.tv_nsec);
            }
        }return values;
    }
}
