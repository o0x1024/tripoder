package dev.tripoder.factorycontract;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.*;
import java.lang.reflect.*;
import java.util.List;
import org.json.JSONObject;

/** Observe our own launch at the same early transaction boundary as app
 * instrumentation libraries. Never alter a transaction, ActivityInfo or Intent. */
final class DeliveryObservation {
    static volatile boolean installed,complete;
    static volatile int launches;
    static volatile boolean executableLoadable,executableIsMain;
    static volatile String failureType="",factoryName="",infoName="",targetName="",componentName="",physicalApplicationPackage="";
    static void install(Application application){
        try{
            Class<?> threadType=Class.forName("android.app.ActivityThread");
            Method current=threadType.getDeclaredMethod("currentActivityThread");current.setAccessible(true);Object thread=current.invoke(null);
            Field handlerField=threadType.getDeclaredField("mH");handlerField.setAccessible(true);Handler handler=(Handler)handlerField.get(thread);
            Field callbackField=Handler.class.getDeclaredField("mCallback");callbackField.setAccessible(true);Handler.Callback previous=(Handler.Callback)callbackField.get(handler);
            Method items=Class.forName("android.app.servertransaction.ClientTransaction").getDeclaredMethod("getTransactionItems");items.setAccessible(true);
            Class<?> launchType=Class.forName("android.app.servertransaction.LaunchActivityItem");
            Field infoField=launchType.getDeclaredField("mInfo"),intentField=launchType.getDeclaredField("mIntent");infoField.setAccessible(true);intentField.setAccessible(true);
            Handler.Callback observer=message->{
                if(message.what==159&&message.obj!=null)try{
                    Object value=items.invoke(message.obj);
                    if(!(value instanceof List<?> transactions))throw new IllegalStateException("OWNED_TRANSACTION_ITEMS_REQUIRED");
                    for(Object item:transactions)if(item!=null&&item.getClass()==launchType){
                        ActivityInfo info=(ActivityInfo)infoField.get(item);Intent intent=(Intent)intentField.get(item);
                        if(info==null||intent==null||intent.getComponent()==null)throw new IllegalStateException("OWNED_LAUNCH_METADATA_REQUIRED");
                        infoName=info.name;targetName=info.targetActivity==null?"":info.targetActivity;componentName=intent.getComponent().getClassName();
                        physicalApplicationPackage=info.applicationInfo.packageName;
                        String executable=info.targetActivity==null?componentName:info.targetActivity;
                        executableIsMain=Main.class.getName().equals(executable);launches++;
                        try{executableLoadable=Activity.class.isAssignableFrom(Class.forName(executable,false,application.getClassLoader()));}
                        catch(ClassNotFoundException missing){executableLoadable=false;}
                    }
                }catch(Exception error){failureType=error.getClass().getName();}
                return previous!=null&&previous.handleMessage(message);
            };
            callbackField.set(handler,observer);installed=true;
        }catch(Exception error){failureType=error.getClass().getName();}
        finally{complete=true;}
    }
    static JSONObject snapshot()throws Exception{
        return new JSONObject().put("installed",installed).put("launches",launches).put("earlyExecutableLoadable",executableLoadable).put("earlyExecutableIsMain",executableIsMain)
            .put("factoryName",factoryName).put("infoName",infoName).put("targetActivity",targetName).put("componentClass",componentName)
            .put("physicalApplicationPackage",physicalApplicationPackage).put("observationFailureType",failureType).put("transactionMutated",false);
    }
    private DeliveryObservation(){}
}
