package dev.tripoder.selfhook;

import dalvik.annotation.optimization.FastNative;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;

/** Fixed boot-framework registry. No arbitrary class/name lookup API. */
public final class FrameworkAccess {
    private FrameworkAccess() {}
    private static boolean prepared;
    private static final Object[][] declarationCache=new Object[59][];
    private static native boolean prepareNative(Method field,Method count,Method method);
    public static native String statusNative();
    @FastNative private static native Field fieldNative(int slot);
    @FastNative private static native int countNative(int owner);
    @FastNative private static native Object methodNative(int owner,int ordinal);

    private static final String[] CLASSES={"android.app.Application","android.app.ActivityThread","android.app.LoadedApk",
        "android.app.ContextImpl","android.content.pm.ApplicationInfo","android.app.QueuedWork","android.content.ContextWrapper"};
    private static final int[] FIELD_OWNERS={1,0,2,1,2,2,2,2,2,4,6,1};
    private static final boolean[] FIELD_STATIC={false,false,false,false,false,false,false,false,false,false,false,true};
    private static final String[] FIELD_NAMES={"mInitialApplication","mLoadedApk","mApplication","mInstrumentation",
        "mClassLoader","mDefaultClassLoader","mResources","mAppComponentFactory","mApplicationInfo","credentialProtectedDataDir","mBase","sCurrentActivityThread"};
    private static final String[] FIELD_TYPES={"Landroid/app/Application;","Landroid/app/LoadedApk;","Landroid/app/Application;",
        "Landroid/app/Instrumentation;","Ljava/lang/ClassLoader;","Ljava/lang/ClassLoader;","Landroid/content/res/Resources;",
        "Landroid/app/AppComponentFactory;","Landroid/content/pm/ApplicationInfo;","Ljava/lang/String;","Landroid/content/Context;","Landroid/app/ActivityThread;"};
    private static final int[] METHOD_OWNERS={1,1,2,2,3,3,0,5,5};
    private static final String[] METHOD_NAMES={"currentActivityThread","currentApplication","<init>","getCompatibilityInfo",
        "createAppContext","setOuterContext","attach","addFinisher","removeFinisher"};
    private static final String[] METHOD_TYPES={"()Landroid/app/ActivityThread;","()Landroid/app/Application;",
        "(Landroid/app/ActivityThread;Landroid/content/pm/ApplicationInfo;Landroid/content/res/CompatibilityInfo;Ljava/lang/ClassLoader;ZZZ)V",
        "()Landroid/content/res/CompatibilityInfo;","(Landroid/app/ActivityThread;Landroid/app/LoadedApk;Ljava/lang/String;)Landroid/app/ContextImpl;",
        "(Landroid/content/Context;)V","(Landroid/content/Context;)V","(Ljava/lang/Runnable;)V","(Ljava/lang/Runnable;)V"};
    private static final boolean[] METHOD_STATIC={true,true,false,false,true,false,false,true,true};

