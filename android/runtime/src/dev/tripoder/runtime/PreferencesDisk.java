package dev.tripoder.runtime;

import android.os.SystemClock;
import android.system.*;
import java.io.*;
import java.nio.channels.*;
import java.util.concurrent.Callable;

/** Stable lock inode, short disk transactions. Process death releases the kernel lock. */
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
