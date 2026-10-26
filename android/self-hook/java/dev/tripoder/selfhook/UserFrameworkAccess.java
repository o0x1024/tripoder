package dev.tripoder.selfhook;

import android.content.Context;
import android.os.UserManager;
import java.lang.reflect.*;
import java.util.Objects;

/** Exact boot UserManager construction; the caller supplies logical package restriction policy. */
public final class UserFrameworkAccess {
    private static Constructor<?> constructor;private static Field service;private static Method identifier;
    private static synchronized void prepare(){
        if(constructor!=null)return;
        try{
            Constructor<?> selected=FixedFrameworkMembers.constructor(64,"(Landroid/content/Context;Landroid/os/IUserManager;)V");
            Field field=FixedFrameworkMembers.field(46,64,"mService","Landroid/os/IUserManager;",false);
            Method id=FixedFrameworkMembers.method(49,"getIdentifier","()I",false);
            constructor=selected;service=field;identifier=id;
        }catch(Exception error){throw new IllegalStateException("USER_FRAMEWORK_MEMBERS_UNAVAILABLE",error);}
    }
    public static UserManager facade(Context host,UserManager physical,InvocationHandler policy){
        prepare();Objects.requireNonNull(policy);
        Object original=Objects.requireNonNull(FixedFrameworkMembers.get(service,Objects.requireNonNull(physical)));
        Class<?> contract=constructor.getParameterTypes()[1];
        Object routed=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                switch(method.getName()){case "equals":return proxy==args[0];case "hashCode":return System.identityHashCode(proxy);case "toString":return "TripoderUserService";default:throw new IllegalStateException("USER_OBJECT_METHOD");}
            }
            // These are package-scoped authority. All other operations retain
            // the real platform service's device-user and permission semantics.
            if(method.getName().equals("getApplicationRestrictionsForUser")||method.getName().equals("setApplicationRestrictions"))return policy.invoke(proxy,method,args);
            try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
        return (UserManager)FixedFrameworkMembers.create(constructor,Objects.requireNonNull(host),routed);
    }
    public static int currentUser(){prepare();return (Integer)FixedFrameworkMembers.call(identifier,android.os.Process.myUserHandle());}
    private UserFrameworkAccess(){}
}
