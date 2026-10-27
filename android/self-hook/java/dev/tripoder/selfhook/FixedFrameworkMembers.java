package dev.tripoder.selfhook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;

/** Exact typed groups over FrameworkAccess; never ordinary hidden-member lookup. */
final class FixedFrameworkMembers {
    private static final String[] NAMES={"android.app.Application","android.app.ActivityThread","android.app.LoadedApk",
        "android.app.ContextImpl","android.content.pm.ApplicationInfo","android.app.QueuedWork","android.content.ContextWrapper",
        "android.app.ActivityManager","android.net.ConnectivityManager","android.telephony.TelephonyManager","android.media.AudioManager",
        "android.os.BatteryManager","android.os.PowerManager","android.app.Service","android.app.IActivityManager",
        "android.content.BroadcastReceiver","android.content.pm.IPackageManager","android.content.Context$BindServiceFlags",
        "android.content.ContentProvider","android.content.ContentProviderOperation","android.content.ContentProviderOperation$BackReference",
        "android.util.SparseArray","android.net.ConnectivityManager$NetworkCallback","android.app.ApplicationStartInfo",
        "android.content.ContentResolver","android.content.BroadcastReceiver$PendingResult","android.content.ContentResolver$1",
        "android.app.Activity",
        "android.app.ComponentCaller",
        "android.app.Instrumentation",
        "android.content.pm.ActivityInfo",
        "android.view.ViewRootImpl",
        "android.view.ContextThemeWrapper",
        "java.lang.Thread",
        "java.lang.ThreadGroup",
        "java.lang.Runtime",
        "android.app.usage.NetworkStatsManager",
        "android.net.TrafficStats",
        "android.app.NotificationManager",
        "android.app.NotificationChannel",
        "android.app.NotificationChannelGroup",
        "android.app.ApplicationPackageManager",
        "android.content.pm.PackageManager",
        "android.content.pm.PackageItemInfo",
        "android.os.ServiceManager",
        "android.content.pm.ShortcutManager",
        "android.net.wifi.p2p.WifiP2pManager",
        "android.net.wifi.p2p.WifiP2pManager$Channel",
        "android.app.ActivityThread$ActivityClientRecord","android.os.UserHandle",
        "android.app.JobSchedulerImpl","android.app.job.JobInfo","android.app.job.JobParameters","android.app.job.JobService","android.app.job.IJobCallback$Stub$Proxy",
        "android.app.PendingIntent","android.content.IntentSender","android.app.AlarmManager","android.app.ApplicationExitInfo",
        "android.appwidget.AppWidgetManager","android.view.autofill.AutofillClientController","android.hardware.biometrics.BiometricPrompt","android.content.pm.SharedLibraryInfo","android.content.res.Resources","android.os.UserManager","android.util.Xml","android.app.ActivityThread$AppBindData","android.service.notification.StatusBarNotification","com.android.internal.os.ClassLoaderFactory","android.content.res.AssetManager","android.content.res.ApkAssets","android.provider.Settings$System","android.provider.Settings$Secure","android.provider.Settings$Global","android.provider.Settings$Config","android.provider.Settings$NameValueCache","android.provider.Settings$ContentProviderHolder","android.app.ResourcesManager","android.app.ResourcesManager$SharedLibraryAssets","android.app.IntentService","android.net.http.internal.org.chromium.net.NetworkChangeNotifierAutoDetect","android.net.http.internal.org.chromium.net.ProxyBroadcastReceiver","android.net.http.internal.org.chromium.net.ProxyChangeListener$ProxyReceiver","android.app.servertransaction.LaunchActivityItem","android.app.role.RoleManager","dalvik.system.BaseDexClassLoader","android.location.LocationManager","android.os.Process","android.content.pm.ParceledListSlice","android.content.pm.BaseParceledListSlice"};
    static Class<?> owner(int index)throws Exception{return Class.forName(NAMES[index],false,null);}
    static Field field(int slot,int owner,String name,String type,boolean isStatic)throws Exception {
        Field value=FrameworkAccess.fixedField(slot);
        if(value==null||value.getDeclaringClass()!=owner(owner)||!value.getName().equals(name)||!descriptor(value.getType()).equals(type)
            ||Modifier.isStatic(value.getModifiers())!=isStatic)throw new IllegalStateException("PRODUCT_FIELD_IDENTITY:"+name);
        value.setAccessible(true);return value;
    }
    /** A registry slot whose member may be absent on a newer boot framework. Returns null
     * only when the resolved member is missing or its identity differs; never a generic lookup. */
    static Field optionalField(int slot,int owner,String name,String type,boolean isStatic)throws Exception {
        Field value=FrameworkAccess.fixedField(slot);
        if(value==null||value.getDeclaringClass()!=owner(owner)||!value.getName().equals(name)||!descriptor(value.getType()).equals(type)
            ||Modifier.isStatic(value.getModifiers())!=isStatic)return null;
        value.setAccessible(true);return value;
    }
    static Object member(int index,String name,String descriptor,boolean isStatic)throws Exception {
        Class<?> owner=owner(index);Object found=null;
        for(Object item:FrameworkAccess.fixedMembers(index)) {
            if(item==null)continue;String n,d;Class<?> declaring;boolean stat;
            if(item instanceof Method){Method m=(Method)item;n=m.getName();d=signature(m.getParameterTypes(),m.getReturnType());declaring=m.getDeclaringClass();stat=Modifier.isStatic(m.getModifiers());}
            else if(item instanceof Constructor<?>){Constructor<?> c=(Constructor<?>)item;n="<init>";d=signature(c.getParameterTypes(),void.class);declaring=c.getDeclaringClass();stat=false;}
            else throw new IllegalStateException("PRODUCT_MEMBER_KIND");
            if(declaring!=owner)throw new IllegalStateException("PRODUCT_MEMBER_OWNER");
            if(n.equals(name)&&d.equals(descriptor)) {
                if(found!=null||stat!=isStatic)throw new IllegalStateException("PRODUCT_MEMBER_AMBIGUOUS:"+name);found=item;
            }
        }
        if(found==null){StringBuilder nulls=new StringBuilder();Object[] all=FrameworkAccess.fixedMembers(index);for(int i=0;i<all.length;i++)if(all[i]==null)nulls.append(i).append(',');
            throw new IllegalStateException("PRODUCT_MEMBER_MISSING:"+NAMES[index]+"."+name+descriptor+":count="+all.length+":nullOrdinals="+nulls+":"+FrameworkAccess.statusNative());}
        ((java.lang.reflect.AccessibleObject)found).setAccessible(true);return found;
    }
    static Method method(int owner,String name,String descriptor,boolean stat)throws Exception{return (Method)member(owner,name,descriptor,stat);}
    static Constructor<?> constructor(int owner,String descriptor)throws Exception{return (Constructor<?>)member(owner,"<init>",descriptor,false);}
    static Object call(Method method,Object receiver,Object... arguments){
        try{return method.invoke(receiver,arguments);}catch(InvocationTargetException error){return rethrow(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException("PRODUCT_FIXED_CALL_FAILED:"+method,error);}
    }
    static Object create(Constructor<?> constructor,Object... arguments){
        try{return constructor.newInstance(arguments);}catch(InvocationTargetException error){return rethrow(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException("PRODUCT_FIXED_CONSTRUCTION_FAILED:"+constructor,error);}
    }
    static void set(Field field,Object receiver,Object value){try{field.set(receiver,value);}catch(IllegalAccessException error){throw new IllegalStateException("PRODUCT_FIXED_FIELD_FAILED",error);}}
    static Object get(Field field,Object receiver){try{return field.get(receiver);}catch(IllegalAccessException error){throw new IllegalStateException("PRODUCT_FIXED_FIELD_FAILED",error);}}
    @SuppressWarnings("unchecked") private static <T extends Throwable,R> R rethrow(Throwable error)throws T{throw (T)error;}
    static String signature(Class<?>[] parameters,Class<?> result){StringBuilder b=new StringBuilder("(");for(Class<?> p:parameters)b.append(descriptor(p));return b.append(')').append(descriptor(result)).toString();}
    static String descriptor(Class<?> type){
        if(type.isArray())return type.getName().replace('.','/');if(!type.isPrimitive())return "L"+type.getName().replace('.','/')+";";
        if(type==void.class)return "V";if(type==boolean.class)return "Z";if(type==byte.class)return "B";if(type==char.class)return "C";
        if(type==short.class)return "S";if(type==int.class)return "I";if(type==long.class)return "J";if(type==float.class)return "F";if(type==double.class)return "D";
        throw new IllegalArgumentException("PRODUCT_PRIMITIVE_TYPE");
    }
    private FixedFrameworkMembers(){}
}
