package dev.tripoder.selfhook;

import android.app.Application;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Read-only physical identity observation using fixed JNI fields, never managed getter dispatch. */
public final class PhysicalApplication {
    private static boolean attempted;
    private static volatile Application host;
    private PhysicalApplication(){}
    private static native void prepareNative(Application host,Class<?> applicationClass,Class<?> threadClass,
                                             Class<?> loadedApkClass,Field[] fields);
    private static native void checkNative(Application expectedHost);
    private static native Object threadNative(Application expectedHost);
    public static native String reportNative();

    public static synchronized void prepare(Application expectedHost,FrameworkAccess.Members members) {
        if(attempted)throw new IllegalStateException("PHYSICAL_APPLICATION_ALREADY_ATTEMPTED");
        attempted=true;
        if(expectedHost==null||members==null||members.owners==null||members.owners.length!=7||
            members.fields==null||members.fields.length!=12)
            throw new IllegalArgumentException("PHYSICAL_APPLICATION_FIXED_MEMBERS_REQUIRED");
        Class<?>[] owners=members.owners.clone();Field[] registry=members.fields.clone();
        if(owners[0]!=Application.class||!boot(owners[0],"android.app.Application")||
            !boot(owners[1],"android.app.ActivityThread")||!boot(owners[2],"android.app.LoadedApk"))
            throw new IllegalArgumentException("PHYSICAL_APPLICATION_CLASS_IDENTITY");
        Field[] fields={registry[0],registry[1],registry[2],registry[11]};
        field(fields[0],owners[1],"mInitialApplication",owners[0],false);
        field(fields[1],owners[0],"mLoadedApk",owners[2],false);
        field(fields[2],owners[2],"mApplication",owners[0],false);
        field(fields[3],owners[1],"sCurrentActivityThread",owners[1],true);
        prepareNative(expectedHost,owners[0],owners[1],owners[2],fields);
        host=expectedHost;
    }
    private static boolean boot(Class<?> type,String name){
        // Android may expose a BootClassLoader object; resolve the exact boot
        // definition instead of assuming its public loader value is null.
        try{return type!=null&&Class.forName(name,false,null)==type;}
        catch(ClassNotFoundException error){return false;}
    }
    private static void field(Field value,Class<?> owner,String name,Class<?> type,boolean isStatic) {
        if(value==null||value.getDeclaringClass()!=owner||!value.getName().equals(name)||value.getType()!=type||
            Modifier.isStatic(value.getModifiers())!=isStatic)
            throw new IllegalArgumentException("PHYSICAL_APPLICATION_FIELD_IDENTITY:"+name);
    }
    public static void check(){check(host);}
    /** Exact physical ActivityThread retained and rechecked by direct JNI fields. */
    public static Object thread(){Application prepared=host;if(prepared==null)throw new IllegalStateException("PHYSICAL_APPLICATION_NOT_PREPARED");return threadNative(prepared);}
    /** Valid on any attached Java thread; the Native checker retains no thread-local JNI references. */
    public static void check(Application expectedHost) {
        Application prepared=host;
        if(prepared==null)throw new IllegalStateException("PHYSICAL_APPLICATION_NOT_PREPARED");
        if(expectedHost!=prepared)throw new SecurityException("PHYSICAL_APPLICATION_HOST_ARGUMENT");
        checkNative(expectedHost);
    }
}
