package dev.tripoder.selfhook;

import android.os.IBinder;

/** Drives an {@code ANativeService} entry point inside a process that has ART. The platform's native
 * service process is not involved: the entry point only fills a record of callbacks, so this owns that
 * record. One host per library file; every call runs on the thread that created it (the main thread). */
public final class NativeServiceHost {
    private final long handle;
    private NativeServiceHost(long handle){this.handle=handle;}
    private static native long create(int fd,long offset,String library,String function);
    private static native IBinder bind(long handle,long token,String action,String data);
    private static native boolean unbind(long handle,long token);
    private static native void rebind(long handle,long token);
    private static native void destroy(long handle);
    private static native void trim(long handle,int level);
    /** {@code fd} is a verified, read-only file; {@code offset} is where the ELF begins inside it. */
    public static NativeServiceHost start(int fd,long offset,String library,String function){
        if(fd<0||offset<0||library==null||function==null)throw new IllegalArgumentException("NATIVE_SERVICE_ARGUMENTS");
        return new NativeServiceHost(create(fd,offset,library,function));
    }
    public IBinder onBind(long token,String action,String data){return bind(handle,token,action,data);}
    public boolean onUnbind(long token){return unbind(handle,token);}
    public void onRebind(long token){rebind(handle,token);}
    public void onTrimMemory(int level){trim(handle,level);}
    public void onDestroy(){destroy(handle);}
}
