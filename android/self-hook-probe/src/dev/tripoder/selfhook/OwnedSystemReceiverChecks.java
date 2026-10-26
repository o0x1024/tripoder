package dev.tripoder.selfhook;

import android.content.*;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Live fixed Class/Method and pure role policy checks; never registers a physical receiver. */
public final class OwnedSystemReceiverChecks {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private interface Request {void run();}
    private static void denied(String name,Request request){boolean rejected=false;try{request.run();}catch(SecurityException expected){rejected=true;}check(rejected,name);}
    private static IntentFilter filter(String action){return action==null?new IntentFilter():new IntentFilter(action);}
    public static final class GuestReceiver extends BroadcastReceiver {public void onReceive(Context context,Intent intent){}}
    public static JSONObject run(Map<Executable,ManagedHook.Handle> reservations)throws Exception{
        Handler handler=new Handler(Looper.getMainLooper());JSONArray roles=new JSONArray();
        for(int owner=80;owner<=82;owner++){
            Class<?> type=FixedFrameworkMembers.owner(owner);String action=owner==80?"android.net.conn.CONNECTIVITY_CHANGE":owner==81?"android.intent.action.PROXY_CHANGE":null;
            int flags=owner==82?Context.RECEIVER_NOT_EXPORTED:0;
            check(SystemReceiverAccess.ownsType(type),"EXACT_CANONICAL_SYSTEM_RECEIVER");
            IntentFilter supplied=filter(action),captured=SystemReceiverAccess.registrationFilter(type,supplied,null,null,flags);
            check(captured!=supplied&&captured.countActions()==(action==null?0:1),"SYSTEM_ROLE_CAPTURES_BASE_FILTER");
            supplied.addAction("owned.mutation");check(captured.countActions()==(action==null?0:1),"SYSTEM_ROLE_CAPTURE_ISOLATED_FROM_CALLER_MUTATION");
            denied("SYSTEM_ROLE_WRONG_ACTION",()->SystemReceiverAccess.registrationFilter(type,new IntentFilter("owned.wrong"),null,null,flags));
            IntentFilter data=filter(action);data.addDataScheme("owned");denied("SYSTEM_ROLE_DATA_CONSTRAINT",()->SystemReceiverAccess.registrationFilter(type,data,null,null,flags));
            IntentFilter priority=filter(action);priority.setPriority(1);denied("SYSTEM_ROLE_PRIORITY",()->SystemReceiverAccess.registrationFilter(type,priority,null,null,flags));
            denied("SYSTEM_ROLE_PERMISSION",()->SystemReceiverAccess.registrationFilter(type,filter(action),"owned.permission",null,flags));
            denied("SYSTEM_ROLE_HANDLER",()->SystemReceiverAccess.registrationFilter(type,filter(action),null,handler,flags));
            denied("SYSTEM_ROLE_FLAGS",()->SystemReceiverAccess.registrationFilter(type,filter(action),null,null,Context.RECEIVER_EXPORTED));
            Method callback=FixedFrameworkMembers.method(owner,"onReceive","(Landroid/content/Context;Landroid/content/Intent;)V",false);
            ManagedHook.Handle handle=ManagedHook.prepare(callback,new Executable[0]);reservations.put(callback,handle);
            boolean rejected=false;try{ManagedHook.enableEntryBatch(Map.of(handle,call->call.original(call.arguments())));}catch(IllegalArgumentException expected){rejected="GUEST_CALLBACK_ENTRY_REQUIRED".equals(expected.getMessage());}
            check(rejected,"SYSTEM_CALLBACK_CANNOT_USE_GUEST_ENTRY_PUBLICATION");
            roles.put(new JSONObject().put("owner",owner).put("type",type.getName()).put("callback",callback.getName()).put("entryOnlyRejected",true));
        }
        check(!SystemReceiverAccess.ownsType(android.appwidget.AppWidgetProvider.class),"UNSELECTED_BOOT_RECEIVER_IS_NOT_CANONICAL_ROLE");
        check(!SystemReceiverAccess.ownsType(GuestReceiver.class),"GUEST_RECEIVER_IS_NOT_CANONICAL_SYSTEM_ROLE");
        int capacity=ManagedHook.remainingCapacity();boolean rejected=false;
        try{ManagedHook.prepare(FixedFrameworkMembers.method(80,"destroy","()V",false),new Executable[0]);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED");}
        check(rejected&&capacity==ManagedHook.remainingCapacity(),"UNSELECTED_SYSTEM_METHOD_CONSUMES_NO_SLOT");
        return new JSONObject().put("passed",true).put("checks",checks).put("roles",roles).put("physicalRegistrationCalled",false).put("productRoutingAccepted",false);
    }
    private OwnedSystemReceiverChecks(){}
}
