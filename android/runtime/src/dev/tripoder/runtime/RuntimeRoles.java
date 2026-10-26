package dev.tripoder.runtime;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.ContextWrapper;
import dev.tripoder.selfhook.RoleFrameworkAccess;
import dev.tripoder.selfhook.UserFrameworkAccess;

/** Role holding belongs to a logical installation, never to the host package sharing its UID. */
final class RuntimeRoles {
    private final RuntimeEngine engine;private RoleManager facade;
    RuntimeRoles(RuntimeEngine engine){this.engine=engine;}
    synchronized RoleManager facade(){
        if(facade!=null)return facade;
        // RoleManager names its caller through its Context; that caller is the logical package.
        Context logical=new ContextWrapper(engine.host){@Override public String getPackageName(){return engine.packageName;}};
        facade=RoleFrameworkAccess.facade(logical,engine.host.getSystemService(RoleManager.class),(proxy,method,args)->{
            if(args==null||args.length!=3||!(args[0] instanceof String)||!(args[1] instanceof String)||!(args[2] instanceof Integer))throw new IllegalArgumentException("ROLE_HELD_QUERY_SHAPE");
            if(!engine.packageName.equals(args[1])||(Integer)args[2]!=UserFrameworkAccess.currentUser())throw new SecurityException("ROLE_HELD_QUERY_OUTSIDE_INSTALL");
            // There is no admitted logical role controller or holder assignment.
            // The exact Android answer for a package that holds no role is false;
            // original-device/host role holders are not inherited.
            return false;
        });
        return facade;
    }
}
