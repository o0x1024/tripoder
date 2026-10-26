package dev.tripoder.selfhook;

import android.app.role.RoleManager;
import android.content.Context;
import java.lang.reflect.*;
import java.util.Objects;

/** Exact boot RoleManager construction; the caller supplies the logical package role-holding policy. */
public final class RoleFrameworkAccess {
    private static Constructor<?> constructor;private static Field service;
    private static synchronized void prepare(){
        if(constructor!=null)return;
        try{
            Constructor<?> selected=FixedFrameworkMembers.constructor(84,"(Landroid/content/Context;Landroid/app/role/IRoleManager;)V");
            Field field=FixedFrameworkMembers.field(69,84,"mService","Landroid/app/role/IRoleManager;",false);
            constructor=selected;service=field;
        }catch(Exception error){throw new IllegalStateException("ROLE_FRAMEWORK_MEMBERS_UNAVAILABLE",error);}
    }
    public static RoleManager facade(Context logical,RoleManager physical,InvocationHandler policy){
        prepare();Objects.requireNonNull(policy);
        Object original=Objects.requireNonNull(FixedFrameworkMembers.get(service,Objects.requireNonNull(physical)));
        Class<?> contract=constructor.getParameterTypes()[1];
        Object routed=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                switch(method.getName()){case "equals":return proxy==args[0];case "hashCode":return System.identityHashCode(proxy);case "toString":return "TripoderRoleService";default:throw new IllegalStateException("ROLE_OBJECT_METHOD");}
            }
            // Holding a role is package-scoped authority. All other operations
            // retain the real platform service's device-user and permission semantics.
            if(method.getName().equals("isRoleHeldAsUser"))return policy.invoke(proxy,method,args);
            try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
        return (RoleManager)FixedFrameworkMembers.create(constructor,Objects.requireNonNull(logical),routed);
    }
    private RoleFrameworkAccess(){}
}
