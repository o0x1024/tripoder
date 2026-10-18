package dev.tripoder.selfhook;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.IInterface;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pinned declarations in the single framework registry. No alternate reflection resolver. */
public final class PendingIntentFrameworkAccess {
    private static PendingIntentFrameworkAccess current;
    private static boolean attempted;
    private static final String[] HASHES={
        "efe57ff86a10060d699fe18d39998c478665ecb9e3d778a02ae08530f284e636",
        "a9e1e63c0e9b980464df9c07261b8e18fc4ad65295b4cbbb2d6ab48c57f2bdcc",
        "ff7fd3b9b71401fd7af3d766ad62297241e1b97f02321d292a0747d6361b075d"};
    private final Set<Method> hooks=new LinkedHashSet<>();
    private final Method tag,senderTarget,senderOptions;
    private final Constructor<?> pending,alarm;
    private PendingIntentFrameworkAccess()throws Exception {
        for(int owner=55;owner<58;owner++){
            Class<?> type=FixedFrameworkMembers.owner(owner);List<String> declarations=new ArrayList<>();
            for(Object raw:FrameworkAccess.fixedMembers(owner))if(raw instanceof Executable){
                Executable value=(Executable)raw;
                if(value.getDeclaringClass()!=type)throw new IllegalStateException("PENDING_MEMBER_OWNER");
                String name=value instanceof Constructor<?>?"<init>":value.getName();
                String descriptor=FixedFrameworkMembers.signature(value.getParameterTypes(),value instanceof Method?((Method)value).getReturnType():void.class);
                declarations.add(name+descriptor+":"+Integer.toHexString(value.getModifiers()));
                if(value instanceof Method&&selected(owner,(Method)value)){value.setAccessible(true);hooks.add((Method)value);}
            }
            Collections.sort(declarations);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((String.join("\n",declarations)+"\n").getBytes(StandardCharsets.UTF_8)));
            if(!HASHES[owner-55].equals(digest))throw new IllegalStateException("PENDING_DECLARATIONS_CHANGED:"+type.getName()+":"+digest);
        }
        tag=FixedFrameworkMembers.method(55,"getTag","(Ljava/lang/String;)Ljava/lang/String;",false);
        senderTarget=FixedFrameworkMembers.method(56,"getTarget","()Landroid/content/IIntentSender;",false);
        senderOptions=FixedFrameworkMembers.method(56,"getSendIntentDefaultOptions","()Landroid/os/Bundle;",false);
        pending=FixedFrameworkMembers.constructor(55,"(Landroid/content/IIntentSender;)V");
        alarm=FixedFrameworkMembers.constructor(57,"(Landroid/app/IAlarmManager;Landroid/content/Context;)V");
    }
    private static boolean selected(int owner,Method method){
        String name=method.getName();
        if(owner==57)return !Modifier.isStatic(method.getModifiers())&&(Modifier.isPublic(method.getModifiers())||name.equals("setImpl"));
        if(owner==55){
            if(Modifier.isStatic(method.getModifiers()))return name.startsWith("getActivit")||Set.of("getBroadcast","getBroadcastAsUser","getService","getForegroundService").contains(name);
            return Set.of("cancel","sendAndReturnResult","sendAsync","getCreatorPackage","getTargetPackage","intentFilterEquals","queryIntentComponents").contains(name);
        }
        // Pinned DEX proves both public Handler overloads delegate here. The old
        // hidden Handler+options overload retains its platform removal check.
        return Set.of("getCreatorPackage","getTargetPackage").contains(name)||name.equals("sendIntent")&&method.getParameterCount()==7&&method.getParameterTypes()[3]==String.class;
    }
    public static synchronized void prepare()throws Exception{
        if(attempted)throw new IllegalStateException("PENDING_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;current=new PendingIntentFrameworkAccess();
    }
    private static PendingIntentFrameworkAccess require(){if(current==null)throw new IllegalStateException("PENDING_FRAMEWORK_NOT_READY");return current;}
    static synchronized boolean permitsHook(Executable target){return current!=null&&current.hooks.contains(target);}
    public static Set<Method> hooks(){return Set.copyOf(require().hooks);}
    public static String tokenTag(PendingIntent token){return (String)FixedFrameworkMembers.call(require().tag,Objects.requireNonNull(token),"tripoder-owner:");}
    public static PendingIntent pending(IntentSender sender){return (PendingIntent)FixedFrameworkMembers.create(require().pending,FixedFrameworkMembers.call(require().senderTarget,Objects.requireNonNull(sender)));}
    public static android.os.Bundle senderOptions(IntentSender sender){return (android.os.Bundle)FixedFrameworkMembers.call(require().senderOptions,Objects.requireNonNull(sender));}
    public static AlarmManager alarm(Context context){return (AlarmManager)FixedFrameworkMembers.create(require().alarm,null,Objects.requireNonNull(context));}
    private PendingIntentFrameworkAccess(boolean unused){throw new AssertionError();}
}
