package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Android-owned, inert until the worker has an authenticated immutable identity. */
public class RuntimeProviderTransport extends RuntimeProviderBase {
    private String physical;
    static String authority(Context host,int slot){return host.getPackageName()+".runtime.provider."+slot;}
    @Override public void attachInfo(Context context,ProviderInfo info){physical=info.authority;super.attachInfo(context,info);}
    @Override public String getType(Uri uri){throw new SecurityException("PROVIDER_OPERATION_CAPABILITY_REQUIRED");}
    @Override public Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal){
        if(signal!=null)throw unsupported();List<String> path=uri.getPathSegments();
        if("content".equals(uri.getScheme())&&physical.equals(uri.getAuthority())&&path.size()==2&&"v1-thin".equals(path.get(0))&&uri.getQueryParameterNames().equals(Set.of("uri","callerPackage"))&&uri.getQueryParameters("uri").size()==1&&uri.getQueryParameters("callerPackage").size()==1&&uri.getFragment()==null){
            Bundle envelope=new Bundle();envelope.putString("lease",path.get(1));envelope.putString("kind","query");envelope.putString("uri",uri.getQueryParameter("uri"));envelope.putString("callerPackage",uri.getQueryParameter("callerPackage"));envelope.putBundle("payload",RuntimeProviderFacade.primitive(args));envelope.putStringArray("projection",projection);
            return (Cursor)dispatchThin(envelope);
        }
        if(!"content".equals(uri.getScheme())||!physical.equals(uri.getAuthority())||path.size()!=3||!path.get(0).equals("v1")||!uri.getQueryParameterNames().equals(Set.of("uri"))||uri.getQueryParameters("uri").size()!=1||uri.getFragment()!=null)throw new SecurityException("PROVIDER_ENVELOPE_INVALID");
        Bundle envelope=new Bundle();envelope.putString("cap",path.get(1));envelope.putString("operation",path.get(2));envelope.putString("kind","query");envelope.putString("uri",uri.getQueryParameter("uri"));envelope.putBundle("payload",RuntimeProviderFacade.primitive(args));envelope.putStringArray("projection",projection);
        return (Cursor)dispatch(envelope);
    }
    @Override public Bundle call(String authority,String method,String arg,Bundle extras){if(!physical.equals(authority))throw new SecurityException("PROVIDER_PHYSICAL_AUTHORITY_MISMATCH");return call(method,arg,extras);}
    @Override public Bundle call(String method,String arg,Bundle extras){
        if("tripoder.thin".equals(method)){
            if(arg!=null||extras==null)throw new SecurityException("PROVIDER_ENVELOPE_INVALID");
            return (Bundle)dispatchThin(extras);
        }
        if(!"tripoder.dispatch".equals(method)||arg!=null||extras==null||extras.size()!=6)throw new SecurityException("PROVIDER_ENVELOPE_INVALID");
        return (Bundle)dispatch(extras);
    }
    private Object dispatchThin(Bundle envelope){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null||engine.mode!=RuntimeMode.THIN)throw new SecurityException("PROVIDER_PROCESS_NOT_AUTHENTICATED");
        if(Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("PROVIDER_CALLER_UID_MISMATCH");
        String kind=RuntimeWire.required(envelope,"kind"),lease=RuntimeWire.required(envelope,"lease"),logical=RuntimeWire.required(envelope,"uri"),callerPackage=RuntimeWire.required(envelope,"callerPackage");
        String authority=envelope.getString("authority",Uri.parse(logical).getAuthority());
        Bundle payload=envelope.getBundle("payload");String[] projection=envelope.getStringArray("projection");
        Bundle caller=new Bundle();caller.putString("callerPackage",callerPackage);caller.putInt("callerPid",Binder.getCallingPid());caller.putInt("callerUid",Binder.getCallingUid());caller.putBoolean("thin",true);
        try{
            ContentProvider target=engine.providers.ready(lease);
            try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(target,caller)){
                switch(kind){
                    case "ready":return new Bundle();
                    case "query":return RuntimeProviderCursor.wrap(target,target.query(Uri.parse(logical),projection,payload,null),caller);
                    case "getType":{Bundle b=new Bundle();b.putString("type",target.getType(Uri.parse(logical)));return b;}
                    case "call":return RuntimeProviderFacade.primitive(target.call(authority,RuntimeWire.required(payload,"method"),payload.getString("arg"),RuntimeProviderFacade.primitive(payload.getBundle("extras"))));
                    case "insert":case "update":case "delete":case "bulkInsert":return write(target,kind,Uri.parse(logical),payload);
                    case "applyBatch":{ArrayList<ContentProviderOperation> operations=RuntimeProviderBatch.decode(payload.getByteArray("batch"),authority);return RuntimeProviderBatch.result(target.applyBatch(authority,operations),operations.size(),authority);}
                    default:throw new UnsupportedOperationException("PROVIDER_OPERATION_UNSUPPORTED");
                }
            }
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("PROVIDER_DISPATCH_FAILED:"+error.getClass().getName()+":"+error.getMessage(),error);}
    }
    private Object dispatch(Bundle envelope){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null)throw new SecurityException("PROVIDER_PROCESS_NOT_AUTHENTICATED");
        String kind=RuntimeWire.required(envelope,"kind"),operation=RuntimeWire.required(envelope,"operation"),cap=RuntimeWire.required(envelope,"cap"),logical=RuntimeWire.required(envelope,"uri");
        if(!cap.matches("[0-9a-f]{64}")||!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("PROVIDER_CAPABILITY_INVALID");
        Bundle input=engine.auth();input.putString("cap",cap);input.putString("operation",operation);input.putString("kind",kind);input.putString("uri",logical);input.putInt("callerPid",Binder.getCallingPid());input.putInt("callerUid",Binder.getCallingUid());
        Bundle payload=envelope.getBundle("payload");String[] projection=envelope.getStringArray("projection");
        Bundle fingerprint=new Bundle();fingerprint.putBundle("payload",payload);fingerprint.putStringArray("projection",projection);
        try{
            if(kind.equals("applyBatch"))RuntimeProviderBatch.summary(input,payload,Uri.parse(logical).getAuthority());
            input.putString("digest",RuntimeProviderFacade.digest(fingerprint));Bundle claimed=authority(engine,RuntimeWire.PROVIDER_DISPATCH,input);boolean success=false;
            try{
                ContentProvider target=engine.providers.ready(claimed.getString("lease"));
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(target,claimed)){
                    Object result;
                    switch(kind){
                        case "ready":result=new Bundle();break;
                        case "query":result=RuntimeProviderCursor.wrap(target,target.query(Uri.parse(logical),projection,payload,null),claimed);break;
                        case "getType":{Bundle b=new Bundle();b.putString("type",target.getType(Uri.parse(logical)));result=b;break;}
                        case "call":result=RuntimeProviderFacade.primitive(target.call(claimed.getString("authority"),RuntimeWire.required(payload,"method"),payload.getString("arg"),RuntimeProviderFacade.primitive(payload.getBundle("extras"))));break;
                        case "insert":case "update":case "delete":case "bulkInsert":result=write(target,kind,Uri.parse(logical),payload);break;
                        case "applyBatch":{ArrayList<ContentProviderOperation> operations=RuntimeProviderBatch.decode(payload.getByteArray("batch"),claimed.getString("authority"));result=RuntimeProviderBatch.result(target.applyBatch(claimed.getString("authority"),operations),operations.size(),claimed.getString("authority"));break;}
                        default:throw new UnsupportedOperationException("PROVIDER_OPERATION_UNSUPPORTED");
                    }
                    success=true;return result;
                }
            }catch(Exception error){input.putString("failureClass",error.getClass().getName());String message=String.valueOf(error.getMessage());input.putString("failureMessage",message.substring(0,Math.min(message.length(),1024)));throw error;}
            finally{input.putBoolean("success",success);authority(engine,RuntimeWire.PROVIDER_FINISH,input);}
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("PROVIDER_DISPATCH_FAILED:"+error.getClass().getName()+":"+error.getMessage(),error);}
    }
    private static Bundle authority(RuntimeEngine engine,int code,Bundle input)throws Exception{long identity=Binder.clearCallingIdentity();try{return engine.brokerCall(code,input);}finally{Binder.restoreCallingIdentity(identity);}}
    private static Bundle write(ContentProvider target,String kind,Uri uri,Bundle payload){
        RuntimeProviderValues.bounded(payload);Bundle result=new Bundle();
        if(kind.equals("bulkInsert")){ContentValues[] values=payload.getParcelableArray("values",ContentValues.class);Bundle snapshot=RuntimeProviderValues.bulk(values);result.putInt("count",target.bulkInsert(uri,snapshot.getParcelableArray("values",ContentValues.class)));return result;}
        ContentValues values=RuntimeProviderValues.copy(payload.getParcelable("values",ContentValues.class));Bundle extras=RuntimeProviderFacade.primitive(payload.getBundle("extras"));
        if(kind.equals("insert")){
            Uri inserted=target.insert(uri,values,extras);
            if(inserted!=null&&(!"content".equals(inserted.getScheme())||!uri.getAuthority().equals(inserted.getAuthority())||inserted.toString().length()>8192))throw new SecurityException("PROVIDER_RESULT_URI_INVALID");
            result.putString("uri",inserted==null?null:inserted.toString());
        }else result.putInt("count",kind.equals("update")?target.update(uri,values,extras):target.delete(uri,extras));
        return result;
    }
    public static final class P0 extends RuntimeProviderTransport {}
    public static final class P1 extends RuntimeProviderTransport {}
    public static final class P2 extends RuntimeProviderTransport {}
    public static final class P3 extends RuntimeProviderTransport {}
    public static final class P4 extends RuntimeProviderTransport {}
    public static final class P5 extends RuntimeProviderTransport {}
    public static final class P6 extends RuntimeProviderTransport {}
    public static final class P7 extends RuntimeProviderTransport {}
}
