package dev.tripoder.selfhook;

import android.content.Context;
import android.content.pm.ShortcutManager;
import java.lang.reflect.*;
import java.util.*;

/** Fixed Binder/constructor seams, with no ART entry replacement. */
public final class ManagerServiceAccess {
    private static Constructor<?> slice,shortcut;
    private static Method getList;
    private static Field notification;
    private static boolean installed;
    public static synchronized void prepare()throws Exception{
        if(slice!=null)return;
        slice=FixedFrameworkMembers.constructor(88,"(Ljava/util/List;)V");
        getList=FixedFrameworkMembers.method(89,"getList","()Ljava/util/List;",false);
        shortcut=FixedFrameworkMembers.constructor(45,"(Landroid/content/Context;Landroid/content/pm/IShortcutService;)V");
        notification=FixedFrameworkMembers.field(73,38,"sService","Landroid/app/INotificationManager;",true);
    }
    public static Object slice(List<?> values){return FixedFrameworkMembers.create(slice,values);}
    public static List<?> list(Object value){return (List<?>)FixedFrameworkMembers.call(getList,Objects.requireNonNull(value));}
    public interface Boundary {Object invoke(Object original,Method method,Object[] args)throws Throwable;}
    public static synchronized void notifications(Boundary boundary)throws Exception{
        prepare();if(installed)throw new IllegalStateException("NOTIFICATION_BOUNDARY_ALREADY_INSTALLED");
        Object original=FixedFrameworkMembers.call(FixedFrameworkMembers.method(38,"getService","()Landroid/app/INotificationManager;",true),null);
        Class<?> contract=notification.getType();Objects.requireNonNull(original);
        Object routed=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(p,m,a)->boundary.invoke(original,m,a==null?new Object[0]:a));
        FixedFrameworkMembers.set(notification,null,routed);installed=true;
    }
    public static ShortcutManager shortcuts(Context context,InvocationHandler boundary){
        Class<?> contract=shortcut.getParameterTypes()[1];
        Object routed=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},boundary);
        return (ShortcutManager)FixedFrameworkMembers.create(shortcut,context,routed);
    }
    public static Object call(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private ManagerServiceAccess(){}
}
