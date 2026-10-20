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
    private final Map<ServiceConnection,Binding> bindings=new IdentityHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Bundle> thinStubs=new java.util.concurrent.ConcurrentHashMap<>();
    RuntimeServices(RuntimeEngine engine){this.engine=engine;}
    static String stub(int process,int lane){return "dev.tripoder.runtime.RuntimeServiceSlots$S"+process+"_"+lane;}
    static int processIndex(String stub){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(stub))return p;throw new IllegalArgumentException("SERVICE_STUB_INVALID");}
    static boolean isStub(String name){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(name))return true;return false;}
    private static final class Identity {final String lease,stub,component,instance;Identity(Bundle b,String instance){lease=RuntimeWire.required(b,"lease");stub=RuntimeWire.required(b,"stub");component=RuntimeWire.required(b,"component");this.instance=instance;}}
    Service instantiate(String stub,Intent input)throws Exception{
        String instance=UUID.randomUUID().toString();Bundle lease;
        if(engine.mode==RuntimeMode.THIN){
            lease=thinStubs.get(stub);
            if(lease==null&&input!=null&&input.getBooleanExtra("tripoder.thinService",false)){
                String id=input.getStringExtra("tripoder.serviceLease"),comp=input.getStringExtra("tripoder.serviceComponent");
                if(id!=null&&comp!=null){lease=new Bundle();lease.putString("lease",id);lease.putString("stub",stub);lease.putString("component",comp);thinStubs.put(stub,lease);}
            }
            if(lease==null){Bundle request=engine.auth();request.putString("stub",stub);request.putString("instance",instance);lease=engine.brokerCall(RuntimeWire.SERVICE_CLAIM,request);thinStubs.put(stub,lease);}
        }else{
            Bundle request=engine.auth();request.putString("stub",stub);request.putString("instance",instance);lease=engine.brokerCall(RuntimeWire.SERVICE_CLAIM,request);
        }
        engine.startApplication();
        String name=RuntimeWire.required(lease,"component");Intent visible=input==null?null:new Intent(input).setComponent(new ComponentName(engine.packageName,name));
        if(visible!=null){visible.removeExtra("tripoder.serviceLease");visible.removeExtra("tripoder.serviceComponent");visible.removeExtra("tripoder.thinService");}
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
            Intent value=new Intent(input);value.removeExtra("tripoder.serviceLease");value.removeExtra("tripoder.serviceComponent");value.removeExtra("tripoder.thinService");value.setComponent(new ComponentName(engine.packageName,identity.component));value.setExtrasClassLoader(engine.guestLoader());return value;
        }
        // Not engine-routed: the guest invoked its own hooked service lifecycle directly -- a service
        // that re-posts onStartCommand to itself from a Handler (Taobao ACCS, embedded in 高德, does
        // this). The argument is already a plain guest intent bearing neither the stub nor the lease,
        // so present it as-is (with the guest classloader) rather than rejecting a legal in-process
        // self-call. Same UID: no trust boundary is crossed by an app calling its own public method.
        Intent value=new Intent(input);value.setExtrasClassLoader(engine.guestLoader());return value;
    }
    void lifecycle(Service service,String event)throws Exception{
        if(engine.mode==RuntimeMode.THIN){identity(service);return;}
        android.util.Log.i("TripoderService", "service lifecycle start: " + service.getClass().getName() + " event=" + event);
        long t0 = android.os.SystemClock.elapsedRealtime();
        Identity identity=identity(service);Bundle b=engine.auth();b.putString("lease",identity.lease);b.putString("instance",identity.instance);b.putString("event",event);engine.brokerCall(RuntimeWire.SERVICE_EVENT,b);
        android.util.Log.i("TripoderService", "service lifecycle done: " + service.getClass().getName() + " event=" + event + " took=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
    }
    private Identity identity(Service service){Identity id=instances.get(service);if(id==null)throw new SecurityException("SERVICE_INSTANCE_NOT_REGISTERED");return id;}
    private Intent snapshotForMode(Intent input)throws Exception{
        if(engine.mode!=RuntimeMode.THIN)return RuntimeServiceIntent.snapshot(input);
        Objects.requireNonNull(input,"SERVICE_INTENT_REQUIRED");
        if(input.getComponent()==null){
            ComponentName resolved=RuntimeBridge.resolveOwnService(input);
            if(resolved==null)return null;
            Intent copy=new Intent(input).setComponent(resolved);
            if(input.getExtras()!=null)copy.replaceExtras(new Bundle(input.getExtras()));
            return copy;
        }
        Intent copy=new Intent(input);if(input.getExtras()!=null)copy.replaceExtras(new Bundle(input.getExtras()));return copy;
    }
    ComponentName start(Context base,Intent intent)throws Exception{
        intent=snapshotForMode(intent);if(intent==null)return null;
        if(engine.mode==RuntimeMode.THIN&&!engine.packageName.equals(intent.getComponent().getPackageName())&&engine.serviceIndex.get(intent.getComponent().getClassName())==null){
            try{return base.startService(intent);}catch(Exception ignored){return null;}
        }
        Bundle route=route("start",intent,"");ComponentName physical=base.startService(physical(intent,route));boolean accepted=physical!=null;if(accepted&&!physical.equals(component(route)))throw new SecurityException("SERVICE_START_COMPONENT_MISMATCH");ack(route,accepted);return accepted?intent.getComponent():null;
    }
    boolean stop(Context base,Intent intent)throws Exception{
        intent=snapshotForMode(intent);if(intent==null)return false;
        if(engine.mode==RuntimeMode.THIN&&!engine.packageName.equals(intent.getComponent().getPackageName())&&engine.serviceIndex.get(intent.getComponent().getClassName())==null){
            try{return base.stopService(intent);}catch(Exception ignored){return false;}
        }
        Bundle route=route("stop",intent,"");if(route.getBoolean("absent"))return false;boolean stopped=base.stopService(physical(intent,route));ack(route,stopped);return stopped;
    }
    boolean bind(Context base,Intent intent,ServiceConnection original,int flags,Executor executor)throws Exception{
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
            if(engine.mode==RuntimeMode.THIN){
                ComponentName resolved=RuntimeBridge.resolveOwnService(intent);
                if(resolved!=null)intent=new Intent(intent).setComponent(resolved);
            }
            if(intent.getComponent()==null){
                if(intent.getPackage()==null&&intent.getSelector()==null)throw new IllegalArgumentException("Service Intent must be explicit: "+intent);
                return false;
            }
        }
        intent=snapshotForMode(intent);
        if(engine.mode!=RuntimeMode.THIN)RuntimeServiceBinding.validate(flags);
        Binding binding;
        synchronized(bindings){
            binding=bindings.get(original);
            if(binding!=null&&Objects.equals(binding.logical,intent.getComponent())){binding.count++;return true;}
            if(binding!=null)throw new IllegalArgumentException("SERVICE_CONNECTION_ALREADY_BOUND");
            binding=new Binding(original,intent.getComponent());
            bindings.put(original,binding);
        }
        Bundle route;try{route=route("bind",intent,binding.connection,flags);}catch(Exception error){synchronized(bindings){bindings.remove(original);}throw error;}
        binding.physical=component(route);binding.base=base;
        Intent phys=physical(intent,route);
        boolean bound=executor==null?base.bindService(phys,binding,flags):base.bindService(phys,flags,executor,binding);
        ack(route,bound);if(!bound)synchronized(bindings){bindings.remove(original);}return bound;
    }
    void unbind(ServiceConnection original)throws Exception{
        Binding binding;
        synchronized(bindings){
            binding=bindings.get(original);
            if(binding==null)throw new IllegalArgumentException("SERVICE_CONNECTION_NOT_BOUND");
            if(--binding.count>0)return;
            bindings.remove(original);
        }
        android.util.Log.i("TripoderService", "unbindService: " + binding.logical + " physical=" + binding.physical);
        if(engine.mode==RuntimeMode.THIN){
            binding.base.unbindService(binding);
            return;
        }
        Bundle route=route("unbind",null,binding.connection);
        binding.base.unbindService(binding);
        ack(route,true);
    }
    private Bundle route(String kind,Intent intent,String connection)throws Exception{
        return route(kind,intent,connection,0);
    }
    private Bundle route(String kind,Intent intent,String connection,int flags)throws Exception{
        Bundle request=engine.auth();request.putString("kind",kind);request.putString("connection",connection);request.putString("operation",UUID.randomUUID().toString());
        request.putInt("flags",flags);
        if(intent!=null){if(engine.mode!=RuntimeMode.THIN)request.putString("requestDigest",RuntimeServiceIntent.digest(intent));request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());}
        Bundle result=engine.brokerCall(RuntimeWire.SERVICE_ROUTE,request);
        if(engine.mode==RuntimeMode.THIN&&result.containsKey("stub")&&result.containsKey("lease")&&result.containsKey("component"))thinStubs.put(result.getString("stub"),result);
        return result;
    }
    private void ack(Bundle route,boolean applied)throws Exception{if(engine.mode==RuntimeMode.THIN)return;Bundle request=engine.auth();request.putString("operation",RuntimeWire.required(route,"operation"));request.putBoolean("applied",applied);engine.brokerCall(RuntimeWire.SERVICE_ACK,request);}
    private ComponentName component(Bundle route){return new ComponentName(engine.host,RuntimeWire.required(route,"stub"));}
    private Intent physical(Intent source,Bundle route){
        Intent out=new Intent(source).setComponent(component(route)).putExtra("tripoder.serviceLease",RuntimeWire.required(route,"lease"));
        if(engine.mode==RuntimeMode.THIN)out.putExtra("tripoder.thinService",true).putExtra("tripoder.serviceComponent",RuntimeWire.required(route,"component"));
        return out;
    }
    private final class Binding implements ServiceConnection {
        final String connection=UUID.randomUUID().toString();final ServiceConnection original;final ComponentName logical;ComponentName physical;Context base;int count=1;
        Binding(ServiceConnection original,ComponentName logical){this.original=Objects.requireNonNull(original);this.logical=Objects.requireNonNull(logical);}
        void check(ComponentName actual){if(!actual.equals(physical))throw new SecurityException("SERVICE_CONNECTION_COMPONENT_MISMATCH");}
        @Override public void onServiceConnected(ComponentName name,IBinder binder){check(name);original.onServiceConnected(logical,binder);}
        @Override public void onServiceDisconnected(ComponentName name){check(name);original.onServiceDisconnected(logical);}
        @Override public void onBindingDied(ComponentName name){check(name);original.onBindingDied(logical);}
        @Override public void onNullBinding(ComponentName name){check(name);original.onNullBinding(logical);}
    }
}