    public static synchronized Members prepare() throws Exception {
        if(prepared)throw new IllegalStateException("FRAMEWORK_ACCESS_ALREADY_PREPARED");
        prepared=true;
        Method field=FrameworkAccess.class.getDeclaredMethod("fieldNative",int.class);
        Method count=FrameworkAccess.class.getDeclaredMethod("countNative",int.class);
        Method method=FrameworkAccess.class.getDeclaredMethod("methodNative",int.class,int.class);
        if(!prepareNative(field,count,method))throw new IllegalStateException("FRAMEWORK_ART_CONTRACT_REJECTED");
        Class<?>[] owners=new Class<?>[CLASSES.length];
        for(int i=0;i<owners.length;i++)owners[i]=Class.forName(CLASSES[i],false,null);
        Field[] fields=new Field[FIELD_NAMES.length];
        for(int i=0;i<fields.length;i++){
            Field value=fieldNative(i);
            if(value==null||value.getDeclaringClass()!=owners[FIELD_OWNERS[i]]||!value.getName().equals(FIELD_NAMES[i])||
                !type(value.getType()).equals(FIELD_TYPES[i])||Modifier.isStatic(value.getModifiers())!=FIELD_STATIC[i])
                throw new IllegalStateException("FRAMEWORK_FIELD_IDENTITY:"+FIELD_NAMES[i]+":"+(value==null?"null":value.toString()));
            fields[i]=value;
        }
        Object[] methods=new Object[METHOD_NAMES.length];
        int reflected=0;
        for(int owner:new int[]{0,1,2,3,5}){
            int size=countNative(owner);
            if(size<1||size>1024)throw new IllegalStateException("FRAMEWORK_METHOD_BUDGET:"+owner);
            Object[] declarations=new Object[size];
            for(int ordinal=0;ordinal<size;ordinal++){
                Object candidate=methodNative(owner,ordinal);
                declarations[ordinal]=candidate;
                if(candidate==null)continue;
                reflected++;
                String name,signature;Class<?> declaring;boolean isStatic;
                if(candidate instanceof Method){Method value=(Method)candidate;
                    name=value.getName();signature=signature(value.getParameterTypes(),value.getReturnType());
                    declaring=value.getDeclaringClass();isStatic=Modifier.isStatic(value.getModifiers());
                }else if(candidate instanceof Constructor<?>){Constructor<?> value=(Constructor<?>)candidate;
                    name="<init>";signature=signature(value.getParameterTypes(),void.class);
                    declaring=value.getDeclaringClass();isStatic=false;
                }else throw new IllegalStateException("FRAMEWORK_REFLECTED_KIND");
                if(declaring!=owners[owner])throw new IllegalStateException("FRAMEWORK_METHOD_OWNER");
                for(int slot=0;slot<methods.length;slot++)if(METHOD_OWNERS[slot]==owner&&METHOD_NAMES[slot].equals(name)&&METHOD_TYPES[slot].equals(signature)){
                    if(methods[slot]!=null||isStatic!=METHOD_STATIC[slot])throw new IllegalStateException("FRAMEWORK_METHOD_AMBIGUOUS:"+name);
                    methods[slot]=candidate;
                }
            }
            if(countNative(owner)!=size)throw new IllegalStateException("FIXED_MEMBER_REGISTRY_INCOMPLETE:"+owner+":"+statusNative());
            declarationCache[owner]=declarations;
        }
        for(int i=0;i<methods.length;i++)if(methods[i]==null)throw new IllegalStateException("FRAMEWORK_METHOD_MISSING:"+METHOD_NAMES[i]);
        return new Members(owners,fields,methods,reflected);
    }
    private static String signature(Class<?>[] parameters,Class<?> result){StringBuilder value=new StringBuilder("(");
        for(Class<?> parameter:parameters)value.append(type(parameter));return value.append(')').append(type(result)).toString();}
    /** The six fixed manager owners share the SAME native registry as Application.
     * A declared-member inventory does not authorize these methods as Hook targets. */
    static synchronized Object[] managerMembers(int family){
        if(!prepared||family<0||family>=11)throw new IllegalStateException("FIXED_MANAGER_INVENTORY_ORDER");
        return fixedMembers(new int[]{7,8,9,10,11,12,46,36,38,45,50}[family]);
    }
    static synchronized Object[] fixedMembers(int owner){
        if(!prepared||owner<0||owner>=declarationCache.length)throw new IllegalStateException("FIXED_MEMBER_ORDER");
        if(declarationCache[owner]!=null)return declarationCache[owner].clone();
        int count=countNative(owner);
        if(count<1||count>1024)throw new IllegalStateException("FIXED_MANAGER_MEMBER_BUDGET:"+owner+":"+statusNative());
        Object[] result=new Object[count];
        for(int i=0;i<count;i++)result[i]=methodNative(owner,i);
        // A null ordinal is either a legitimately skipped record or an owner lookup the native
        // registry refused mid-enumeration. Re-resolve the owner before caching so a partial
        // declaration set is never cached as if those members were absent.
        if(countNative(owner)!=count)throw new IllegalStateException("FIXED_MEMBER_REGISTRY_INCOMPLETE:"+owner+":"+statusNative());
        declarationCache[owner]=result;return result.clone();
    }
    static synchronized Field fixedField(int slot){
        if(!prepared||slot<0||slot>=42)throw new IllegalStateException("FIXED_FIELD_ORDER");
        return fieldNative(slot);
    }
    /** ActivityThread.getApplication() reads mInitialApplication; it must show the same logical Application as currentApplication(). */
    public static Method activityThreadApplicationMethod()throws Exception{
        return FixedFrameworkMembers.method(1,"getApplication","()Landroid/app/Application;",false);
    }
    public static Method processNameMethod()throws Exception{
        return FixedFrameworkMembers.method(0,"getProcessName","()Ljava/lang/String;",true);
    }
    /** One typed Service group in the same registry; no alternate name resolver. */
    static synchronized Object[] serviceTokenMembers() throws Exception {
        if(!prepared)throw new IllegalStateException("FIXED_SERVICE_MEMBER_ORDER");
        Class<?> service=Class.forName("android.app.Service",false,null),manager=Class.forName("android.app.IActivityManager",false,null);
        Field managerField=fieldNative(12),tokenField=fieldNative(13);
        if(managerField==null||tokenField==null||managerField.getDeclaringClass()!=service||tokenField.getDeclaringClass()!=service
            ||!managerField.getName().equals("mActivityManager")||managerField.getType()!=manager
            ||!tokenField.getName().equals("mToken")||tokenField.getType()!=android.os.IBinder.class
            ||Modifier.isStatic(managerField.getModifiers())||Modifier.isStatic(tokenField.getModifiers()))
            throw new IllegalStateException("FIXED_SERVICE_FIELD_IDENTITY");
        Method stop=null;
        for(Object candidate:fixedMembers(14)) {
            if(!(candidate instanceof Method))continue;Method method=(Method)candidate;
            if(method.getDeclaringClass()!=manager)throw new IllegalStateException("FIXED_SERVICE_METHOD_OWNER");
            if(method.getName().equals("stopServiceToken")&&signature(method.getParameterTypes(),method.getReturnType())
                .equals("(Landroid/content/ComponentName;Landroid/os/IBinder;I)Z")) {
                if(stop!=null||Modifier.isStatic(method.getModifiers()))throw new IllegalStateException("FIXED_SERVICE_METHOD_IDENTITY");stop=method;
            }
        }
        if(stop==null)throw new IllegalStateException("FIXED_SERVICE_STOP_MEMBER_MISSING");
        return new Object[]{service,manager,managerField,tokenField,stop};
    }
    /** Boot entries require an explicit fixed adapter member. Unknown boot and
     * caller-sensitive/intrinsic semantics are not a generic-frame capability. */
    static void requireManagedHookTarget(Executable target){
        Class<?> owner=target.getDeclaringClass();
        try{if(Class.forName(owner.getName(),false,null)!=owner)return;}
        catch(ClassNotFoundException nonBoot){return;}
        String descriptor=signature(target.getParameterTypes(),target instanceof Method?((Method)target).getReturnType():void.class);
        String name=target instanceof Constructor<?>?"<init>":target.getName();
        for(int i=0;i<METHOD_NAMES.length;i++)if(owner.getName().equals(CLASSES[METHOD_OWNERS[i]])
            &&name.equals(METHOD_NAMES[i])&&descriptor.equals(METHOD_TYPES[i])
            &&Modifier.isStatic(target.getModifiers())==METHOD_STATIC[i])return;
        if(target instanceof Method&&FixedManagerInventory.permitsHook((Method)target))return;
        if(target instanceof Method&&FixedComponentHooks.permitsHook((Method)target))return;
        if(FixedRuntimeHooks.permitsHook(target))return;
        if(JobFrameworkAccess.permitsHook(target))return;
        if(PendingIntentFrameworkAccess.permitsHook(target))return;
        if(owner==android.app.Application.class&&name.equals("getProcessName")&&descriptor.equals("()Ljava/lang/String;")
            &&Modifier.isStatic(target.getModifiers()))return;
        if(owner.getName().equals("android.app.ActivityThread")&&name.equals("getApplication")&&descriptor.equals("()Landroid/app/Application;")
            &&!Modifier.isStatic(target.getModifiers()))return;
        throw new IllegalArgumentException("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED:"+owner.getName()+"."+target.getName());
    }
    private static String type(Class<?> value){
        if(value.isArray())return value.getName().replace('.','/');
        if(!value.isPrimitive())return "L"+value.getName().replace('.','/')+";";
        if(value==void.class)return "V";if(value==boolean.class)return "Z";if(value==byte.class)return "B";
        if(value==char.class)return "C";if(value==short.class)return "S";if(value==int.class)return "I";
        if(value==long.class)return "J";if(value==float.class)return "F";if(value==double.class)return "D";
        throw new IllegalStateException("FRAMEWORK_PRIMITIVE_TYPE");
    }
    public static final class Members {
        public final Class<?>[] owners;
        public final Field[] fields;
        public final Object[] methods;
        public final int reflectedMethods;
        private Members(Class<?>[] owners,Field[] fields,Object[] methods,int reflected){
            this.owners=owners;this.fields=fields;this.methods=methods;reflectedMethods=reflected;}
    }
}
