package dev.tripoder.runtime;
import android.app.*;
import android.content.Intent;
import android.content.BroadcastReceiver;

public final class RuntimeFactory extends AppComponentFactory {
    @Override public BroadcastReceiver instantiateReceiver(ClassLoader cl,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        if(RuntimeReceiverSlots.isStub(name))try{
            var host=RuntimeApplication.host;
            if(host!=null&&RuntimePendingIntentPayload.version(host,intent)!=null)return new RuntimeRejectedReceiver("PENDING_VERSION_RETIRED");
            if(host==null||intent==null||intent.getComponent()==null||!intent.getComponent().equals(new android.content.ComponentName(host,name))||!host.getPackageName().equals(intent.getPackage())||intent.getAction()!=null)throw new SecurityException("BROADCAST_PHYSICAL_COMPONENT_MISMATCH");
            String rejected=bootstrap(name,"restoreReceiver",RuntimeBroadcastIntent.ticket(intent));
            if(rejected!=null)return new RuntimeRejectedReceiver(rejected);
            return RuntimeEngine.current.receivers.instantiate(intent);
        }catch(Exception error){if("PROCESS_CLOSING".equals(error.getMessage()))return new RuntimeRejectedReceiver("PROCESS_CLOSING");throw new IllegalStateException("GUEST_RECEIVER_FAILED",error);}
        return super.instantiateReceiver(cl,name,intent);
    }
    @Override public Service instantiateService(ClassLoader cl,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        if(RuntimeServices.isStub(name))try{bootstrap(name,"restoreService",null);return RuntimeEngine.current.services.instantiate(name,intent);}catch(Exception error){throw new IllegalStateException("GUEST_SERVICE_FAILED",error);}
        return super.instantiateService(cl,name,intent);
    }
    static String bootstrap(String name,String method,String ticket)throws Exception{
        // A live worker already owns its immutable Broker capability. Its
        // component claim below authenticates the ticket against that owner;
        // only a cold worker needs the provider to acquire a new allocation.
        if(RuntimeEngine.current!=null&&(method.equals("restoreService")||method.equals("restoreActivity"))){RuntimeEngine.awaitConstructed();return null;}
        var host=RuntimeApplication.host;if(host==null)throw new IllegalStateException("HOST_APPLICATION_NOT_ATTACHED");
        var control=RuntimeProcessControl.get(host);var request=new android.os.Bundle();request.putString("stub",name);if(ticket!=null)request.putString("ticket",ticket);request.putBinder("peer",control);
        var bootstrap=host.getContentResolver().call(android.net.Uri.parse("content://"+host.getPackageName()+".runtime.bootstrap"),method,null,request);
        if(bootstrap==null)throw new IllegalStateException("BOOTSTRAP_RESPONSE_MISSING");
        if(bootstrap.containsKey("rejectedReceiver")){if(!method.equals("restoreReceiver"))throw new SecurityException("INVALID_REJECTION_KIND");return bootstrap.getString("rejectedReceiver");}
        if(bootstrap.containsKey("rejectedActivity")){if(!method.equals("restoreActivity"))throw new SecurityException("INVALID_REJECTION_KIND");return bootstrap.getString("rejectedActivity");}
        if(bootstrap.getBoolean("awaitExisting")){RuntimeEngine.awaitConstructed();return null;}
        control.configureAbort(bootstrap.getString("bootstrap"));control.claim(bootstrap.getInt("authorityPid"));new RuntimeEngine(host,bootstrap);
        return null;
    }
    @Override public Activity instantiateActivity(ClassLoader cl,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        if(RuntimeSlot.isActivity(name)||RuntimeActivitySlots.retired(name))throw new IllegalStateException("ACTIVITY_COMPONENT_PERMANENTLY_RETIRED");
        if(RuntimeActivitySlots.owns(name)){
            try{
                return RuntimeActivityCreation.cold(name,intent);
            }catch(Exception e){throw new IllegalStateException("GUEST_ACTIVITY_FAILED",e);}
        }return super.instantiateActivity(cl,name,intent);
    }
}
