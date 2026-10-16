package dev.tripoder.runtime;

import android.app.Service;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Guest-side component translation. All guest callbacks and Binder handles are Android-driven. */
final class RuntimeServices {
    static final int PER_PROCESS=4;
    private final RuntimeEngine engine;
    private final Map<Service,Identity> instances=new WeakHashMap<>();
    private final Map<ServiceConnection,Binding> bindings=new IdentityHashMap<>();
    RuntimeServices(RuntimeEngine engine){this.engine=engine;}
    static String stub(int process,int lane){return "dev.tripoder.runtime.RuntimeServiceSlots$S"+process+"_"+lane;}
    static int processIndex(String stub){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(stub))return p;throw new IllegalArgumentException("SERVICE_STUB_INVALID");}
    static boolean isStub(String name){for(int p=0;p<RuntimeSlot.CAPACITY;p++)for(int s=0;s<PER_PROCESS;s++)if(stub(p,s).equals(name))return true;return false;}
    private static final class Identity {final String lease,stub,component,instance;Identity(Bundle b,String instance){lease=RuntimeWire.required(b,"lease");stub=RuntimeWire.required(b,"stub");component=RuntimeWire.required(b,"component");this.instance=instance;}}
    Service instantiate(String stub,Intent input)throws Exception{
        String instance=UUID.randomUUID().toString();Bundle request=engine.auth();request.putString("stub",stub);request.putString("instance",instance);Bundle lease=engine.brokerCall(RuntimeWire.SERVICE_CLAIM,request);engine.startApplication();
        String name=RuntimeWire.required(lease,"component");Intent visible=input==null?null:new Intent(input).setComponent(new ComponentName(engine.packageName,name));if(visible!=null)visible.removeExtra("tripoder.serviceLease");
        Service service=engine.componentFactory.instantiateService(engine.loader,name,visible);if(service==null||!engine.ownsLoader(service.getClass().getClassLoader()))throw new SecurityException("SERVICE_LOADER_MISMATCH");
        instances.put(service,new Identity(lease,instance));RuntimeBridge.installServiceAdapter(service);return service;
    }
    boolean owns(Service service){return instances.containsKey(service);}
    ComponentName physical(Service service){return new ComponentName(engine.host,identity(service).stub);}
    Intent visible(Service service,Intent input){if(input==null)return null;Identity identity=identity(service);if(input.getComponent()==null||!input.getComponent().equals(physical(service)))throw new SecurityException("SERVICE_INTENT_STUB_MISMATCH");if(!identity.lease.equals(input.getStringExtra("tripoder.serviceLease")))throw new SecurityException("SERVICE_INTENT_LEASE_MISMATCH");Intent value=new Intent(input);value.removeExtra("tripoder.serviceLease");value.setComponent(new ComponentName(engine.packageName,identity.component));value.setExtrasClassLoader(engine.loader);return value;}
    void lifecycle(Service service,String event)throws Exception{Identity identity=identity(service);Bundle b=engine.auth();b.putString("lease",identity.lease);b.putString("instance",identity.instance);b.putString("event",event);engine.brokerCall(RuntimeWire.SERVICE_EVENT,b);}
    private Identity identity(Service service){Identity id=instances.get(service);if(id==null)throw new SecurityException("SERVICE_INSTANCE_NOT_REGISTERED");return id;}
    ComponentName start(Context base,Intent intent)throws Exception{intent=RuntimeServiceIntent.snapshot(intent);Bundle route=route("start",intent,"");ComponentName physical=base.startService(physical(intent,route));boolean accepted=physical!=null;if(accepted&&!physical.equals(component(route)))throw new SecurityException("SERVICE_START_COMPONENT_MISMATCH");ack(route,accepted);return accepted?intent.getComponent():null;}
    boolean stop(Context base,Intent intent)throws Exception{intent=RuntimeServiceIntent.snapshot(intent);Bundle route=route("stop",intent,"");if(route.getBoolean("absent"))return false;boolean stopped=base.stopService(physical(intent,route));ack(route,stopped);return stopped;}
    boolean bind(Context base,Intent intent,ServiceConnection original,int flags,Executor executor)throws Exception{
        intent=RuntimeServiceIntent.snapshot(intent);
        RuntimeServiceBinding.validate(flags);
        Binding binding=new Binding(original,intent.getComponent());synchronized(bindings){if(bindings.containsKey(original))throw new IllegalArgumentException("SERVICE_CONNECTION_ALREADY_BOUND");bindings.put(original,binding);}
        Bundle route;try{route=route("bind",intent,binding.connection,flags);}catch(Exception error){synchronized(bindings){bindings.remove(original);}throw error;}
        binding.physical=component(route);binding.base=base;
        boolean bound=executor==null?base.bindService(physical(intent,route),binding,flags):base.bindService(physical(intent,route),flags,executor,binding);
        ack(route,bound);if(!bound)synchronized(bindings){bindings.remove(original);}return bound;
    }
    void unbind(ServiceConnection original)throws Exception{Binding binding;synchronized(bindings){binding=bindings.get(original);}if(binding==null)throw new IllegalArgumentException("SERVICE_CONNECTION_NOT_BOUND");Bundle route=route("unbind",null,binding.connection);binding.base.unbindService(binding);ack(route,true);synchronized(bindings){bindings.remove(original);}}
    private Bundle route(String kind,Intent intent,String connection)throws Exception{
        return route(kind,intent,connection,0);
    }
    private Bundle route(String kind,Intent intent,String connection,int flags)throws Exception{
        Bundle request=engine.auth();request.putString("kind",kind);request.putString("connection",connection);request.putString("operation",UUID.randomUUID().toString());
        request.putInt("flags",flags);
        if(intent!=null){request.putString("requestDigest",RuntimeServiceIntent.digest(intent));request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());}
        return engine.brokerCall(RuntimeWire.SERVICE_ROUTE,request);
    }
    private void ack(Bundle route,boolean applied)throws Exception{Bundle request=engine.auth();request.putString("operation",RuntimeWire.required(route,"operation"));request.putBoolean("applied",applied);engine.brokerCall(RuntimeWire.SERVICE_ACK,request);}
    private ComponentName component(Bundle route){return new ComponentName(engine.host,RuntimeWire.required(route,"stub"));}
    private Intent physical(Intent source,Bundle route){return new Intent(source).setComponent(component(route)).putExtra("tripoder.serviceLease",RuntimeWire.required(route,"lease"));}
    private final class Binding implements ServiceConnection {
        final String connection=UUID.randomUUID().toString();final ServiceConnection original;final ComponentName logical;ComponentName physical;Context base;
        Binding(ServiceConnection original,ComponentName logical){this.original=Objects.requireNonNull(original);this.logical=Objects.requireNonNull(logical);}
        void check(ComponentName actual){if(!actual.equals(physical))throw new SecurityException("SERVICE_CONNECTION_COMPONENT_MISMATCH");}
        @Override public void onServiceConnected(ComponentName name,IBinder binder){check(name);original.onServiceConnected(logical,binder);}
        @Override public void onServiceDisconnected(ComponentName name){check(name);original.onServiceDisconnected(logical);}
        @Override public void onBindingDied(ComponentName name){check(name);original.onBindingDied(logical);}
        @Override public void onNullBinding(ComponentName name){check(name);original.onNullBinding(logical);}
    }
}
