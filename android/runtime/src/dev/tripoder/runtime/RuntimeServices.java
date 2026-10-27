package dev.tripoder.runtime;

import android.app.Service;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Guest-side component translation. All guest callbacks and Binder handles are Android-driven. */
final class RuntimeServices {
    static final int PER_PROCESS=16;
    private final RuntimeEngine engine;
    private final Map<Service,Identity> instances=new WeakHashMap<>();
    /** One guest connection object may hold several platform bindings, as one IServiceConnection holds several ConnectionRecords. */
    private final Map<ServiceConnection,List<Binding>> bindings=new IdentityHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Bundle> thinStubs=new java.util.concurrent.ConcurrentHashMap<>();
    RuntimeServices(RuntimeEngine engine){this.engine=engine;}
    static String stub(int process,int lane){return "dev.tripoder.runtime.RuntimeServiceSlots$S"+process+"_"+lane;}
    static int processIndex(String stub){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(stub))return p;throw new IllegalArgumentException("SERVICE_STUB_INVALID");}
    static boolean isStub(String name){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(name))return true;return false;}
    private static final class Identity {final String lease,stub,component,instance;Identity(Bundle b,String instance){lease=RuntimeWire.required(b,"lease");stub=RuntimeWire.required(b,"stub");component=RuntimeWire.required(b,"component");this.instance=instance;}}
    Service instantiate(String stub,Intent input)throws Exception{
        String instance=UUID.randomUUID().toString();Bundle lease;
        {
            lease=thinStubs.get(stub);
            if(lease==null&&input!=null&&input.getBooleanExtra("tripoder.thinService",false)){
                String id=input.getStringExtra("tripoder.serviceLease"),comp=input.getStringExtra("tripoder.serviceComponent");
                if(id!=null&&comp!=null){lease=new Bundle();lease.putString("lease",id);lease.putString("stub",stub);lease.putString("component",comp);thinStubs.put(stub,lease);}
            }
            if(lease==null){Bundle request=engine.auth();request.putString("stub",stub);request.putString("instance",instance);lease=engine.brokerCall(RuntimeWire.SERVICE_CLAIM,request);thinStubs.put(stub,lease);}
        }
        engine.startApplication();
        String name=RuntimeWire.required(lease,"component");Intent visible=null;
        if(input!=null){
            {visible=new Intent(input).setComponent(new ComponentName(engine.packageName,name));visible.removeExtra("tripoder.serviceLease");visible.removeExtra("tripoder.serviceComponent");visible.removeExtra("tripoder.thinService");}
        }
        android.util.Log.i("TripoderService", "instantiate stub=" + stub + " component=" + name);
        Service service=engine.componentFactory.instantiateService(engine.guestLoader(),name,visible);if(service==null||!engine.ownsLoader(service.getClass().getClassLoader()))throw new SecurityException("SERVICE_LOADER_MISMATCH");
        instances.put(service,new Identity(lease,instance));RuntimeBridge.installServiceAdapter(service);return service;
    }
    boolean owns(Service service){return instances.containsKey(service);}
    ComponentName physical(Service service){return new ComponentName(engine.host,identity(service).stub);}
    Intent visible(Service service,Intent input){if(input==null)return null;Identity identity=identity(service);
        boolean stub=new ComponentName(engine.host,identity.stub).equals(input.getComponent());boolean leased=identity.lease.equals(input.getStringExtra("tripoder.serviceLease"));
        if(stub||leased){
            // Engine-routed framework delivery: the physical stub component and the per-instance lease are both present. Strip them and present the guest-visible intent. Half a pair is an inconsistent (spoofed) delivery.
            if(!stub)throw new SecurityException("SERVICE_INTENT_STUB_MISMATCH");if(!leased)throw new SecurityException("SERVICE_INTENT_LEASE_MISMATCH");
            {}
            Intent value=new Intent(input);value.removeExtra("tripoder.serviceLease");value.removeExtra("tripoder.serviceComponent");value.removeExtra("tripoder.thinService");value.setComponent(new ComponentName(engine.packageName,identity.component));value.setExtrasClassLoader(engine.guestLoader());return value;
        }
        // Not engine-routed: the guest invoked its own hooked service lifecycle directly -- a service
        // that re-posts onStartCommand to itself from a Handler (Taobao ACCS, embedded in 高德, does
        // this). The argument is already a plain guest intent bearing neither the stub nor the lease,
        // so present it as-is (with the guest classloader) rather than rejecting a legal in-process
        // self-call. Same UID: no trust boundary is crossed by an app calling its own public method.
        Intent value=new Intent(input);value.setExtrasClassLoader(engine.guestLoader());return value;
    }
    private Intent restored(Intent physical,String component){
        return RuntimeServiceIntent.delivery(physical,new ComponentName(engine.packageName,component),engine.guestLoader());
    }
    void lifecycle(Service service,String event)throws Exception{
        {identity(service);return;}

    }
    private Identity identity(Service service){Identity id=instances.get(service);if(id==null)throw new SecurityException("SERVICE_INSTANCE_NOT_REGISTERED");return id;}
    private RuntimeServiceIntent.Request snapshotForPlatform(Intent input)throws Exception{
        {}
        Objects.requireNonNull(input,"SERVICE_INTENT_REQUIRED");
        if(input.getComponent()==null){
            ComponentName resolved=RuntimeBridge.resolveOwnService(input);
            if(resolved==null)return null;
            Intent copy=new Intent(input).setComponent(resolved);
            if(input.getExtras()!=null)copy.replaceExtras(new Bundle(input.getExtras()));
            return RuntimeServiceIntent.thin(copy);
        }
        Intent copy=new Intent(input);if(input.getExtras()!=null)copy.replaceExtras(new Bundle(input.getExtras()));return RuntimeServiceIntent.thin(copy);
    }
    ComponentName start(Context base,Intent intent)throws Exception{
        RuntimeServiceIntent.Request request=snapshotForPlatform(intent);if(request==null)return null;intent=request.intent;
        if(!engine.packageName.equals(intent.getComponent().getPackageName())&&engine.serviceIndex.get(intent.getComponent().getClassName())==null){
            try{return base.startService(intent);}catch(Exception ignored){return null;}
        }
        Bundle route=route("start",intent,"",0,request.digest);if(route.getBoolean("absent"))return null;ComponentName physical=base.startService(physical(request,route));boolean accepted=physical!=null;if(accepted&&!physical.equals(component(route)))throw new SecurityException("SERVICE_START_COMPONENT_MISMATCH");ack(route,accepted);return accepted?intent.getComponent():null;
    }
    boolean stop(Context base,Intent intent)throws Exception{
        RuntimeServiceIntent.Request request=snapshotForPlatform(intent);if(request==null)return false;intent=request.intent;
        if(!engine.packageName.equals(intent.getComponent().getPackageName())&&engine.serviceIndex.get(intent.getComponent().getClassName())==null){
            try{return base.stopService(intent);}catch(Exception ignored){return false;}
        }
        Bundle route=route("stop",intent,"",0,request.digest);if(route.getBoolean("absent"))return false;boolean stopped=base.stopService(physical(request,route));ack(route,stopped);return stopped;
    }
    boolean bind(Context base,Intent intent,ServiceConnection original,int flags,Executor executor)throws Exception{
        return bind(base,intent,original,flags,executor,null);
    }
    boolean bindIsolated(Context base,Intent intent,ServiceConnection original,int flags,Executor executor,String instance)throws Exception{
        return bind(base,intent,original,flags,Objects.requireNonNull(executor),Objects.requireNonNull(instance));
    }
    private boolean bind(Context base,Intent intent,ServiceConnection original,int flags,Executor executor,String instance)throws Exception{
        if(original==null)throw new IllegalArgumentException("connection is null");
        android.util.Log.i("TripoderService", "bindService: " + (intent != null ? intent.getComponent() : null) + " flags=0x" + Integer.toHexString(flags));
        // Android resolves a service bind to exactly one concrete component. An intent aimed at
        // another package (action + setPackage, e.g. com.xiaomi.account or com.tencent.soter) that
        // declares no matching service simply resolves to nothing, which the platform reports by
        // returning false -- it only throws "must be explicit" when neither component nor package is
        // set. The guest service model routes only explicit guest-world components, so any bind
        // without one is an unresolved external bind: return false to match the platform instead of
        // letting the shape check throw on the caller's worker thread (SDKs probing for an optional
        // system service do not catch that and self-kill, which is what crashed 高德 at login).
        if(intent!=null&&intent.getComponent()==null){
            {
                ComponentName resolved=RuntimeBridge.resolveOwnService(intent);
                if(resolved!=null)intent=new Intent(intent).setComponent(resolved);
            }
            if(intent.getComponent()==null){
                if(intent.getPackage()==null&&intent.getSelector()==null)throw new IllegalArgumentException("Service Intent must be explicit: "+intent);
                // ContextImpl allocates its ServiceDispatcher before asking AMS
                // to resolve the service. A false result still requires one
                // unbind to forget that local registration; no callback is sent.
                synchronized(bindings){var held=bindings.computeIfAbsent(original,key->new ArrayList<>());if(held.isEmpty())held.add(new Binding(original,null));}
                return false;
            }
        }
        RuntimeServiceIntent.Request request=snapshotForPlatform(intent);intent=request.intent;
        {}
        Binding binding;
        synchronized(bindings){
            var held=bindings.computeIfAbsent(original,key->new ArrayList<>());
            held.removeIf(existing->!existing.platformRegistered);
            for(Binding existing:held)if(Objects.equals(existing.logical,intent.getComponent())&&Objects.equals(existing.isolatedInstance,instance))return existing.bound;
            binding=new Binding(original,intent.getComponent(),instance);
            held.add(binding);
        }
        final Binding attempted=binding;
        Bundle route;try{route=route("bind",intent,binding.connection,flags,request.digest,instance);}catch(Exception error){synchronized(bindings){forget(original,attempted);}throw error;}
        // ContextImpl retains its local dispatcher even when AMS resolves no
        // service. Keep this connection until one unbind, without a lease/RPC.
        if(route.getBoolean("absent"))return false;
        binding.physical=component(route);binding.base=base;
        Intent phys=physical(request,route);
        binding.platformRegistered=true;binding.routed=true;binding.isolated=route.getBoolean("isolated");
        boolean bound=binding.isolated?base.bindIsolatedService(phys,flags,RuntimeWire.required(route,"isolatedPhysicalInstance"),executor==null?base.getMainExecutor():executor,binding):executor==null?base.bindService(phys,binding,flags):base.bindService(phys,flags,executor,binding);
        binding.bound=bound;ack(route,bound);if(!bound)binding.routed=false;return bound;
    }
    /** Both act on the connection the platform actually holds for this guest connection. */
    void rebind(ServiceConnection original,Context.BindServiceFlags flags){for(Binding binding:registered(original))binding.base.rebindService(binding,flags);}
    void updateGroup(ServiceConnection original,int group,int importance){for(Binding binding:registered(original))binding.base.updateServiceGroup(binding,group,importance);}
    private List<Binding> registered(ServiceConnection original){
        synchronized(bindings){
            var live=new ArrayList<Binding>();
            for(Binding binding:bindings.getOrDefault(Objects.requireNonNull(original),List.of()))if(binding.platformRegistered&&binding.routed&&binding.base!=null)live.add(binding);
            if(live.isEmpty())throw new IllegalArgumentException("ServiceConnection not currently bound: "+original);
            return live;
        }
    }
    private void forget(ServiceConnection original,Binding binding){var held=bindings.get(original);if(held!=null){held.remove(binding);if(held.isEmpty())bindings.remove(original);}}
    /** Like the platform, one unbindService releases every binding this connection object holds. */
    void unbind(ServiceConnection original)throws Exception{
        List<Binding> held;
        synchronized(bindings){
            held=bindings.remove(original);
            if(held==null||held.isEmpty())throw new IllegalArgumentException("SERVICE_CONNECTION_NOT_BOUND");
        }
        Exception first=null;
        for(Binding binding:held)try{release(binding);}catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}
        if(first!=null)throw first;
    }
    private void release(Binding binding)throws Exception{
        android.util.Log.i("TripoderService", "unbindService: " + binding.logical + " physical=" + binding.physical);
        if(!binding.platformRegistered)return;
        if(!binding.routed){binding.base.unbindService(binding);return;}
        if(!binding.isolated){
            binding.base.unbindService(binding);
            return;
        }
        Bundle route=route("unbind",null,binding.connection,0,null);
        binding.base.unbindService(binding);
        ack(route,true);
    }
    private Bundle route(String kind,Intent intent,String connection,int flags,String digest)throws Exception{
        return route(kind,intent,connection,flags,digest,null);
    }
    private Bundle route(String kind,Intent intent,String connection,int flags,String digest,String instance)throws Exception{
        Bundle request=engine.auth();request.putString("kind",kind);request.putString("connection",connection);request.putString("operation",UUID.randomUUID().toString());
        request.putInt("flags",flags);
        if(instance!=null)request.putString("isolatedInstance",instance);
        // Whether the target runs isolated is the authority's decision; it needs this process's resolved library graph then.
        if(kind.equals("bind")){Bundle libraries=RuntimeIsolatedLibraries.describe(engine.sharedLibraries);if(libraries!=null)request.putBundle("platformLibraries",libraries);}
        if(intent!=null){{}request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());}
        Bundle result=engine.brokerCall(RuntimeWire.SERVICE_ROUTE,request);
        if(!result.getBoolean("isolated")&&result.containsKey("stub")&&result.containsKey("lease")&&result.containsKey("component"))thinStubs.put(result.getString("stub"),result);
        return result;
    }
    private void ack(Bundle route,boolean applied)throws Exception{if(!route.getBoolean("isolated"))return;Bundle request=engine.auth();request.putString("operation",RuntimeWire.required(route,"operation"));request.putBoolean("applied",applied);engine.brokerCall(RuntimeWire.SERVICE_ACK,request);}
    private ComponentName component(Bundle route){return new ComponentName(engine.host,RuntimeWire.required(route,"stub"));}
    private Intent physical(RuntimeServiceIntent.Request request,Bundle route)throws Exception{
        Intent out=new Intent(request.intent).setComponent(component(route)).putExtra("tripoder.serviceLease",RuntimeWire.required(route,"lease"));
        if(route.getBoolean("isolated")){Bundle capability=new Bundle();capability.putBinder(RuntimeIsolatedService.AUTHORITY,Objects.requireNonNull(route.getBinder("isolatedAuthority")));out.putExtras(capability).putExtra("tripoder.generation",RuntimeWire.required(route,"generation")).putExtra(RuntimeServiceIntent.PAYLOAD,RuntimeServiceIntent.snapshot(request.intent).payload());}
        else out.putExtra("tripoder.thinService",true).putExtra("tripoder.serviceComponent",RuntimeWire.required(route,"component"));
        return out;
    }
    private final class Binding implements ServiceConnection {
        final String connection=UUID.randomUUID().toString();final ServiceConnection original;final ComponentName logical;final String isolatedInstance;ComponentName physical;Context base;boolean platformRegistered,routed,bound,isolated;
        Binding(ServiceConnection original,ComponentName logical){this(original,logical,null);}
        Binding(ServiceConnection original,ComponentName logical,String isolatedInstance){this.original=Objects.requireNonNull(original);this.logical=logical;this.isolatedInstance=isolatedInstance;}
        void check(ComponentName actual){if(!actual.equals(physical))throw new SecurityException("SERVICE_CONNECTION_COMPONENT_MISMATCH");}
        @Override public void onServiceConnected(ComponentName name,IBinder binder){check(name);original.onServiceConnected(logical,binder);}
        @Override public void onServiceDisconnected(ComponentName name){check(name);original.onServiceDisconnected(logical);}
        @Override public void onBindingDied(ComponentName name){check(name);original.onBindingDied(logical);}
        @Override public void onNullBinding(ComponentName name){check(name);original.onNullBinding(logical);}
    }
}
