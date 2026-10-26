package dev.tripoder.runtime;

import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.os.Bundle;

/** Request-scoped projection: no worker cache, event channel or revision protocol. */
final class RuntimeComponentState {
    static Bundle snapshot(RuntimeEngine engine){
        try{Bundle request=engine.auth();request.putString("kind","snapshot");Bundle result=engine.brokerCall(RuntimeWire.COMPONENT_STATE,request).getBundle("states");if(result==null)throw new IllegalStateException("COMPONENT_SETTINGS_RESPONSE_MISSING");return result;}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("COMPONENT_SETTINGS_QUERY_FAILED",error);}
    }
    static boolean enabled(Bundle states,String name,boolean declared){int state=states.getInt(name,0);if(state<0||state>2)throw new SecurityException("COMPONENT_SETTINGS_RESPONSE_INVALID");return state==0?declared:state==1;}
    static boolean enabled(Bundle states,ComponentInfo info){return info!=null&&info.applicationInfo.enabled&&enabled(states,info.name,info.enabled);}
    static ActivityInfo activity(RuntimeEngine engine,ActivityInfo raw){ActivityInfo copy=new ActivityInfo(raw);copy.enabled=enabled(snapshot(engine),copy.name,copy.enabled);return copy;}
    private RuntimeComponentState(){}
}
