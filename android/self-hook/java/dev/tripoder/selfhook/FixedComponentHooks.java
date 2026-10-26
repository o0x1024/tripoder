package dev.tripoder.selfhook;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Pinned component declarations over the existing Native registry. Only the
 * exact selected concrete Method identities gain boot Hook admission. */
public final class FixedComponentHooks {
    public enum Group { SERVICES, PROVIDERS, RECEIVERS, WIDGETS }
    private static final int[] OWNERS={13,15,18,24,25,26,59,79,80,81,82};
    private static final String[] HASHES={
        "e8bef0347523c2ad3b2f15e2e12fa95f512c1aa32ca391d7fd0cc227738f5f5f",
        "5dfe6ca617a2233ae29f66907e7fa42a1788b089c24cd00eeb59d8764b342c2b",
        "5f48578efc4f40a7184b258bb90f882500fe5b285ee1434f624020fac4d3811b",
        "03ada6cc2c4d98ca398e1039f3f865afb67760751f66288dd678467c99923f82",
        "ab550e69d942480ddab78427f5ef8f122db67f77f6d16cafa0c06b54fdbd373c",
        "71ffa40a13f26b913212a772d88ce512a0692e08728839dcf6e50b4f5ffcb000",
        // AppWidgetManager (owner 59): device-pinned declaration digest from the API37 inventory run.
        "8e138cce8b8633b73de9bbb9559e0f34be424f05bd651e62db6b0df9f9fe4e89",
        "770175655176e1a088715091d7a1d04bdad6cb161afbb5241931ed5fab48895e",
        "ee61a20b311b5b4b07080f08699909d8aa6224b313b604e6bae80b177b845f08",
        "9b8b201b383e62858df93ffd4e7812e88d1e4c91665aae3924c9cddb415a388f",
        "7ed83a77351759f90dab7453681e2c71a12c7ed5c84a56ee93745e8cb2885f83"};
    private static final EnumMap<Group,List<Method>> groups=new EnumMap<>(Group.class);
    private static Set<Method> admitted=Set.of();
    private static boolean attempted,prepared;
    private FixedComponentHooks(){}
    public static synchronized void prepare()throws Exception{
        if(attempted)throw new IllegalStateException("FIXED_COMPONENT_PREPARE_ALREADY_ATTEMPTED");attempted=true;
        EnumMap<Group,List<Method>> selected=new EnumMap<>(Group.class);for(Group group:Group.values())selected.put(group,new ArrayList<>());
        for(int i=0;i<OWNERS.length;i++){
            int owner=OWNERS[i];Class<?> type=FixedFrameworkMembers.owner(owner);List<String> inventory=new ArrayList<>();List<Method> methods=new ArrayList<>();
            for(Object candidate:FrameworkAccess.fixedMembers(owner))if(candidate instanceof Method){Method method=(Method)candidate;
                if(method.getDeclaringClass()!=type)throw new IllegalStateException("FIXED_COMPONENT_DECLARING_CLASS");
                inventory.add(key(method)+":"+Integer.toHexString(method.getModifiers()));methods.add(method);
            }
            Collections.sort(inventory);String text=String.join("\n",inventory)+"\n";
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            if(!HASHES[i].equals(hash)){
                android.util.Log.i("TripoderInventory","owner="+type.getName()+" declarations="+inventory.size()+" sha256="+hash);
                throw new IllegalStateException("FIXED_COMPONENT_DECLARATIONS_CHANGED:"+type.getName());
            }
            for(Method method:methods){Group group=select(owner,method);if(group==null)continue;
                if(Modifier.isStatic(method.getModifiers())||Modifier.isAbstract(method.getModifiers())||Modifier.isNative(method.getModifiers()))
                    throw new IllegalStateException("FIXED_COMPONENT_METHOD_KIND:"+method);
                method.setAccessible(true);selected.get(group).add(method);
            }
        }
        int[] expected={18,28,6,28};Set<Method> all=new HashSet<>();
        for(Group group:Group.values()){
            List<Method> methods=selected.get(group);methods.sort(Comparator.comparing(m->m.getDeclaringClass().getName()+"."+key(m)));
            if(methods.size()!=expected[group.ordinal()]||!all.addAll(methods))throw new IllegalStateException("FIXED_COMPONENT_SET_INCOMPLETE:"+group+":"+methods.size());
            groups.put(group,List.copyOf(methods));
        }
        if(all.size()!=52+selected.get(Group.WIDGETS).size())throw new IllegalStateException("FIXED_COMPONENT_IDENTITY_DUPLICATION:"+all.size());
        SystemReceiverAccess.prepare(selected.get(Group.RECEIVERS));admitted=Set.copyOf(all);prepared=true;
    }
    public static synchronized List<Method> methods(Group group){
        if(!prepared)throw new IllegalStateException("FIXED_COMPONENT_NOT_PREPARED");return groups.get(Objects.requireNonNull(group));
    }
    static synchronized boolean permitsHook(Method method){return prepared&&admitted.contains(method);}
    private static String key(Method method){return method.getName()+FixedFrameworkMembers.signature(method.getParameterTypes(),method.getReturnType());}
    private static Group select(int owner,Method method){
        String name=method.getName(),signature=key(method);
        if(owner==13&&Set.of("onCreate","onDestroy","onRebind","onUnbind","onStartCommand","attach","stopSelf","stopSelfResult","startForeground","stopForeground","getForegroundServiceType").contains(name))return Group.SERVICES;
        if(owner==79&&Set.of("onCreate()V","onDestroy()V","onStartCommand(Landroid/content/Intent;II)I","onBind(Landroid/content/Intent;)Landroid/os/IBinder;").contains(signature))return Group.SERVICES;
        if(owner==15&&Set.of("goAsync","peekService").contains(name)||owner==25&&signature.equals("finish()V"))return Group.RECEIVERS;
        if(owner>=80&&owner<=82&&signature.equals("onReceive(Landroid/content/Context;Landroid/content/Intent;)V"))return Group.RECEIVERS;
        if(owner==18&&(signature.equals("attachInfo(Landroid/content/Context;Landroid/content/pm/ProviderInfo;Z)V")
            ||Set.of("getCallingPackage","getCallingPackageUnchecked","getCallingAttributionTag","getCallingAttributionSource","clearCallingIdentity","restoreCallingIdentity").contains(name)))return Group.PROVIDERS;
        if(owner==24&&(signature.equals("acquireExistingProvider(Landroid/content/Context;Ljava/lang/String;)Landroid/content/IContentProvider;")
            ||Set.of("registerContentObserver","unregisterContentObserver","notifyChange","takePersistableUriPermission","releasePersistableUriPermission","getPersistedUriPermissions","getOutgoingPersistedUriPermissions").contains(name)))return Group.PROVIDERS;
        if(owner==26&&Set.of("acquireProvider","acquireUnstableProvider","releaseProvider","releaseUnstableProvider").contains(name))return Group.PROVIDERS;
        // A widget provider's platform operations are scoped by the ComponentName the platform resolves
        // against its own install table. Guest component names are not installed under the host UID, so
        // every widget operation the guest can reach is answered from the virtual device's widget state.
        if(owner==59&&WIDGET_METHODS.contains(name))return Group.WIDGETS;
        return null;
    }
    private static final Set<String> WIDGET_METHODS=Set.of("getAppWidgetIds","getAppWidgetInfo","getAppWidgetOptions",
        "getInstalledProviders","getInstalledProvidersForPackage","getInstalledProvidersForProfile",
        "bindAppWidgetIdIfAllowed","updateAppWidget","partiallyUpdateAppWidget","notifyAppWidgetViewDataChanged",
        "requestPinAppWidget","isRequestPinAppWidgetSupported","getWidgetPreview","setWidgetPreview","removeWidgetPreview",
        "updateAppWidgetOptions","updateAppWidgetProviderInfo","queryAppWidgetEvents");
}
