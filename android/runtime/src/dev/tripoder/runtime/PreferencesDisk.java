package dev.tripoder.runtime;

import android.os.SystemClock;
import android.system.*;
import java.io.*;
import java.nio.channels.*;
import java.util.concurrent.Callable;

/**
 * Stable lock inode, short disk transactions. Process death releases the kernel lock.
 *
 * The kernel lock is per open file description, not per thread: a second attempt to lock the same path from this process
 * fails with OverlappingFileLockException rather than blocking, and it can never be handed off between threads. Callers
 * therefore must not enter this method twice for the same file at once; {@code synchronized} here serializes the
 * transaction for one disk object, and {@link RuntimeStorage#preferences} guarantees one disk object per file.
 */
final class PreferencesDisk {
    private final RuntimeStorage storage;
    private final File file,lockPath;
    PreferencesDisk(RuntimeStorage storage,File file){this.storage=storage;this.file=file;lockPath=new File(file+".lock");}
    synchronized <T>T access(Callable<T> operation)throws Exception{
        storage.check(lockPath);storage.check(file);
        try(var stream=new FileOutputStream(Os.open(lockPath.toString(),OsConstants.O_RDWR|OsConstants.O_CREAT|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0600))){
            FileChannel channel=stream.getChannel();long deadline=SystemClock.elapsedRealtime()+5000;FileLock acquired;
            while((acquired=channel.tryLock())==null){
                if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("PREFERENCES_LOCK_TIMEOUT");
                try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
            }
            try(FileLock held=acquired){storage.check(file);return operation.call();}
        }
    }
}
