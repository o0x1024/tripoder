package dev.tripoder.selfhook;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.util.SparseArray;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/** Fixed F2 accessors. No component hook installation and no borrowed host facade. */
public final class ComponentFrameworkAccess {
    private static boolean attempted;
    private static volatile ComponentFrameworkAccess instance;
    private final Method pending,setPending,protectedQuery,flags,provider;
    private final Object packages;
    private final Field selection,index,key,pendingKind;
    private final Class<?> backReference;
    private ComponentFrameworkAccess()throws Exception {
        pending=FixedFrameworkMembers.method(15,"getPendingResult","()Landroid/content/BroadcastReceiver$PendingResult;",false);
        setPending=FixedFrameworkMembers.method(15,"setPendingResult","(Landroid/content/BroadcastReceiver$PendingResult;)V",false);
        Method pm=FixedFrameworkMembers.method(1,"getPackageManager","()Landroid/content/pm/IPackageManager;",true);
        protectedQuery=FixedFrameworkMembers.method(16,"isProtectedBroadcast","(Ljava/lang/String;)Z",false);
        flags=FixedFrameworkMembers.method(17,"getValue","()J",false);
        provider=FixedFrameworkMembers.method(18,"getIContentProvider","()Landroid/content/IContentProvider;",false);
        selection=FixedFrameworkMembers.field(14,19,"mSelectionArgs","Landroid/util/SparseArray;",false);
        index=FixedFrameworkMembers.field(15,20,"fromIndex","I",false);key=FixedFrameworkMembers.field(16,20,"fromKey","Ljava/lang/String;",false);
        pendingKind=FixedFrameworkMembers.field(66,25,"mType","I",false);
        if(!java.lang.reflect.Modifier.isFinal(pendingKind.getModifiers()))throw new IllegalStateException("RECEIVER_PENDING_KIND_NOT_FINAL");
        backReference=FixedFrameworkMembers.owner(20);
        packages=Objects.requireNonNull(FixedFrameworkMembers.call(pm,null),"PHYSICAL_PACKAGE_MANAGER_REQUIRED");
        if(!FixedFrameworkMembers.owner(16).isInstance(packages))throw new IllegalStateException("PHYSICAL_PACKAGE_MANAGER_TYPE");
    }
    public static synchronized void prepare()throws Exception {
        if(attempted)throw new IllegalStateException("COMPONENT_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;instance=new ComponentFrameworkAccess();
    }
    private static ComponentFrameworkAccess require(){ComponentFrameworkAccess v=instance;if(v==null)throw new IllegalStateException("COMPONENT_FRAMEWORK_NOT_READY");return v;}
    static Object physicalPackages(){return require().packages;}
    public static Object pendingResult(BroadcastReceiver receiver){return FixedFrameworkMembers.call(require().pending,Objects.requireNonNull(receiver));}
    public static void pendingResult(BroadcastReceiver receiver,Object token){FixedFrameworkMembers.call(require().setPending,Objects.requireNonNull(receiver),token);}
    /** SDK dispatch role, never inferred from a mutable Intent or a result code. */
    public static int pendingKind(Object pending){
        int kind=(Integer)FixedFrameworkMembers.get(require().pendingKind,Objects.requireNonNull(pending));
        if(kind<0||kind>2)throw new SecurityException("RECEIVER_PENDING_KIND_INVALID");return kind;
    }
    public static boolean protectedBroadcast(String action){ComponentFrameworkAccess v=require();return (Boolean)FixedFrameworkMembers.call(v.protectedQuery,v.packages,Objects.requireNonNull(action));}
    public static long bindFlags(Context.BindServiceFlags flags){return (Long)FixedFrameworkMembers.call(require().flags,Objects.requireNonNull(flags));}
    public static Object providerHandle(ContentProvider provider){return Objects.requireNonNull(FixedFrameworkMembers.call(require().provider,Objects.requireNonNull(provider)),"PROVIDER_HANDLE_REQUIRED");}
    public static final class BackReference {
        public final int fromIndex;public final String fromKey;
        private BackReference(int index,String key){fromIndex=index;fromKey=key;}
    }
    public static final class Selection {
        private final int[] keys;private final Object[] values;
        private Selection(int[] keys,Object[] values){this.keys=keys;this.values=values;}
        public int[] keys(){return keys.clone();}public Object[] values(){return values.clone();}
    }
    public static Selection selection(ContentProviderOperation operation){
        ComponentFrameworkAccess v=require();if(operation==null||operation.getClass()!=ContentProviderOperation.class)throw new SecurityException("PROVIDER_BATCH_OPERATION_CLASS_INVALID");
        Object raw=FixedFrameworkMembers.get(v.selection,operation);if(raw==null)return new Selection(new int[0],new Object[0]);
        if(raw.getClass()!=SparseArray.class)throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TYPE_INVALID");
        SparseArray<?> sparse=(SparseArray<?>)raw;int count=sparse.size();
        if(count>128)throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TOO_LARGE");
        int[] keys=new int[count];Object[] values=new Object[count];
        for(int i=0;i<count;i++) {
            keys[i]=sparse.keyAt(i);Object value=sparse.valueAt(i);
            if(value!=null&&value.getClass()==v.backReference)value=new BackReference((Integer)FixedFrameworkMembers.get(v.index,value),(String)FixedFrameworkMembers.get(v.key,value));
            else if(value!=null&&!(value instanceof String))throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TYPE_INVALID");
            values[i]=value;
        }
        if(sparse.size()!=count)throw new IllegalStateException("PROVIDER_BATCH_SELECTION_CHANGED");
        return new Selection(keys,values);
    }
}
