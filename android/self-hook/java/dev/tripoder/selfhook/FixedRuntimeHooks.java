package dev.tripoder.selfhook;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Remaining Runtime target groups from pinned boot declarations. Inspection
 * alone grants no admission; only these exact concrete member identities do. */
public final class FixedRuntimeHooks {
    public enum Group { ACTIVITY_CREATION,ACTIVITY_DELIVERY,ACTIVITY_ROUTING,PACKAGES,TRAFFIC,WIFI_CHANNEL,UI,BIOMETRICS,FAILURES }
    private static final int[] OWNERS={27,28,29,30,31,33,34,35,37,41,42,44,47,60,61};
    private static final String[] HASHES={"e267e4418772a5e8b79faba68f1b75dad2e4b9ae15943538de2e16d41f9d77bf","2e854397afd2aee6a81e372ed4398d1a5e6413c9ac2bc4a6180d5fa192ea781f","6a96bd5a88b4f92bfda9955f8d6e57ab0f4d8328052dfc3241d384eee2c3bd17","4356ca2e7fc8608b7fdafc2d9c0a45eca665cc98aced63aff8cf44f7f9d03d64","66a5dfae818de4bcc07c3edba87f70cec94f926a6dd2def79b9e55d1b902e629","c6fb2511844b76ee5e36518335fdbb82cb50827a6fdb0e2204849e87a1090ace","2af2c8f27b80686a4981361109712276dbcb927ed9a39223ca8b69eb59b76f49","e376db2bea9747d796291a53241f099a58b4922d73b1707fb616af28ef4840d6","161485fa2d9c2fea14649387f0974f1d90279fa2ac5c718df1c8145a0300e598","e814e31f8e15f2de0118e54669ffc1362657b571b574c310926b5e4459b7c896","e77c6f944a3a7563e907c5674dbe292475481c2db90032fd403d92bbad190d22","d6afeda127517070513b8357a49689470c1b3cbb4de9a126ee0b7553aece6874","f475b4485ad8aeb38f19b10276944f878a0ac95ddc3c52f9ffa4141015b5084b","10696921fae0ba8311a8113caf07c3caccebbaa967a455494e4602e31a606f25","679eb989f370e10eac0e0204c99135b6aab839db47253d108f412b1cc89013c0"};
    private static final EnumMap<Group,List<Executable>> groups=new EnumMap<>(Group.class);
    private static final int[] EXPECTED={5,11,10,514,62,1,2,2,4};
    private static Set<Executable> admitted=Set.of();private static boolean attempted,prepared;
    private FixedRuntimeHooks(){}
    public static synchronized void prepare()throws Exception{
        if(attempted)throw new IllegalStateException("FIXED_RUNTIME_HOOKS_ALREADY_ATTEMPTED");attempted=true;
        EnumMap<Group,List<Executable>> selected=new EnumMap<>(Group.class);for(Group group:Group.values())selected.put(group,new ArrayList<>());
        for(int index=0;index<OWNERS.length;index++){
            int owner=OWNERS[index];Class<?> type=FixedFrameworkMembers.owner(owner);List<String> declarations=new ArrayList<>();List<Executable> members=new ArrayList<>();
            for(Object candidate:FrameworkAccess.fixedMembers(owner))if(candidate instanceof Executable){Executable member=(Executable)candidate;
                if(member.getDeclaringClass()!=type)throw new IllegalStateException("FIXED_RUNTIME_MEMBER_OWNER");
                declarations.add(key(member)+":"+Integer.toHexString(member.getModifiers()));members.add(member);
            }
            Collections.sort(declarations);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((String.join("\n",declarations)+"\n").getBytes(StandardCharsets.UTF_8)));
            if(!digest.equals(HASHES[index]))throw new IllegalStateException("FIXED_RUNTIME_DECLARATIONS_CHANGED:"+type.getName());
            for(Executable member:members){Group group=select(owner,member);if(group!=null){member.setAccessible(true);selected.get(group).add(member);}}
        }
        selected.get(Group.ACTIVITY_CREATION).add(FixedFrameworkMembers.method(1,"performLaunchActivity","(Landroid/app/ActivityThread$ActivityClientRecord;Landroid/content/Intent;)Landroid/app/Activity;",false));
        selected.get(Group.PACKAGES).add(FixedFrameworkMembers.method(3,"getPackageManager","()Landroid/content/pm/PackageManager;",false));
        Set<Executable> all=new HashSet<>();
        for(Group group:Group.values()){
            List<Executable> members=selected.get(group);members.sort(Comparator.comparing(m->m.getDeclaringClass().getName()+"."+key(m)));
            if(members.size()!=EXPECTED[group.ordinal()])throw new IllegalStateException("FIXED_RUNTIME_GROUP_INCOMPLETE:"+group+":"+members.size());
            for(Executable member:members)if(Modifier.isAbstract(member.getModifiers())||!all.add(member))throw new IllegalStateException("FIXED_RUNTIME_TARGET_DUPLICATE_OR_ABSTRACT");
            groups.put(group,List.copyOf(members));
        }
        admitted=Set.copyOf(all);prepared=true;
    }
    public static synchronized List<Executable> methods(Group group){if(!prepared)throw new IllegalStateException("FIXED_RUNTIME_HOOKS_NOT_PREPARED");return groups.get(Objects.requireNonNull(group));}
    static synchronized boolean permitsHook(Executable member){return prepared&&admitted.contains(member);}
    private static String key(Executable member){return (member instanceof Constructor<?>?"<init>":member.getName())+FixedFrameworkMembers.signature(member.getParameterTypes(),member instanceof Method?((Method)member).getReturnType():void.class);}
    private static Group select(int owner,Executable member){
        String key=key(member),name=member.getName();int flags=member.getModifiers();
        if(member instanceof Constructor<?>){
            if(owner==29&&key.equals("<init>()V"))return Group.ACTIVITY_CREATION;
            if(owner==61&&key.equals("<init>(Landroid/content/Context;Landroid/hardware/biometrics/PromptInfo;Landroid/hardware/biometrics/BiometricPrompt$ButtonInfo;Landroid/hardware/biometrics/BiometricPrompt$ButtonInfo;[Landroid/hardware/biometrics/BiometricPrompt$ButtonInfo;Landroid/hardware/biometrics/IAuthService;)V"))return Group.BIOMETRICS;
            return null;
        }
        Method method=(Method)member;
        switch(owner){
            case 27:
                if(key.equals("attach(Landroid/content/Context;Landroid/app/ActivityThread;Landroid/app/Instrumentation;Landroid/os/IBinder;ILandroid/app/Application;Landroid/content/Intent;Landroid/content/pm/ActivityInfo;Ljava/lang/CharSequence;Landroid/app/Activity;Ljava/lang/String;Landroid/app/Activity$NonConfigurationInstances;Landroid/content/res/Configuration;Ljava/lang/String;Lcom/android/internal/app/IVoiceInteractor;Landroid/view/Window;Landroid/view/ViewRootImpl$ActivityConfigCallback;Landroid/os/IBinder;Landroid/os/IBinder;Landroid/os/IBinder;)V"))return Group.ACTIVITY_CREATION;
                if(Set.of("performNewIntent(Landroid/content/Intent;)V","performNewIntent(Landroid/content/Intent;Landroid/app/ComponentCaller;)V","getCaller()Landroid/app/ComponentCaller;","getInitialCaller()Landroid/app/ComponentCaller;","getCurrentCaller()Landroid/app/ComponentCaller;","getReferrer()Landroid/net/Uri;","getCallingActivity()Landroid/content/ComponentName;","getCallingPackage()Ljava/lang/String;","setTaskDescription(Landroid/app/ActivityManager$TaskDescription;)V").contains(key))return Group.ACTIVITY_DELIVERY;
                if(key.equals("finish()V")||key.equals("dispatchActivityResult(Ljava/lang/String;IILandroid/content/Intent;Landroid/app/ComponentCaller;Ljava/lang/String;)V"))return Group.ACTIVITY_ROUTING;break;
            case 28:if(key.equals("getPackage()Ljava/lang/String;")||key.equals("checkContentUriPermission(Landroid/net/Uri;I)I"))return Group.ACTIVITY_DELIVERY;break;
            case 29:if(key.equals("newActivity(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Intent;)Landroid/app/Activity;"))return Group.ACTIVITY_CREATION;
                if(name.startsWith("execStartActivit")||key.equals("checkStartActivityResult(ILjava/lang/Object;)V"))return Group.ACTIVITY_ROUTING;break;
            case 30:if(key.equals("getThemeResource()I"))return Group.ACTIVITY_CREATION;break;
            case 31:if(key.equals("playSoundEffect(I)V"))return Group.UI;break;
            case 33:if(key.equals("dispatchUncaughtException(Ljava/lang/Throwable;)V"))return Group.FAILURES;break;
            case 34:if(key.equals("uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V"))return Group.FAILURES;break;
            case 35:if(key.equals("exit(I)V")||key.equals("halt(I)V"))return Group.FAILURES;break;
            case 37:if(Modifier.isPublic(flags)&&Modifier.isStatic(flags))return Group.TRAFFIC;break;
            case 41:case 42:if(!Modifier.isStatic(flags)&&!Modifier.isAbstract(flags))return Group.PACKAGES;break;
            case 44:if(Modifier.isStatic(flags)&&method.getParameterCount()>0&&method.getParameterTypes()[0]==String.class)return Group.PACKAGES;break;
            case 47:if(key.equals("close()V"))return Group.WIFI_CHANNEL;break;
            case 60:if(key.equals("autofillClientGetComponentName()Landroid/content/ComponentName;"))return Group.UI;break;
            case 61:if(key.equals("authenticateInternal(JLandroid/os/CancellationSignal;Ljava/util/concurrent/Executor;Landroid/hardware/biometrics/BiometricPrompt$AuthenticationCallback;I)J"))return Group.BIOMETRICS;break;
            default:throw new IllegalStateException("FIXED_RUNTIME_OWNER_UNHANDLED");
        }
        return null;
    }
}
