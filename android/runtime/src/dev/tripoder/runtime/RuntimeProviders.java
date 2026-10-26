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
    /**
     * The providers this thread is inside right now, in install order. A cycle is re-entrance of one of these leases
     * (A.onCreate needs B while B is still initializing), never "the main thread asked for a provider". Android itself
     * installs same-process providers on the requesting thread, including from Application.attach and from another
     * provider's onCreate, so the requesting looper says nothing about whether a cycle exists.
     */
    private static final ThreadLocal<Set<String>> INITIALIZING=ThreadLocal.withInitial(LinkedHashSet::new);
    private static final class Entry {
        final String lease;final ProviderInfo info;final long deadline;final CompletableFuture<ContentProvider> ready=new CompletableFuture<>();volatile Thread initializing;final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();
        Entry(String lease,ProviderInfo info,long deadline){this.lease=lease;this.info=info;this.deadline=deadline;}
    }
    RuntimeProviders(RuntimeEngine engine,ProviderInfo[] infos,Bundle publications,List<ApkManifest.Component> declarations){
        this.engine=engine;this.settingsObservers=new RuntimeSettingsObservers(engine.host.getContentResolver(),engine.providerIndex);
        // The bootstrap publications are authenticated authority admissions. The
        // raw sealed metadata still carries manifest defaults, including disabled.
        for(String component:publications.keySet()){
            ProviderInfo info=engine.providerIndex.component(component);
            if(!engine.processName.equals(info.processName))throw new SecurityException("PROVIDER_PUBLICATION_PROCESS_MISMATCH");
            info.enabled=true;info.applicationInfo=new ApplicationInfo(engine.info);
            // Android startup attaches ProviderInfo without GET_META_DATA; explicit PM queries own it.
            info.metaData=null;
            String lease=RuntimeWire.required(publications,info.name);entries.put(lease,new Entry(lease,info,engine.startupDeadlineElapsed));
        }
        if(publications.size()!=entries.size())throw new SecurityException("PROVIDER_PUBLICATIONS_MISMATCH");
    }
    void initialize()throws Exception{
        // The platform startup batch is silent=false. A provider failure is not
        // an Application success; the existing FAILED future remains terminal.
        installFromFramework(dev.tripoder.selfhook.ApplicationAdapter.providerInstallationBatch());
    }
    /** Validate the complete selector batch before any factory or attach callback.
     * The authority-admitted lease and sealed metadata remain the only publication
     * owner; a shell's mutable ProviderInfo does not create a second Android owner. */
    void installFromFramework(List<?> batch)throws Exception{
        List<Entry> admitted=frameworkEntries(batch);
        if(!admitted.isEmpty()&&(engine.application==null||engine.componentFactory==null))throw new IllegalStateException("PROVIDER_INSTALL_APPLICATION_NOT_CONSTRUCTED");
        for(Entry entry:admitted){
            // This is the caller's explicit local framework batch, including a
            // call made from a Binder thread. Keep Android's calling-thread
            // initialization rather than posting it and waiting on that thread.
            if(!entry.ready.isDone()&&entry.claimed.compareAndSet(false,true))initialize(entry);
            ready(entry.lease);
        }
    }
    private List<Entry> frameworkEntries(List<?> batch){
        if(batch.size()>1024)throw new IllegalArgumentException("PROVIDER_INSTALL_BATCH_TOO_LARGE");
        Bundle states=RuntimeComponentState.snapshot(engine);List<Entry> admitted=new ArrayList<>();
        synchronized(entries){
            for(Object item:batch){
                if(item==null||item.getClass()!=ProviderInfo.class)throw new SecurityException("PROVIDER_INSTALL_SELECTOR_MISMATCH");
                ProviderInfo supplied=(ProviderInfo)item;
                if(!engine.packageName.equals(supplied.packageName)||!engine.processName.equals(supplied.processName)||supplied.name==null)throw new SecurityException("PROVIDER_INSTALL_SELECTOR_MISMATCH");
                ProviderInfo canonical=engine.providerIndex.component(supplied.name,states);
                if(!canonical.enabled||!Objects.equals(canonical.packageName,supplied.packageName)||!Objects.equals(canonical.processName,supplied.processName)||!Objects.equals(canonical.authority,supplied.authority))throw new SecurityException("PROVIDER_INSTALL_SELECTOR_MISMATCH");
                Entry selected=null;
                for(Entry entry:entries.values())if(entry.info.name.equals(canonical.name)){
                    if(selected!=null||!Objects.equals(entry.info.authority,canonical.authority)||!Objects.equals(entry.info.processName,canonical.processName))throw new SecurityException("PROVIDER_INSTALL_PUBLICATION_AMBIGUOUS");selected=entry;
                }
                if(selected==null)throw new SecurityException("PROVIDER_INSTALL_PUBLICATION_REQUIRED");
                admitted.add(selected);
            }
        }
        return admitted;
    }
    private void initialize(Entry entry)throws Exception{
            Set<String> stack=INITIALIZING.get();
            if(!stack.add(entry.lease))throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
            entry.initializing=Thread.currentThread();
            try{
                publish(entry,"INITIALIZING");
                ContentProvider instance=engine.componentFactory.instantiateProvider(engine.guestLoader(),entry.info.name);
                if(instance==null||!engine.ownsLoader(instance.getClass().getClassLoader()))throw new SecurityException("PROVIDER_LOADER_MISMATCH");
                synchronized(instances){instances.add(instance);}
                // attachInfo invokes onCreate; invoking it again would duplicate guest side effects.
                RuntimeProviderAttachments.attach(engine,instance,new GuestContext(engine.host,engine),entry.info);
                publish(entry,"READY");entry.ready.complete(instance);
            }catch(Throwable error){entry.ready.completeExceptionally(error);publish(entry,"FAILED");throw error;}
            finally{entry.initializing=null;stack.remove(entry.lease);if(stack.isEmpty())INITIALIZING.remove();}
    }
    /** Same-package multiprocess providers are first attached on the acquiring thread, once per process. */
    private void local(Bundle ref)throws Exception{
        if(!engine.generation.equals(ref.getString("target")))throw new SecurityException("LOCAL_PROVIDER_GENERATION_MISMATCH");
        String lease=RuntimeWire.required(ref,"lease"),component=RuntimeWire.required(ref,"component");Entry entry;boolean owner=false;
        synchronized(entries){
            entry=entries.get(lease);
            if(entry==null){
                ProviderInfo info=engine.providerIndex.component(component,RuntimeComponentState.snapshot(engine));
                if(!info.enabled||!info.multiprocess||!engine.packageName.equals(info.packageName)||engine.processName.equals(info.processName))throw new SecurityException("LOCAL_PROVIDER_DECLARATION_MISMATCH");
                if(engine.componentFactory==null||engine.application==null)throw new IllegalStateException("LOCAL_PROVIDER_APPLICATION_NOT_ATTACHED");
                info.applicationInfo=new ApplicationInfo(engine.info);info.metaData=null;entry=new Entry(lease,info,engine.ready?RuntimeStartupDeadline.after(SystemClock.elapsedRealtime()):engine.startupDeadlineElapsed);entries.put(lease,entry);owner=true;
            }else if(!entry.info.name.equals(component))throw new SecurityException("LOCAL_PROVIDER_COMPONENT_MISMATCH");
        }
        if(owner)initialize(entry);else if(entry.initializing==Thread.currentThread())throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
    }
    private void publish(Entry entry,String state)throws Exception{if(engine.mode.routesThroughPlatform())return;Bundle b=engine.auth();b.putString("lease",entry.lease);b.putString("state",state);engine.brokerCall(RuntimeWire.PROVIDER_PUBLICATION,b);}
    boolean owns(ContentProvider provider){synchronized(instances){return instances.contains(provider);}}
    ContentProvider ready(String lease)throws Exception{
        Entry entry;synchronized(entries){entry=entries.get(lease);}
        if(entry==null){
            Bundle describe=engine.auth();describe.putString("kind","describe");describe.putString("lease",lease);Bundle owned=engine.brokerCall(RuntimeWire.PROVIDER_PUBLICATION,describe);
            if(!engine.generation.equals(owned.getString("target")))throw new SecurityException("PROVIDER_LEASE_NOT_IN_PROCESS");
            ProviderInfo info=engine.providerIndex.component(RuntimeWire.required(owned,"component"),RuntimeComponentState.snapshot(engine));
            if(!info.enabled||!engine.processName.equals(info.processName))throw new SecurityException("PROVIDER_DYNAMIC_DECLARATION_MISMATCH");
            info.applicationInfo=new ApplicationInfo(engine.info);info.metaData=null;
            Entry created=new Entry(lease,info,engine.ready?RuntimeStartupDeadline.after(SystemClock.elapsedRealtime()):engine.startupDeadlineElapsed);
            synchronized(entries){entry=entries.get(lease);if(entry==null){entry=created;entries.put(lease,entry);}}
        }

        // Android answers a same-process provider request with canRunHere: the requesting thread installs it, even
        // when that thread is the main thread inside Application.attach. The only cycle is this thread already being
        // inside the same provider (directly or through another provider's onCreate); the looper is not evidence.
        if(!entry.ready.isDone()&&Binder.getCallingPid()==android.os.Process.myPid()&&engine.application!=null&&engine.componentFactory!=null
            &&engine.processName.equals(entry.info.processName)){
            if(INITIALIZING.get().contains(entry.lease))throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
            if(entry.claimed.compareAndSet(false,true))initialize(entry);
        }
        if(!entry.ready.isDone()&&entry.initializing==Thread.currentThread())throw new IllegalStateException("PROVIDER_INITIALIZATION_CYCLE");
        if(!entry.ready.isDone()&&Binder.getCallingPid()!=android.os.Process.myPid()&&!entry.claimed.get()){
            // Queueing is not initialization ownership. Bootstrap may install
            // this entry inline before this main-thread message can execute.
            Entry initializing=entry;new Handler(Looper.getMainLooper()).post(()->{
                if(initializing.ready.isDone())return;
                try{
                    // A provider can reach the prepared worker before the
                    // authority's START_APPLICATION transaction. Join the one
                    // canonical main-thread Application task, never a second boot.
                    engine.startApplication();
                    if(!initializing.ready.isDone()&&initializing.claimed.compareAndSet(false,true))initialize(initializing);
                }catch(Throwable error){
                    Throwable cause=error instanceof ExecutionException&&error.getCause()!=null?error.getCause():error;
                    initializing.ready.completeExceptionally(cause);
                    android.util.Log.e("TripoderRuntime","PROVIDER_STARTUP_OR_INSTALL_FAILED:"+initializing.info.name,cause);
                }
            });
        }
        // A cold dependency may still be installing its pre-guest hooks. Share the
        // authority's original deadline; never restart a shorter timer at each layer.
        try{return entry.ready.get(RuntimeStartupDeadline.remaining(entry.deadline,SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}catch(TimeoutException error){throw new IllegalStateException("PROVIDER_INITIALIZATION_TIMEOUT",error);}
    }
    private final ConcurrentHashMap<String,ContentProvider> localAuthorities=new ConcurrentHashMap<>();
    ContentProvider findLocal(String authority){
        if(authority==null)return null;
        ProviderInfo declared=engine.providerIndex.authority(authority);
        if(declared==null||!RuntimeComponentState.enabled(RuntimeComponentState.snapshot(engine),declared))return null;
        ContentProvider cached=localAuthorities.get(authority);
        if(cached!=null)return cached;
        synchronized(entries){
            for(Entry entry:entries.values()){
                if(entry.info.authority!=null){
                    for(String a:entry.info.authority.split(";")){
                        if(authority.equals(a.trim())){
                            try{
                                ContentProvider p=ready(entry.lease);
                                if(p!=null)localAuthorities.put(authority,p);
                                return p;
                            }catch(Exception e){
                                return null;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
    ContentProvider acquire(String authority,boolean stable,boolean existing){
        ContentProvider local=findLocal(authority);
        if(local!=null)return local;
        Bundle b=engine.auth();b.putString("authority",authority);b.putBoolean("stable",stable);b.putBoolean("existing",existing);
        try{
            Bundle ref=engine.brokerCall(RuntimeWire.PROVIDER_ACQUIRE,b);if(!ref.containsKey("cap"))return null;
            ContentProviderClient client=null;RuntimeProviderFacade facade=null;
            try{
                if(ref.getBoolean("local"))local(ref);
                long identity=Binder.clearCallingIdentity();try{client=stable?engine.host.getContentResolver().acquireContentProviderClient(ref.getString("physical")):engine.host.getContentResolver().acquireUnstableContentProviderClient(ref.getString("physical"));}finally{Binder.restoreCallingIdentity(identity);}
                if(client==null)throw new IllegalStateException("PROVIDER_TRANSPORT_UNAVAILABLE");
                facade=new RuntimeProviderFacade(engine,ref,client);
                if(!"READY".equals(ref.getString("publication")))facade.awaitReady();
                return facade;
            }catch(Throwable error){if(facade!=null)facade.close();else{if(client!=null)client.close();if(!ref.getBoolean("thin")){Bundle release=engine.auth();release.putString("cap",ref.getString("cap"));engine.brokerCall(RuntimeWire.PROVIDER_RELEASE,release);}}throw error;}
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("PROVIDER_ACQUIRE_FAILED:"+authority,error);}
    }
    ContentProvider router(){return new Router();}
    private final class Router extends RuntimeProviderBase {
        private final Map<String,RuntimeProviderFacade> cachedFacades=new ConcurrentHashMap<>();
        private final Set<String> externalAuthorities=Collections.newSetFromMap(new ConcurrentHashMap<>());
        private boolean settings(Uri uri){return uri!=null&&"content".equals(uri.getScheme())&&"settings".equals(uri.getAuthority());}
        private String logical(Uri uri){if(uri==null||!"content".equals(uri.getScheme()))throw new IllegalArgumentException("PROVIDER_URI_INVALID");return RuntimeProviderBroker.authority(uri.getAuthority());}
        private boolean allowExternalPassthrough(String authority){
            return engine.mode.routesThroughPlatform()&&authority!=null&&engine.providerIndex.authority(authority)==null&&!authority.startsWith(engine.host.getPackageName())&&!authority.startsWith("dev.tripoder.");
        }
        private Bundle localCaller(){
            Bundle caller=new Bundle();
            caller.putString("callerPackage",engine.packageName);
            caller.putInt("callerPid",android.os.Process.myPid());
            caller.putInt("callerUid",android.os.Process.myUid());
            return caller;
        }
        private RuntimeProviderFacade getFacade(String authority,String missing){
            RuntimeProviderFacade facade=cachedFacades.get(authority);
            if(facade!=null&&!facade.isClosed())return facade;
            if(externalAuthorities.contains(authority)){if(missing!=null)throw new IllegalArgumentException(missing);return null;}
            synchronized(cachedFacades){
                facade=cachedFacades.get(authority);
                if(facade!=null&&!facade.isClosed())return facade;
                ContentProvider acquired=acquire(authority,true,false);
                if(acquired==null){
                    if(allowExternalPassthrough(authority))externalAuthorities.add(authority);
                    if(missing!=null)throw new IllegalArgumentException(missing);
                    return null;
                }
                if(acquired instanceof RuntimeProviderFacade){
                    facade=(RuntimeProviderFacade)acquired;
                    cachedFacades.put(authority,facade);
                    return facade;
                }
                return null;
            }
        }
        private void invalidate(String authority,RuntimeProviderFacade facade){
            synchronized(cachedFacades){if(cachedFacades.get(authority)==facade)cachedFacades.remove(authority);}
            try{facade.close();}catch(Throwable ignored){}
        }
        @Override public Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal){
            if(settings(uri)){if(signal!=null)throw unsupported();return engine.settingsProvider().query(uri,projection,args,signal);}
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.query(uri,projection,args,signal);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,null);
            if(facade==null){
                if(allowExternalPassthrough(auth))return engine.host.getContentResolver().query(uri,projection,args,signal);
                return null;
            }
            try{
                return facade.query(uri,projection,args,signal);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public String getType(Uri uri){
            if(settings(uri))return engine.settingsProvider().getType(uri);
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.getType(uri);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,null);
            if(facade==null){
                if(allowExternalPassthrough(auth))return engine.host.getContentResolver().getType(uri);
                return null;
            }
            try{
                return facade.getType(uri);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public Uri insert(Uri uri,ContentValues values,Bundle extras){
            if(settings(uri))return engine.settingsProvider().insert(uri,values,extras);
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.insert(uri,values,extras);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown URL "+uri);
            if(facade==null)return engine.host.getContentResolver().insert(uri,values,extras);
            try{
                return facade.insert(uri,values,extras);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public int update(Uri uri,ContentValues values,Bundle extras){
            if(settings(uri))return engine.settingsProvider().update(uri,values,extras);
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.update(uri,values,extras);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown URI "+uri);
            if(facade==null)return engine.host.getContentResolver().update(uri,values,extras);
            try{
                return facade.update(uri,values,extras);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public int delete(Uri uri,Bundle extras){
            if(settings(uri))return engine.settingsProvider().delete(uri,extras);
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.delete(uri,extras);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown URL "+uri);
            if(facade==null)return engine.host.getContentResolver().delete(uri,extras);
            try{
                return facade.delete(uri,extras);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public int bulkInsert(Uri uri,ContentValues[] values){
            if(settings(uri))return engine.settingsProvider().bulkInsert(uri,values);
            String auth=logical(uri);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.bulkInsert(uri,values);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown URL "+uri);
            if(facade==null)return engine.host.getContentResolver().bulkInsert(uri,values);
            try{
                return facade.bulkInsert(uri,values);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public ContentProviderResult[] applyBatch(String authority,ArrayList<ContentProviderOperation> operations)throws OperationApplicationException{
            if("settings".equals(authority))return engine.settingsProvider().applyBatch(authority,operations);
            String auth=RuntimeProviderBroker.authority(authority);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.applyBatch(authority,operations);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown authority "+authority);
            if(facade==null){
                try{return engine.host.getContentResolver().applyBatch(authority,operations);}
                catch(RemoteException error){throw new IllegalStateException("PROVIDER_BATCH_FAILED:"+authority,error);}
            }
            try{
                return facade.applyBatch(authority,operations);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public Bundle call(String authority,String method,String arg,Bundle extras){
            if("settings".equals(authority))return engine.settingsProvider().call(authority,method,arg,extras);
            String auth=RuntimeProviderBroker.authority(authority);
            ContentProvider local=findLocal(auth);
            if(local!=null){
                try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(local,localCaller())){
                    return local.call(authority,method,arg,extras);
                }
            }
            RuntimeProviderFacade facade=getFacade(auth,allowExternalPassthrough(auth)?null:"Unknown authority "+authority);
            if(facade==null)return engine.host.getContentResolver().call(authority,method,arg,extras);
            try{
                return facade.call(authority,method,arg,extras);
            }catch(RuntimeException error){
                if(facade.isClosed()||error.getCause() instanceof DeadObjectException)invalidate(auth,facade);
                throw error;
            }
        }
        @Override public Bundle call(String method,String arg,Bundle extras){throw new SecurityException("PROVIDER_AUTHORITY_REQUIRED");}
    }
}
