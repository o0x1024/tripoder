package dev.tripoder.runtime;

import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** All logical resolution is space-bound. Never resolve a logical authority in Android PM. */
final class RuntimeProviderBroker {
    private final RuntimeBroker broker;
    private final Map<String,Map<String,Integer>> waits=new HashMap<>();
    RuntimeProviderBroker(RuntimeBroker broker){this.broker=broker;}
    static String authority(String authority){if(authority==null||!authority.matches("[a-zA-Z0-9_][a-zA-Z0-9_.:-]{0,199}")||authority.equals("settings"))throw new SecurityException("PROVIDER_AUTHORITY_INVALID");return authority;}
    static List<String> aliases(String authorities){List<String> out=Arrays.asList(authorities.split(";",-1));Set<String> unique=new HashSet<>();for(String a:out)if(!unique.add(authority(a)))throw new SecurityException("PROVIDER_AUTHORITY_DUPLICATE");return out;}
    private static ProviderInfo metadata(ApkStore.BundleInstall install,String component){return new RuntimeProviderIndex(install.info.providers,install.components()).component(component);}
    private static void access(ProviderInfo p,String caller,String kind,Uri uri){
        boolean read,write;
        switch(kind){case "ready":read=false;write=false;break;case "query":case "getType":read=true;write=false;break;case "call":read=true;write=true;break;case "insert":case "update":case "delete":case "bulkInsert":read=false;write=true;break;default:throw new UnsupportedOperationException("PROVIDER_OPERATION_UNSUPPORTED");}
        if(caller.equals(p.packageName))return;
        if(!p.exported)throw new SecurityException("PROVIDER_NOT_EXPORTED");
        if(read&&p.readPermission!=null||write&&p.writePermission!=null)throw new SecurityException("PROVIDER_PERMISSION_UNSUPPORTED");
        if(p.pathPermissions!=null)for(var path:p.pathPermissions)if(uri==null||path.match(uri.getPath()))if(read&&path.getReadPermission()!=null||write&&path.getWritePermission()!=null)throw new SecurityException("PROVIDER_PATH_PERMISSION_UNSUPPORTED");
    }
    private static void accessRequest(ProviderInfo provider,String caller,String kind,Uri uri,Bundle input){
        if(!kind.equals("applyBatch")){access(provider,caller,kind,uri);return;}
        Bundle routes=input.getBundle("batchRoutes");if(routes==null)throw new SecurityException("PROVIDER_BATCH_ROUTES_REQUIRED");String[] uris=routes.getStringArray("uris"),kinds=routes.getStringArray("kinds");
        if(uris==null||kinds==null||uris.length!=kinds.length||uris.length>32)throw new SecurityException("PROVIDER_BATCH_ROUTES_INVALID");
        for(int i=0;i<uris.length;i++){Uri part=Uri.parse(uris[i]);if(!"content".equals(part.getScheme())||!uri.getAuthority().equals(part.getAuthority())||uris[i].length()>4096||!Set.of("insert","update","delete","query").contains(kinds[i]))throw new SecurityException("PROVIDER_BATCH_ROUTE_INVALID");access(provider,caller,kinds[i],part);}
    }
    Bundle acquire(Bundle input,int pid,int uid)throws Exception{
        String source=RuntimeWire.required(input,"generation"),logical=authority(RuntimeWire.required(input,"authority"));boolean existing=input.getBoolean("existing");
        String space;InstallProviderIndex.Target targetInstall;
        synchronized(broker){
            space=broker.contract.authenticate(source,pid,uid).identity.spaceId;
            targetInstall=InstallProviderIndex.find(broker.store.getReadableDatabase(),space,logical);
            if(targetInstall==null)return new Bundle();
        }
        // Only the indexed owner is verified. Full APK I/O cannot hold the broker monitor.
        ApkStore.BundleInstall install=broker.apks.get(space,targetInstall.pkg);
        String component=null,process=null;RuntimeBroker.Slot to;boolean local;
        synchronized(broker){
            var session=broker.contract.authenticate(source,pid,uid);
            if(!targetInstall.generation.equals(install.generation)||!targetInstall.same(InstallProviderIndex.find(broker.store.getReadableDatabase(),space,logical)))throw new SecurityException("PROVIDER_STALE_INSTALL");
            for(var declared:install.components())if(declared.type.equals("provider")&&declared.enabled&&aliases(declared.authorities).contains(logical)){
                component=declared.name;process=declared.process;break;
            }
            if(component==null)return new Bundle();
            ProviderInfo info=metadata(install,component);if(!session.identity.packageName.equals(install.info.packageName)&&!info.exported)throw new SecurityException("PROVIDER_NOT_EXPORTED");
            local=info.multiprocess&&session.identity.packageName.equals(install.info.packageName)&&!process.equals(session.identity.processName);
            if(local)process=session.identity.processName;
            to=broker.findProcess(space,install.info.packageName,process);
            if(local){
                if(to==null||!source.equals(to.allocation.session.processGeneration)||!to.install.generation.equals(install.generation))throw new SecurityException("LOCAL_PROVIDER_PROCESS_MISMATCH");
                Bundle publication=broker.providers.local(to,component,existing);if(existing&&(publication==null||!"READY".equals(publication.getString("publication"))))return new Bundle();
            }
            if(existing&&(to==null||!broker.providers.find(to.allocation.session.processGeneration,component).getString("publication").equals("READY")))return new Bundle();
        }
        if(!existing&&!local)to=broker.processes.prepare(space,install.info.packageName,process);
        synchronized(broker){
            broker.contract.authenticate(source,pid,uid);String target=to.allocation.session.processGeneration;
            if(broker.requiredSlot(target)!=to||!to.install.generation.equals(install.generation))throw new SecurityException("PROVIDER_STALE_INSTALL");
            Bundle publication=broker.providers.find(target,component);String cap=broker.providers.acquire(source,publication,logical,input.getBoolean("stable"));
            publication.putString("cap",cap);publication.putString("authority",logical);publication.putBoolean("local",local);publication.putString("physical",RuntimeProviderTransport.authority(broker.host,RuntimeSlot.index(to.activityStub)));return publication;
        }
    }
    Bundle handle(int code,Bundle input,String generation)throws Exception{
        var db=broker.store.getWritableDatabase();db.beginTransaction();try{Bundle result=handleTransaction(code,input,generation);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }
    private Bundle handleTransaction(int code,Bundle input,String generation)throws Exception{
        Bundle out=new Bundle();ProviderLedger ledger=broker.providers;
        if(code==RuntimeWire.PROVIDER_PUBLICATION){ledger.publication(RuntimeWire.required(input,"lease"),generation,RuntimeWire.required(input,"state"));return out;}
        String cap=RuntimeWire.required(input,"cap");Bundle ref=ledger.acquisition(cap);String source=ref.getString("source"),target=ref.getString("target");
        if(code==RuntimeWire.PROVIDER_RELEASE){ledger.release(cap,generation);return out;}
        if(!ref.getString("reference").equals("OPEN"))throw new SecurityException("PROVIDER_REFERENCE_CLOSED");
        if(code==RuntimeWire.PROVIDER_RECEIVED){
            String operation=RuntimeWire.required(input,"operation");Bundle call=ledger.invocation(operation);
            if(!generation.equals(source)||!cap.equals(call.getString("cap"))||!call.getString("invocation").equals("DONE")||broker.contract.query(operation).state!=ExecutionContract.OperationState.SUCCEEDED)throw new SecurityException("PROVIDER_RECEIPT_MISMATCH");
            // A confirmed source may receive a completed response just after target death.
            ledger.received(call);return out;
        }
        var sourceSession=broker.contract.getSession(source);var targetSession=broker.contract.getSession(target);
        if(sourceSession.state==ExecutionContract.ProcessState.DEAD||targetSession.state==ExecutionContract.ProcessState.DEAD)throw new SecurityException("PROVIDER_GENERATION_DEAD");
        if(code==RuntimeWire.PROVIDER_WAIT){
            if(!generation.equals(source))throw new SecurityException("PROVIDER_SOURCE_MISMATCH");
            if(input.getBoolean("begin")){
                if(!source.equals(target)&&!ref.getString("publication").equals("READY")){
                    if(reaches(target,source,new HashSet<>()))throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
                    waits.computeIfAbsent(source,k->new HashMap<>()).merge(target,1,Integer::sum);out.putBoolean("edge",true);
                }
            }else{Map<String,Integer> edges=waits.get(source);if(edges==null||!edges.containsKey(target))throw new IllegalStateException("PROVIDER_WAIT_NOT_FOUND");if(edges.get(target)==1)edges.remove(target);else edges.put(target,edges.get(target)-1);if(edges.isEmpty())waits.remove(source);}
            return out;
        }
        if(code==RuntimeWire.PROVIDER_BEGIN){
            if(!generation.equals(source))throw new SecurityException("PROVIDER_SOURCE_MISMATCH");
            String kind=RuntimeWire.required(input,"kind"),uri=RuntimeWire.required(input,"uri");
            if(!Set.of("ready","query","getType","call","insert","update","delete","bulkInsert","applyBatch").contains(kind))throw new UnsupportedOperationException("PROVIDER_OPERATION_UNSUPPORTED");
            Uri logical=Uri.parse(uri);if(!"content".equals(logical.getScheme())||!ref.getString("authority").equals(logical.getAuthority())||uri.length()>8192)throw new SecurityException("PROVIDER_URI_MISMATCH");
            var slot=broker.requiredSlot(target);if(!kind.equals("ready")){ProviderInfo info=metadata(slot.install,ref.getString("component"));RuntimeProviderIndex.requireAuthority(info,ref.getString("authority"));accessRequest(info,sourceSession.identity.packageName,kind,logical,input);}
            String op=RuntimeWire.required(input,"operation");var accepted=broker.contract.accept(op,sourceSession.identity.key(),ApkStore.textDigest(cap+"\n"+kind+"\n"+uri+"\n"+RuntimeWire.required(input,"digest")));
            if(!accepted.created)throw new IllegalStateException("PROVIDER_QUERY_OPERATION_REQUIRED");broker.contract.start(op);ledger.begin(op,cap,kind,uri,RuntimeWire.required(input,"digest"));out.putString("operation",op);return out;
        }
        String operation=RuntimeWire.required(input,"operation");Bundle call=ledger.invocation(operation);
        if(!generation.equals(target)||!cap.equals(call.getString("cap")))throw new SecurityException("PROVIDER_TARGET_MISMATCH");
        if(code==RuntimeWire.PROVIDER_CURSOR_ACCESS){
            if(!call.getString("kind").equals("query")||!Set.of("RUNNING","DONE").contains(call.getString("invocation")))throw new SecurityException("PROVIDER_CURSOR_OPERATION_INVALID");
            broker.contract.authenticate(source,input.getInt("callerPid"),input.getInt("callerUid"));
            access(metadata(broker.requiredSlot(target).install,ref.getString("component")),sourceSession.identity.packageName,"query",Uri.parse(call.getString("uri")));return out;
        }
        if(code==RuntimeWire.PROVIDER_DISPATCH){
            if(!call.getString("invocation").equals("PREPARED"))throw new SecurityException("PROVIDER_OPERATION_ALREADY_DISPATCHED");
            broker.contract.authenticate(source,input.getInt("callerPid"),input.getInt("callerUid"));
            if(!RuntimeWire.required(input,"kind").equals(call.getString("kind"))||!RuntimeWire.required(input,"uri").equals(call.getString("uri"))||!RuntimeWire.required(input,"digest").equals(call.getString("digest")))throw new SecurityException("PROVIDER_OPERATION_PAYLOAD_MISMATCH");
            if(!call.getString("kind").equals("ready")){ProviderInfo info=metadata(broker.requiredSlot(target).install,ref.getString("component"));RuntimeProviderIndex.requireAuthority(info,ref.getString("authority"));accessRequest(info,sourceSession.identity.packageName,call.getString("kind"),Uri.parse(call.getString("uri")),input);}
            ledger.claimed(operation);call.putString("callerPackage",sourceSession.identity.packageName);call.putInt("callerPid",sourceSession.pid);call.putInt("callerUid",sourceSession.uid);return call;
        }
        if(code==RuntimeWire.PROVIDER_FINISH){if(!call.getString("invocation").equals("RUNNING"))throw new IllegalStateException("PROVIDER_INVOCATION_NOT_RUNNING");boolean success=input.getBoolean("success");boolean readiness=call.getString("kind").equals("ready");ledger.finished(operation,success||readiness);if(success)broker.contract.succeed(operation,"PROVIDER_CALLBACK_RETURNED");else if(readiness)broker.contract.fail(operation,"PROVIDER_NOT_READY");else broker.contract.markUnknown(operation,"PROVIDER_CALLBACK_THREW_EFFECT_UNKNOWN:"+input.getString("failureClass","")+":"+input.getString("failureMessage",""));return out;}
        throw new IllegalArgumentException("PROVIDER_PROTOCOL_UNSUPPORTED");
    }
    private boolean reaches(String from,String target,Set<String> visited){if(from.equals(target))return true;if(!visited.add(from))return false;for(String next:waits.getOrDefault(from,Map.of()).keySet())if(reaches(next,target,visited))return true;return false;}
    void dead(String generation){waits.remove(generation);for(var edges:waits.values())edges.remove(generation);waits.values().removeIf(Map::isEmpty);}
}
