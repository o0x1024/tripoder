package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Publication is per component, independent of Application.onCreate completion. */
final class RuntimeProviders {
    private final RuntimeEngine engine;
    final RuntimeProviderHandles handles=new RuntimeProviderHandles();
    final RuntimeSettingsObservers settingsObservers;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Set<ContentProvider> instances=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Entry {
        final String lease;final ProviderInfo info;final CompletableFuture<ContentProvider> ready=new CompletableFuture<>();volatile Thread initializing;
        Entry(String lease,ProviderInfo info){this.lease=lease;this.info=info;}
    }
    RuntimeProviders(RuntimeEngine engine,ProviderInfo[] infos,Bundle publications,List<ApkManifest.Component> declarations){
        this.engine=engine;this.settingsObservers=new RuntimeSettingsObservers(engine.host.getContentResolver());
        for(ProviderInfo info:new RuntimeProviderIndex(infos,declarations).publications(engine.processName)){
            info.applicationInfo=new ApplicationInfo(engine.info);
            // Android startup attaches ProviderInfo without GET_META_DATA; explicit PM queries own it.
            info.metaData=null;
            String lease=RuntimeWire.required(publications,info.name);entries.put(lease,new Entry(lease,info));
        }
        if(publications.size()!=entries.size())throw new SecurityException("PROVIDER_PUBLICATIONS_MISMATCH");
    }
    void initialize()throws Exception{
        List<Entry> ordered=new ArrayList<>();synchronized(entries){for(Entry entry:entries.values())if(engine.processName.equals(entry.info.processName))ordered.add(entry);}ordered.sort(Comparator.comparingInt((Entry e)->e.info.initOrder).reversed());
        for(Entry entry:ordered)initialize(entry);
    }
    private void initialize(Entry entry)throws Exception{
            entry.initializing=Thread.currentThread();
            publish(entry,"INITIALIZING");
            try{
                ContentProvider instance=engine.componentFactory.instantiateProvider(engine.loader,entry.info.name);
                if(instance==null||!engine.ownsLoader(instance.getClass().getClassLoader()))throw new SecurityException("PROVIDER_LOADER_MISMATCH");
                synchronized(instances){instances.add(instance);}
                // attachInfo invokes onCreate; invoking it again would duplicate guest side effects.
                RuntimeProviderAttachments.attach(engine,instance,new GuestContext(engine.host,engine),entry.info);
                publish(entry,"READY");entry.ready.complete(instance);
            }catch(Throwable error){entry.ready.completeExceptionally(error);publish(entry,"FAILED");throw error;}finally{entry.initializing=null;}
    }
    /** Same-package multiprocess providers are first attached on the acquiring thread, once per process. */
    private void local(Bundle ref)throws Exception{
        if(!engine.generation.equals(ref.getString("target")))throw new SecurityException("LOCAL_PROVIDER_GENERATION_MISMATCH");
        String lease=RuntimeWire.required(ref,"lease"),component=RuntimeWire.required(ref,"component");Entry entry;boolean owner=false;
        synchronized(entries){
            entry=entries.get(lease);
            if(entry==null){
                ProviderInfo info=engine.providerIndex.component(component);
                if(!info.enabled||!info.multiprocess||!engine.packageName.equals(info.packageName)||engine.processName.equals(info.processName))throw new SecurityException("LOCAL_PROVIDER_DECLARATION_MISMATCH");
                if(engine.componentFactory==null||engine.application==null)throw new IllegalStateException("LOCAL_PROVIDER_APPLICATION_NOT_ATTACHED");
                info.applicationInfo=new ApplicationInfo(engine.info);info.metaData=null;entry=new Entry(lease,info);entries.put(lease,entry);owner=true;
            }else if(!entry.info.name.equals(component))throw new SecurityException("LOCAL_PROVIDER_COMPONENT_MISMATCH");
        }
        if(owner)initialize(entry);else if(entry.initializing==Thread.currentThread())throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
    }
    private void publish(Entry entry,String state)throws Exception{Bundle b=engine.auth();b.putString("lease",entry.lease);b.putString("state",state);engine.brokerCall(RuntimeWire.PROVIDER_PUBLICATION,b);}
    boolean owns(ContentProvider provider){synchronized(instances){return instances.contains(provider);}}
    ContentProvider ready(String lease)throws Exception{
        Entry entry;synchronized(entries){entry=entries.get(lease);}if(entry==null)throw new SecurityException("PROVIDER_LEASE_NOT_IN_PROCESS");
        if(!entry.ready.isDone()&&(entry.initializing==Thread.currentThread()||Looper.myLooper()==Looper.getMainLooper()))throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
        // A cold dependency may still be installing its pre-guest hooks. Share the
        // authority's original deadline; never restart a shorter timer at each layer.
        try{return entry.ready.get(RuntimeStartupDeadline.remaining(engine.startupDeadlineElapsed,SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}catch(TimeoutException error){throw new IllegalStateException("PROVIDER_INITIALIZATION_TIMEOUT",error);}
    }
    RuntimeProviderFacade acquire(String authority,boolean stable,boolean existing){
        Bundle b=engine.auth();b.putString("authority",authority);b.putBoolean("stable",stable);b.putBoolean("existing",existing);
        try{
            Bundle ref=engine.brokerCall(RuntimeWire.PROVIDER_ACQUIRE,b);if(!ref.containsKey("cap"))return null;
            ContentProviderClient client=null;RuntimeProviderFacade facade=null;
            try{
                if(ref.getBoolean("local"))local(ref);
                long identity=Binder.clearCallingIdentity();try{client=stable?engine.host.getContentResolver().acquireContentProviderClient(ref.getString("physical")):engine.host.getContentResolver().acquireUnstableContentProviderClient(ref.getString("physical"));}finally{Binder.restoreCallingIdentity(identity);}
                if(client==null)throw new IllegalStateException("PROVIDER_TRANSPORT_UNAVAILABLE");
                facade=new RuntimeProviderFacade(engine,ref,client);facade.awaitReady();return facade;
            }catch(Throwable error){if(facade!=null)facade.close();else{if(client!=null)client.close();Bundle release=engine.auth();release.putString("cap",ref.getString("cap"));engine.brokerCall(RuntimeWire.PROVIDER_RELEASE,release);}throw error;}
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("PROVIDER_ACQUIRE_FAILED:"+authority,error);}
    }
    ContentProvider router(){return new Router();}
    private final class Router extends RuntimeProviderBase {
        private boolean settings(Uri uri){return uri!=null&&"content".equals(uri.getScheme())&&"settings".equals(uri.getAuthority());}
        private String logical(Uri uri){if(uri==null||!"content".equals(uri.getScheme()))throw new IllegalArgumentException("PROVIDER_URI_INVALID");return RuntimeProviderBroker.authority(uri.getAuthority());}
        @Override public Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal){
            if(settings(uri)){if(signal!=null)throw unsupported();return engine.settingsProvider().query(uri,projection,args,signal);}
            RuntimeProviderFacade facade=acquire(logical(uri),true,false);if(facade==null)return null;boolean retained=false;
            try{Cursor cursor=facade.query(uri,projection,args,signal);if(cursor==null)return null;Cursor owned=new CursorWrapper(cursor){private boolean closed;@Override public void close(){if(closed)return;closed=true;try{super.close();}finally{facade.close();}}};retained=true;return owned;}finally{if(!retained)facade.close();}
        }
        @Override public String getType(Uri uri){if(settings(uri))return engine.settingsProvider().getType(uri);try(RuntimeProviderFacade facade=acquire(logical(uri),false,false)){return facade==null?null:facade.getType(uri);}}
        private RuntimeProviderFacade required(String authority,String missing){RuntimeProviderFacade facade=acquire(authority,true,false);if(facade==null)throw new IllegalArgumentException(missing);return facade;}
        @Override public Uri insert(Uri uri,ContentValues values,Bundle extras){try(RuntimeProviderFacade facade=required(logical(uri),"Unknown URL "+uri)){return facade.insert(uri,values,extras);}}
        @Override public int update(Uri uri,ContentValues values,Bundle extras){try(RuntimeProviderFacade facade=required(logical(uri),"Unknown URI "+uri)){return facade.update(uri,values,extras);}}
        @Override public int delete(Uri uri,Bundle extras){try(RuntimeProviderFacade facade=required(logical(uri),"Unknown URL "+uri)){return facade.delete(uri,extras);}}
        @Override public int bulkInsert(Uri uri,ContentValues[] values){try(RuntimeProviderFacade facade=required(logical(uri),"Unknown URL "+uri)){return facade.bulkInsert(uri,values);}}
        @Override public ContentProviderResult[] applyBatch(String authority,ArrayList<ContentProviderOperation> operations){try(RuntimeProviderFacade facade=required(RuntimeProviderBroker.authority(authority),"Unknown authority "+authority)){return facade.applyBatch(authority,operations);}}
        @Override public Bundle call(String authority,String method,String arg,Bundle extras){if("settings".equals(authority))return engine.settingsProvider().call(authority,method,arg,extras);try(RuntimeProviderFacade facade=required(RuntimeProviderBroker.authority(authority),"Unknown authority "+authority)){return facade.call(authority,method,arg,extras);}}
        @Override public Bundle call(String method,String arg,Bundle extras){throw new SecurityException("PROVIDER_AUTHORITY_REQUIRED");}
    }
}
