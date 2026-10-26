package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.tripoder.selfhook.PendingIntentFrameworkAccess;
import java.lang.reflect.Method;
import java.util.*;

/** Logical views over real PendingIntent/IntentSender Binder identities. */
final class RuntimePendingIntents {
    private final RuntimeEngine engine;
    RuntimePendingIntents(RuntimeEngine engine){this.engine=engine;}
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Method,RuntimeManagedInstaller.Callback> bindings=new LinkedHashMap<>();
        for(Method method:PendingIntentFrameworkAccess.hooks())bindings.put(method,(receiver,args,original)->{
            RuntimeEngine engine=RuntimeEngine.current;
            if(method.getDeclaringClass()==AlarmManager.class)return engine.alarms.invoke(method,receiver,args,original);
            return engine.pendingIntents.invoke(method,receiver,args,original);
        });installer.install(bindings);
    }
    private Object invoke(Method method,Object receiver,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        String name=method.getName();
        if(java.lang.reflect.Modifier.isStatic(method.getModifiers())){
            if(args.length==0||!(args[0] instanceof Context)||!owns((Context)args[0]))return original.call(args);
            String tokenKind=name.equals("getBroadcast")||name.equals("getBroadcastAsUser")?"broadcast":name.equals("getActivity")?"activity":name.equals("getService")||name.equals("getForegroundService")?"service":null;
            if(tokenKind==null)throw new UnsupportedOperationException("PENDING_KIND_UNSUPPORTED:"+name);
            if(name.equals("getBroadcastAsUser")&&args.length==5&&!android.os.Process.myUserHandle().equals(args[4]))throw new SecurityException("PENDING_OTHER_USER_UNSUPPORTED");
            Intent source=(Intent)args[2];
            if(!tokenKind.equals("broadcast")){
                // An implicit start resolves against the guest's own filters now, exactly as it would when sent; a target the guest does not declare has no token.
                ComponentName target=source==null?null:source.getComponent()!=null?source.getComponent():tokenKind.equals("activity")?RuntimeBridge.resolveOwnActivity(source):RuntimeBridge.resolveOwnService(source);
                if(target==null)throw new UnsupportedOperationException("PENDING_DESTINATION_UNRESOLVED:"+name);
                source=new Intent(source).setComponent(target);
            }
            Intent logical=RuntimePendingIntentPayload.logical(source,tokenKind);Bundle request=engine.auth();request.putString("kind","get");request.putString("tokenKind",tokenKind);request.putString("operation",UUID.randomUUID().toString());request.putInt("requestCode",(Integer)args[1]);request.putInt("flags",(Integer)args[3]);request.putByteArray("payload",RuntimeBroadcastIntent.encodeFor(tokenKind,logical));
            return engine.brokerCall(RuntimeWire.PENDING_INTENT,request).getParcelable("token",PendingIntent.class);
        }
        PendingIntent token=receiver instanceof PendingIntent?(PendingIntent)receiver:PendingIntentFrameworkAccess.pending((IntentSender)receiver);
        Bundle value=view(token);if(value==null)return original.call(args);
        switch(name){
            case "getCreatorPackage":case "getTargetPackage":return value.getString("package");
            case "cancel":{Bundle request=engine.auth();request.putString("kind","cancel");request.putString("version",value.getString("version"));request.putString("operation",UUID.randomUUID().toString());engine.brokerCall(RuntimeWire.PENDING_INTENT,request);return null;}
            case "intentFilterEquals":{Bundle other=args[0] instanceof PendingIntent?view((PendingIntent)args[0]):null;return other!=null&&RuntimeBroadcastIntent.decode(value.getByteArray("payload")).filterEquals(RuntimeBroadcastIntent.decode(other.getByteArray("payload")));}
            case "sendAndReturnResult":return sendPending(token,value,args,original);
            case "sendIntent":return sendSender((IntentSender)receiver,value,args,original);
            default:throw new UnsupportedOperationException("PENDING_CAPABILITY_UNSUPPORTED:"+name);
        }
    }
    Bundle view(PendingIntent token)throws Exception{
        String version=RuntimePendingIntentPayload.taggedVersion(PendingIntentFrameworkAccess.tokenTag(token));if(version==null)return null;
        if(token.getCreatorUid()!=android.os.Process.myUid())throw new SecurityException("PENDING_TOKEN_UID_MISMATCH");
        Bundle request=engine.auth();request.putString("kind","view");request.putString("version",version);return engine.brokerCall(RuntimeWire.PENDING_INTENT,request);
    }
    private Object sendPending(PendingIntent token,Bundle value,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        if(args.length!=7)throw new IllegalArgumentException("PENDING_SEND_SHAPE");validateSend((Context)args[0],args[5],args[6]);
        Object[] physical=args.clone();physical[0]=engine.host;physical[2]=fill(value,(Intent)args[2]);
        PendingIntent.OnFinished finished=(PendingIntent.OnFinished)args[3];
        if(finished!=null)physical[3]=(PendingIntent.OnFinished)(p,intent,code,data,extras)->finished.onSendFinished(token,delivered(value,intent),code,data,extras);
        return original.call(physical);
    }
    private Object sendSender(IntentSender sender,Bundle value,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        if(args.length!=7)throw new IllegalArgumentException("PENDING_SENDER_SHAPE");
        Object options=args[4];
        Bundle defaults=PendingIntentFrameworkAccess.senderOptions(sender);
        if(options!=null&&!sameOptions((Bundle)options,defaults))throw new UnsupportedOperationException("PENDING_SEND_OPTIONS_UNSUPPORTED:supplied="+optionTypes((Bundle)options)+";default="+optionTypes(defaults));
        validateSend((Context)args[0],args[3],null);
        Object[] physical=args.clone();physical[0]=engine.host;physical[2]=fill(value,(Intent)args[2]);
        IntentSender.OnFinished finished=(IntentSender.OnFinished)args[6];
        if(finished!=null)physical[6]=(IntentSender.OnFinished)(s,intent,code,data,extras)->finished.onSendFinished(sender,delivered(value,intent),code,data,extras);
        return original.call(physical);
    }
    private void validateSend(Context context,Object permission,Object options){
        if(context!=null&&!owns(context))throw new SecurityException("PENDING_SEND_CONTEXT_NOT_OWNED");
        if(permission!=null||options!=null)throw new UnsupportedOperationException("PENDING_SEND_OPTIONS_UNSUPPORTED");
    }
    private static boolean sameOptions(Bundle supplied,Bundle expected){
        if(expected==null||supplied.hasFileDescriptors()||!supplied.keySet().equals(expected.keySet()))return false;
        for(String key:expected.keySet()){
            Object value=expected.get(key),actual=supplied.get(key);
            // Keep the platform's own opaque default objects (including Xiaomi's
            // ActivityOptions injector) by exact identity, never caller substitutes.
            if(value==actual)continue;
            if(!(value instanceof Integer||value instanceof Boolean||value instanceof Long||value instanceof String)||!Objects.equals(value,actual))return false;
        }return true;
    }
    private static String optionTypes(Bundle value){if(value==null)return "null";List<String> types=new ArrayList<>();for(String key:value.keySet()){Object item=value.get(key);types.add(key+":"+(item==null?"null":item.getClass().getName()));}Collections.sort(types);return types.toString();}
    private static Intent fill(Bundle value,Intent fill){return RuntimePendingIntentPolicy.immutable(value.getInt("flags"))?null:RuntimePendingIntentPayload.fillIn(fill);}
    private static Intent delivered(Bundle value,Intent physical){return RuntimePendingIntentPayload.delivered(RuntimeBroadcastIntent.decode(value.getByteArray("payload")),physical,value.getString("tokenKind","broadcast"));}
    private boolean owns(Context context){Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());while(context!=null&&seen.size()<16&&seen.add(context)){if(context instanceof GuestContext)return ((GuestContext)context).engine==engine;context=context instanceof ContextWrapper?((ContextWrapper)context).getBaseContext():null;}return false;}
}
