package dev.tripoder.runtime;

import android.os.Bundle;
import android.os.UserManager;
import dev.tripoder.selfhook.UserFrameworkAccess;

/** Restrictions belong to a logical installation, never to the host package sharing its UID. */
final class RuntimeUsers {
    private final RuntimeEngine engine;private UserManager facade;
    RuntimeUsers(RuntimeEngine engine){this.engine=engine;}
    synchronized UserManager facade(){
        if(facade==null)facade=UserFrameworkAccess.facade(engine.host,engine.host.getSystemService(UserManager.class),(proxy,method,args)->{
            if(method.getName().equals("setApplicationRestrictions"))throw new UnsupportedOperationException("USER_RESTRICTION_ADMIN_NOT_ADMITTED");
            if(args==null||args.length!=2||!(args[0] instanceof String)||!(args[1] instanceof Integer))throw new IllegalArgumentException("USER_RESTRICTION_QUERY_SHAPE");
            if(!engine.packageName.equals(args[0])||(Integer)args[1]!=UserFrameworkAccess.currentUser())throw new SecurityException("USER_RESTRICTION_QUERY_OUTSIDE_INSTALL");
            // There is no admitted logical DPC/supervision issuer or restriction
            // mutation. The exact Android answer for no saved restrictions is an
            // empty fresh Bundle; original-device/host restrictions are not inherited.
            return new Bundle();
        });
        return facade;
    }
}
