package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.util.*;

/** Android owns the receiver instance, dispatcher and PendingResult; we own logical routing. */
final class RuntimeReceivers {
    private final RuntimeEngine engine;
    private final Map<BroadcastReceiver,Registration> receivers=new IdentityHashMap<>();
    private final Map<Object,Invocation> pending=new IdentityHashMap<>();
    private final Map<String,FinalDelivery> finals=new HashMap<>();
    private final ThreadLocal<Deque<Invocation>> current=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Registration {final String component,id;final boolean dynamic;final Set<String> systems;final java.util.concurrent.CountDownLatch activated=new java.util.concurrent.CountDownLatch(1);boolean closed;Registration(String component,String id,boolean dynamic){this(component,id,dynamic,Set.of());}Registration(String component,String id,boolean dynamic,Set<String> systems){this.component=component;this.id=id;this.dynamic=dynamic;this.systems=systems;if(!dynamic)activated.countDown();}}
    private final Set<String> retiredActions=new HashSet<>();
    private final Set<BroadcastReceiver> transitions=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Invocation {final BroadcastReceiver receiver;final String ticket,instance;final Object pending;boolean returned,finished,finalResult;Invocation(BroadcastReceiver r,String t,String i,Object p){receiver=r;ticket=t;instance=i;pending=p;}}
    private static final class FinalDelivery {final BroadcastReceiver receiver;final Intent logical;FinalDelivery(BroadcastReceiver r,Intent i){receiver=r;logical=i;}}
    RuntimeReceivers(RuntimeEngine engine){this.engine=engine;}
    synchronized boolean owns(BroadcastReceiver receiver){if(receivers.containsKey(receiver))return true;for(FinalDelivery result:finals.values())if(result.receiver==receiver)return true;return false;}
    BroadcastReceiver instantiate(Intent physical)throws Exception{
        String ticket=RuntimeBroadcastIntent.ticket(physical);Bundle b=engine.auth();b.putString("ticket",ticket);b.putBoolean("prepare",true);
        Bundle declaration=engine.brokerCall(RuntimeWire.BROADCAST_CLAIM,b);Intent logical=RuntimeBroadcastIntent.attachBinders(RuntimeBroadcastIntent.decode(declaration.getByteArray("payload")),declaration);String component=declaration.getString("component");
        engine.startApplication();
        BroadcastReceiver receiver=engine.componentFactory.instantiateReceiver(engine.guestLoader(),component,logical);
        if(receiver==null||!engine.ownsReceiverLoader(receiver.getClass().getClassLoader()))throw new SecurityException("RECEIVER_LOADER_MISMATCH");
        Registration registration=new Registration(component,ticket,false);
        synchronized(this){receivers.put(receiver,registration);}RuntimeBridge.installReceiverAdapter(receiver);return receiver;
    }
    /** Sticky broadcasts sent by this application. Android keeps them system-wide; in a guest only this application can send or see them, so the process-local
     *  latest-per-action store gives the same answers within one process (another process of the same app does not see it). */
    private final Map<String,Intent> appSticky=new LinkedHashMap<>();
    void sendSticky(Intent intent,String permission,Bundle options){
        if(intent==null||intent.getAction()==null)throw new IllegalArgumentException("STICKY_ACTION_REQUIRED");
        if(!engine.requestedPermissions.contains("android.permission.BROADCAST_STICKY"))throw new SecurityException("Requires BROADCAST_STICKY permission");
        synchronized(appSticky){appSticky.put(intent.getAction(),new Intent(intent));}
        send(intent,permission,options,false,null,null,0,null,null);
    }
    void removeSticky(Intent intent){
        if(!engine.requestedPermissions.contains("android.permission.BROADCAST_STICKY"))throw new SecurityException("Requires BROADCAST_STICKY permission");
        if(intent!=null&&intent.getAction()!=null)synchronized(appSticky){appSticky.remove(intent.getAction());}
    }
    private Intent appStickyFor(IntentFilter filter){
        synchronized(appSticky){for(Intent held:appSticky.values())if(filter.match(held.getAction(),held.getType(),held.getScheme(),held.getData(),held.getCategories(),"TripoderSticky")>=0)return new Intent(held);}
        return null;
    }
    Intent register(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags){
        Intent heldSticky=appStickyFor(filter);
        if(receiver==null&&heldSticky!=null)return heldSticky;
        Intent platformSticky=registerRegistered(receiver,filter,permission,scheduler,flags);
        return platformSticky!=null?platformSticky:heldSticky;
    }
    private Intent registerRegistered(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags){
        byte[] encoded=RuntimeBroadcastIntent.filter(filter);
        boolean legacyZeroFlags=flags==0&&engine.info!=null&&engine.info.targetSdkVersion<34;
        if(flags!=Context.RECEIVER_EXPORTED&&flags!=Context.RECEIVER_NOT_EXPORTED&&!legacyZeroFlags&&!(flags==0&&(RuntimeSystemBroadcasts.only(filter)||filter.countActions()==0)))throw new UnsupportedOperationException("RECEIVER_POLICY_UNSUPPORTED:flags=0x"+Integer.toHexString(flags)+";permission="+permission+";actions="+actions(filter));
        // Any known system action is observed by subscribing to the real platform broadcast on
        // the host. The platform is the source of truth whether or not the action is a protected
        // broadcast; refusing to even listen to a non-protected one (e.g. CLOSE_SYSTEM_DIALOGS,
        // which apps routinely register for) only turned listening into a crash. Guest-originated
        // sends of system actions stay blocked on the send path, so this is listen-only.
        Set<String> systems=new HashSet<>();for(int i=0;i<filter.countActions();i++){String action=filter.getAction(i);if(RuntimeSystemBroadcasts.ACTIONS.contains(action))systems.add(action);}
        if(receiver==null){
            if(permission!=null||filter.countActions()!=1||!RuntimeSystemBroadcasts.STICKY.contains(filter.getAction(0)))throw new UnsupportedOperationException("STICKY_QUERY_SHAPE_UNSUPPORTED");
            Bundle request=engine.auth();request.putBoolean("stickyQuery",true);request.putByteArray("filter",encoded);
            try{Bundle response=engine.brokerCall(RuntimeWire.BROADCAST_REGISTER,request);return response.containsKey("payload")?RuntimeBroadcastIntent.decode(response.getByteArray("payload")):null;}catch(Exception error){throw new IllegalStateException("STICKY_QUERY_FAILED",error);}
        }
        if(!engine.ownsReceiverLoader(receiver.getClass().getClassLoader()))throw new SecurityException("RECEIVER_LOADER_MISMATCH:"+receiver.getClass().getName()+"@"+receiver.getClass().getClassLoader()+";webview="+engine.webView.ownsProviderLoader(receiver.getClass().getClassLoader()));
        synchronized(this){if(transitions.contains(receiver))throw new IllegalStateException("RECEIVER_REGISTRATION_IN_PROGRESS");Registration prior=receivers.get(receiver);if(prior!=null&&!prior.closed)throw new UnsupportedOperationException("RECEIVER_MULTIPLE_FILTERS_NOT_IMPLEMENTED");if(receivers.size()+retiredActions.size()>=4096)throw new IllegalStateException("RECEIVER_REGISTRATION_LIMIT");transitions.add(receiver);}
        Bundle b=engine.auth();b.putByteArray("filter",encoded);b.putBoolean("exported",flags==Context.RECEIVER_EXPORTED||(legacyZeroFlags&&!RuntimeSystemBroadcasts.only(filter)&&filter.countActions()>0));b.putString("permission",permission);
        try{
            Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_REGISTER,b);String id=admitted.getString("registration");Registration registration=new Registration("",id,true,Set.copyOf(systems));
            Bundle ack=engine.auth();ack.putString("registration",id);
            boolean registered=false;Intent stickyReturn=null;try{
                // Never hold the registry monitor while entering the JavaScript adapter.
                // Publish only after installation, so an incoming callback cannot wait
                // for activation while blocking the adapter that activates it.
                RuntimeBridge.installReceiverAdapter(receiver);synchronized(this){receivers.put(receiver,registration);}
                // Logical payloads travel inside an authority ticket, so their private
                // physical action has no URI. System events keep Android's real filter.
                Intent sticky=engine.host.registerReceiver(receiver,new IntentFilter(admitted.getString("action")),null,scheduler,Context.RECEIVER_NOT_EXPORTED);registered=true;
                if(sticky!=null)throw new IllegalStateException("UNEXPECTED_STICKY_PRIVATE_BROADCAST");
                if(!systems.isEmpty()){
                    IntentFilter physical=new IntentFilter();for(String action:systems)physical.addAction(action);
                    for(int i=0;i<filter.countDataSchemes();i++)physical.addDataScheme(filter.getDataScheme(i));
                    stickyReturn=engine.host.registerReceiver(receiver,physical,null,scheduler,Context.RECEIVER_NOT_EXPORTED);
                }
                ack.putString("state","ACTIVE");engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,ack);
                if(stickyReturn==null)return null;
                Bundle view=engine.auth();view.putString("registration",id);view.putByteArray("payload",RuntimeSystemBroadcasts.observation(stickyReturn));return RuntimeBroadcastIntent.decode(engine.brokerCall(RuntimeWire.BROADCAST_STICKY_VIEW,view).getByteArray("payload"));
            }catch(Exception error){synchronized(this){registration.closed=true;retiredActions.add(BroadcastLedger.action(id));}if(registered)engine.host.unregisterReceiver(receiver);if(!registered){ack.putBoolean("failed",true);ack.putString("state","CLOSED");engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,ack);}throw error;}
            finally{registration.activated.countDown();}
        }catch(Exception error){throw new IllegalStateException("RECEIVER_REGISTER_FAILED",error);}
        finally{synchronized(this){transitions.remove(receiver);}}
    }
    void unregister(BroadcastReceiver receiver){Registration registration;synchronized(this){if(transitions.contains(receiver))throw new IllegalStateException("RECEIVER_REGISTRATION_IN_PROGRESS");registration=receivers.get(receiver);if(registration==null||!registration.dynamic||registration.closed)throw new IllegalArgumentException("RECEIVER_NOT_REGISTERED");transitions.add(receiver);registration.closed=true;retiredActions.add(BroadcastLedger.action(registration.id));}
        try{engine.host.unregisterReceiver(receiver);Bundle b=engine.auth();b.putString("registration",registration.id);b.putString("state","CLOSED");engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,b);}
        catch(Exception error){throw new IllegalStateException("RECEIVER_UNREGISTER_UNCONFIRMED",error);}
        finally{synchronized(this){transitions.remove(receiver);}}
    }
    void send(Intent logical,String permission,Bundle options,boolean ordered,BroadcastReceiver result,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){
        if(options!=null)throw new UnsupportedOperationException("BROADCAST_OPTIONS_UNSUPPORTED");
        Bundle extras=RuntimeProviderFacade.primitive(initialExtras);byte[] payload;
        Intent bytesOnly=new Intent(logical);Bundle binders=RuntimeBroadcastIntent.detachBinders(bytesOnly);
        try{payload=RuntimeBroadcastIntent.encode(bytesOnly);}
        catch(RuntimeBroadcastIntent.BinderPayload binder){
            // Not delivering is what an app with no matching receiver observes; throwing would take down whichever thread merely announced something.
            android.util.Log.w("TripoderBroadcast","broadcast not delivered: its extras carry a Binder, which this runtime cannot hold for later delivery (action="+logical.getAction()+")");return;
        }
        Bundle b=engine.auth();b.putByteArray("payload",payload);if(binders!=null)b.putBundle("binders",binders);b.putBoolean("ordered",ordered);b.putString("operation",UUID.randomUUID().toString());b.putString("receiverPermission",permission);
        try{
            ArrayList<Bundle> routed=engine.brokerCall(RuntimeWire.BROADCAST_ROUTE,b).getParcelableArrayList("deliveries",Bundle.class);
            for(Bundle delivery:routed){String ticket=delivery.getString("ticket");Intent physical=new Intent().setPackage(engine.host.getPackageName()).setFlags(logical.getFlags()).putExtra("tripoder.delivery",ticket);
                if(delivery.containsKey("stub"))physical.setComponent(new ComponentName(engine.host,delivery.getString("stub")));else physical.setAction(delivery.getString("action"));
                if(!ordered){engine.host.sendBroadcast(physical);continue;}
                BroadcastReceiver finalReceiver=result;
                if(finalReceiver==null)finalReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){event(ticket,"","systemDone");}};
                else{if(!engine.ownsReceiverLoader(finalReceiver.getClass().getClassLoader()))throw new SecurityException("RECEIVER_LOADER_MISMATCH");synchronized(this){finals.put(ticket,new FinalDelivery(finalReceiver,RuntimeBroadcastIntent.decode(payload)));}RuntimeBridge.installReceiverAdapter(finalReceiver);}
                engine.host.sendOrderedBroadcast(physical,null,finalReceiver,scheduler,initialCode,initialData,extras);
            }
        }catch(Exception error){throw new IllegalStateException("BROADCAST_SEND_UNCONFIRMED",error);}
    }
    Intent before(BroadcastReceiver receiver,Intent physical,Object token){
        if(token==null)throw new SecurityException("ANDROID_PENDING_RESULT_REQUIRED");
        String ticket=RuntimeSystemBroadcasts.action(physical.getAction())?null:RuntimeBroadcastIntent.ticket(physical);
        FinalDelivery finalDelivery;synchronized(this){finalDelivery=ticket==null?null:finals.get(ticket);}
        if(finalDelivery!=null){
            if(finalDelivery.receiver!=receiver)throw new SecurityException("BROADCAST_FINAL_RECEIVER_MISMATCH");event(ticket,"","systemDone");
            synchronized(this){finals.remove(ticket);}Invocation invocation=new Invocation(receiver,ticket,"",token);invocation.finalResult=true;current.get().push(invocation);return new Intent(finalDelivery.logical);
        }
        Registration registration;synchronized(this){registration=receivers.get(receiver);if(registration!=null&&registration.closed)return null;if(registration!=null&&registration.dynamic&&retiredActions.contains(physical.getAction()))return null;}
        if(registration==null)throw new SecurityException("RECEIVER_NOT_OWNED");
        try{if(!registration.activated.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("RECEIVER_ACTIVATION_UNCONFIRMED");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("RECEIVER_ACTIVATION_INTERRUPTED",error);}
        synchronized(this){if(registration.closed)return null;}
        if(registration.dynamic&&RuntimeSystemBroadcasts.action(physical.getAction())&&registration.systems.contains(physical.getAction())){
            if(receiver.isOrderedBroadcast())throw new UnsupportedOperationException("ORDERED_SYSTEM_BROADCAST_UNSUPPORTED");
            if(!RuntimeSystemBroadcasts.ownPackageEvent(physical,engine.packageName))return null;
            String instance=UUID.randomUUID().toString();Bundle b=engine.auth();b.putString("registration",registration.id);b.putString("instance",instance);b.putByteArray("payload",RuntimeSystemBroadcasts.observation(physical));
            synchronized(this){if(pending.containsKey(token))throw new SecurityException("PENDING_RESULT_REUSED");}
            try{Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_SYSTEM_CLAIM,b);if(admitted.getBoolean("rejectedClosedRegistration"))return null;Invocation invocation=new Invocation(receiver,RuntimeWire.required(admitted,"ticket"),instance,token);synchronized(this){pending.put(token,invocation);}current.get().push(invocation);return RuntimeBroadcastIntent.decode(admitted.getByteArray("payload"));}
            catch(Exception error){throw new IllegalStateException("SYSTEM_RECEIVER_CLAIM_FAILED",error);}
        }
        if(ticket==null)throw new SecurityException("SYSTEM_RECEIVER_NOT_REGISTERED");
        if(registration.dynamic?!BroadcastLedger.action(registration.id).equals(physical.getAction()):!registration.id.equals(ticket))throw new SecurityException("BROADCAST_RECEIVER_MISMATCH");
        String instance=UUID.randomUUID().toString();Bundle b=engine.auth();b.putString("ticket",ticket);b.putString("component",registration.component);b.putString("registration",registration.dynamic?registration.id:"");b.putString("instance",instance);
        b.putBoolean("observedOrdered",receiver.isOrderedBroadcast());
        try{Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_CLAIM,b);if(admitted.getBoolean("rejectedClosedRegistration"))return null;if(admitted.getBoolean("ordered")!=receiver.isOrderedBroadcast())throw new SecurityException("BROADCAST_ORDERED_MISMATCH");Invocation invocation=new Invocation(receiver,ticket,instance,token);synchronized(this){if(pending.put(token,invocation)!=null)throw new SecurityException("PENDING_RESULT_REUSED");}current.get().push(invocation);return RuntimeBroadcastIntent.attachBinders(RuntimeBroadcastIntent.decode(admitted.getByteArray("payload")),admitted);}
        catch(Exception error){throw new IllegalStateException("RECEIVER_CLAIM_FAILED",error);}
    }
    void after(BroadcastReceiver receiver,boolean success){Invocation invocation=current.get().poll();if(invocation==null||invocation.receiver!=receiver)throw new SecurityException("RECEIVER_CALLBACK_STACK_MISMATCH");if(current.get().isEmpty())current.remove();if(invocation.finalResult)return;
        event(invocation.ticket,invocation.instance,success?"returned":"failed");synchronized(this){invocation.returned=true;cleanup(invocation);}
    }
    void asynchronous(BroadcastReceiver receiver,Object token){Invocation invocation=current.get().peek();if(invocation==null||invocation.receiver!=receiver)throw new SecurityException("RECEIVER_ASYNC_OUTSIDE_CALLBACK");if(invocation.finalResult)return;if(invocation.pending!=token)throw new SecurityException("PENDING_RESULT_IDENTITY_MISMATCH");event(invocation.ticket,invocation.instance,"asynchronous");}
    void finished(Object token){Invocation invocation;synchronized(this){invocation=pending.get(token);}if(invocation==null)return;event(invocation.ticket,invocation.instance,"finished");synchronized(this){invocation.finished=true;pending.remove(token);cleanup(invocation);}}
    private void cleanup(Invocation invocation){Registration registration=receivers.get(invocation.receiver);if(invocation.returned&&invocation.finished&&registration!=null&&!registration.dynamic)receivers.remove(invocation.receiver);}
    private void event(String ticket,String instance,String event){Bundle b=engine.auth();b.putString("ticket",ticket);b.putString("instance",instance);b.putString("event",event);try{engine.brokerCall(RuntimeWire.BROADCAST_EVENT,b);}catch(Exception error){throw new IllegalStateException("RECEIVER_EVENT_UNCONFIRMED:"+event,error);}}
    private static String actions(IntentFilter filter){if(filter==null)return "null";StringBuilder result=new StringBuilder();for(int i=0;i<Math.min(filter.countActions(),8);i++){if(i>0)result.append(',');String action=filter.getAction(i);result.append(action.substring(0,Math.min(action.length(),256)));}return result.toString();}
}
