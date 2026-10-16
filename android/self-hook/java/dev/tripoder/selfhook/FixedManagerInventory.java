package dev.tripoder.selfhook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Read-only fixed boot declarations; not a caller-coverage or installer receipt. */
public final class FixedManagerInventory {
    private static final String[] OWNERS={"android.app.ActivityManager","android.net.ConnectivityManager",
        "android.telephony.TelephonyManager","android.media.AudioManager","android.os.BatteryManager","android.os.PowerManager","android.net.wifi.p2p.WifiP2pManager","android.app.usage.NetworkStatsManager","android.app.NotificationManager","android.content.pm.ShortcutManager","android.app.JobSchedulerImpl"};
    private static boolean attempted;
    private static volatile List<Family> verified;
    private static final String[] EXPECTED={
        "5a21b530ff2fd8d9adf29d0d36830544f6dc1687b81d6e756e5368a8435df97c",
        "9b525ea50313e5588059f5b47a3febc1ae002192647cab07c00c8ff78d843b37",
        "f7858de9d8108f074a4704d6d910a5fa6131235a2280e9dd85caa2e5b42dadba",
        "75f57e4055f3f1887f6c7e215fe52fb29d2ab734902accb9fc8bb5524090fd96",
        "45b6e74e54dbc42c7790017cbecab6eb379ad054bf6399592e7313efe17ae80e",
        "28ce6d9bdec01b645c0518d40a2bce7f0c2c8b52e0a2d51bfe20c4ac5fa56dcc","c6de47443247423b38cf03e39683af1520c23977420af06e3d4c924a22570231","e24e976f1cbd0e8af664b8bd1cfabbcc0f7d0d7dd5db625b826b8d534654c0e8","bcd106740880155689ff59bd8600d928e9307a4cb8e46c1cedde3a034598896a","f90336bfc3697a7dc7247e4d7fd66c546cbccfb296acf034554287d29a2b5913",
        // JobSchedulerImpl (index 10): concrete getSystemService impl (abstract JobScheduler has no hookable
        // bodies). Device-pinned digest from the H4 inventory run on this Android 17/API37 platform.
        "4e5c0e344d62d9e7de2a10354546d287bd9d5960f3404a4a11d9f94adb60617c"};
    private FixedManagerInventory() {}
    public static final class Family {
        public final Class<?> owner;
        public final List<Method> instanceMethods;
        private final java.util.Set<Method> admitted;
        public final List<String> declarations;
        public final int constructors,staticMethods,abstractMethods,nativeMethods,skipped;
        public final String sha256;
        private Family(Class<?> owner,List<Method> methods,List<String> declarations,int constructors,int statics,int abstracts,int natives,int skipped)throws Exception {
            this.owner=owner;instanceMethods=List.copyOf(methods);admitted=java.util.Set.copyOf(methods);this.declarations=List.copyOf(declarations);
            this.constructors=constructors;staticMethods=statics;abstractMethods=abstracts;nativeMethods=natives;this.skipped=skipped;
            sha256=digest(String.join("\n",declarations)+"\n");
        }
    }
    public static synchronized List<Family> inspect() throws Exception {
        if(attempted)throw new IllegalStateException("FIXED_MANAGER_INVENTORY_ALREADY_ATTEMPTED");
        attempted=true;List<Family> result=new ArrayList<>();
        for(int family=0;family<OWNERS.length;family++) {
            Class<?> owner=Class.forName(OWNERS[family],false,null);
            List<Method> instance=new ArrayList<>();List<String> declarations=new ArrayList<>();HashSet<String> unique=new HashSet<>();
            int constructors=0,statics=0,abstracts=0,natives=0,skipped=0;
            for(Object candidate:FrameworkAccess.managerMembers(family)) {
                if(candidate==null){skipped++;continue;}
                Class<?> declaring;String descriptor;int flags;
                if(candidate instanceof Constructor<?>) {
                    Constructor<?> value=(Constructor<?>)candidate;declaring=value.getDeclaringClass();flags=value.getModifiers();
                    descriptor="<init>"+signature(value.getParameterTypes(),void.class);constructors++;
                } else if(candidate instanceof Method) {
                    Method value=(Method)candidate;declaring=value.getDeclaringClass();flags=value.getModifiers();
                    descriptor=value.getName()+signature(value.getParameterTypes(),value.getReturnType());
                    if(Modifier.isStatic(flags))statics++;
                    else {instance.add(value);if(Modifier.isAbstract(flags))abstracts++;if(Modifier.isNative(flags))natives++;}
                } else throw new IllegalStateException("FIXED_MANAGER_MEMBER_KIND");
                if(declaring!=owner||!unique.add(descriptor))throw new IllegalStateException("FIXED_MANAGER_MEMBER_IDENTITY:"+descriptor);
                declarations.add(descriptor+" "+flags);
            }
            if(instance.isEmpty())throw new IllegalStateException("FIXED_MANAGER_EMPTY_INVENTORY");
            instance.sort((a,b)->descriptor(a).compareTo(descriptor(b)));Collections.sort(declarations);
            Family value=new Family(owner,instance,declarations,constructors,statics,abstracts,natives,skipped);
            // The Native registry excludes class initializers and copied/runtime
            // records. Pin the exact declared Methods/Constructors, not the
            // count of excluded records which cannot be Hook targets.
            if(!EXPECTED[family].equals(value.sha256)||abstracts!=0||natives!=0)
                throw new IllegalStateException("FIXED_MANAGER_DECLARATIONS_CHANGED:"+owner.getName());
            result.add(value);
        }
        verified=List.copyOf(result);return verified;
    }
    /** Admission only from the complete, pinned registry inventory. No caller
     * can append arbitrary boot methods to this set. */
    static boolean permitsHook(Method target){
        List<Family> values=verified;if(values==null)return false;
        for(Family family:values)if(family.owner==target.getDeclaringClass())return family.admitted.contains(target);
        return false;
    }
    public static List<Method> methods(int family){
        List<Family> values=verified;
        if(values==null||family<0||family>=values.size())throw new IllegalStateException("FIXED_MANAGER_VERIFIED_INVENTORY_REQUIRED");
        return values.get(family).instanceMethods;
    }
    public static String descriptor(Method method){return method.getName()+signature(method.getParameterTypes(),method.getReturnType());}
    private static String signature(Class<?>[] parameters,Class<?> result){
        StringBuilder out=new StringBuilder("(");for(Class<?> parameter:parameters)out.append(type(parameter));return out.append(')').append(type(result)).toString();
    }
    private static String type(Class<?> type){
        if(type.isArray())return type.getName().replace('.','/');if(!type.isPrimitive())return "L"+type.getName().replace('.','/')+";";
        if(type==void.class)return "V";if(type==boolean.class)return "Z";if(type==byte.class)return "B";if(type==char.class)return "C";
        if(type==short.class)return "S";if(type==int.class)return "I";if(type==long.class)return "J";if(type==float.class)return "F";if(type==double.class)return "D";
        throw new IllegalArgumentException("FIXED_MANAGER_TYPE");
    }
    private static String digest(String text)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
