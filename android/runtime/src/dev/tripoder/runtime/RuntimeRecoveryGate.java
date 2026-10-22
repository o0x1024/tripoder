package dev.tripoder.runtime;

import android.os.IBinder;

/** Product authority admission. Probe APKs do not contain the product supervisor. */
public final class RuntimeRecoveryGate {
    private static final boolean PRODUCT=product();
    private static boolean product(){try{Class.forName("dev.tripoder.runtime.EngineRecoveryService",false,RuntimeRecoveryGate.class.getClassLoader());return true;}catch(ClassNotFoundException absent){return false;}}
    private static volatile IBinder supervisor;
    private static volatile String epoch;
    private static volatile boolean ready;
    private static Thread initializer;
    public static boolean productEnabled(){return PRODUCT;}
    static synchronized void begin(String attempt,IBinder owner)throws Exception{
        if(!PRODUCT||epoch!=null||attempt==null||owner==null||!owner.isBinderAlive())throw new IllegalStateException("RECOVERY_PERMIT_INVALID");
        epoch=attempt;supervisor=owner;initializer=Thread.currentThread();owner.linkToDeath(()->{ready=false;android.os.Process.killProcess(android.os.Process.myPid());},0);
    }
    static synchronized void complete(){requireOwner();ready=true;initializer=null;}
    static synchronized void failed(){ready=false;initializer=null;}
    private static void requireOwner(){if(supervisor==null||!supervisor.isBinderAlive())throw new IllegalStateException("RECOVERY_SUPERVISOR_UNAVAILABLE");}
    public static void require(){if(!PRODUCT)return;requireOwner();if(!ready&&Thread.currentThread()!=initializer)throw new IllegalStateException("ENGINE_RECOVERING_OR_FENCED");}
    static boolean matches(String attempt){return epoch!=null&&epoch.equals(attempt);}
    public static boolean automaticPrewarm(){return !PRODUCT;}
    private RuntimeRecoveryGate(){}
}
