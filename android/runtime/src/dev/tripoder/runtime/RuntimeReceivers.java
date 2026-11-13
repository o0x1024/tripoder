package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.util.*;

/** Android owns the receiver instance, dispatcher and PendingResult; we own logical routing. */
final class RuntimeReceivers {
    private final RuntimeEngine engine;
    private final java.util.concurrent.atomic.AtomicBoolean stickyObserverAnnounced=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<BroadcastReceiver,Registration> receivers=new IdentityHashMap<>();
    private final Map<Object,Invocation> pending=new IdentityHashMap<>();
    private final Map<String,FinalDelivery> finals=new HashMap<>();
    private final ThreadLocal<Deque<Invocation>> current=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Registration {
        final String component,id,receiverKey;final boolean dynamic;final Set<String> systems;final IntentFilter filter;
        final List<Registration> entries=new ArrayList<>();
        final java.util.concurrent.CountDownLatch activated=new java.util.concurrent.CountDownLatch(1);boolean closed,active;
        RuntimeReceiverProxy physicalProxy;
        RuntimeReceiverProxy systemProxy;
        Registration(String component,String id,boolean dynamic){this(component,id,dynamic,Set.of(),null,id);}
        Registration(String component,String id,boolean dynamic,Set<String> systems,IntentFilter filter,String key){this.component=component;this.id=id;this.dynamic=dynamic;this.systems=systems;this.filter=filter==null?null:new IntentFilter(filter);receiverKey=key;entries.add(this);if(!dynamic)activated.countDown();}
        Registration select(Intent intent){
            if(!dynamic)return this;
            for(Registration entry:entries)if(!entry.closed&&(BroadcastLedger.action(entry.id).equals(intent.getAction())||entry.systems.contains(intent.getAction())&&RuntimeBroadcastIntent.matches(entry.filter,intent)))return entry;
            return null;
        }
    }

    private final Set<String> retiredActions=new HashSet<>();
    private final Set<BroadcastReceiver> transitions=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Invocation {final BroadcastReceiver receiver;final String ticket,instance;final Object pending;boolean returned,finished,finalResult,ordered;Invocation(BroadcastReceiver r,String t,String i,Object p){receiver=r;ticket=t;instance=i;pending=p;}}
    private static final class FinalDelivery {
        final BroadcastReceiver receiver;final Intent logical;final boolean authorityTicket;
        FinalDelivery(BroadcastReceiver r,Intent i){this(r,i,true);}
        FinalDelivery(BroadcastReceiver r,Intent i,boolean authorityTicket){receiver=r;logical=i;this.authorityTicket=authorityTicket;}
    }
    RuntimeReceivers(RuntimeEngine engine){this.engine=engine;}
    private RuntimeReceiverProxy proxy(BroadcastReceiver receiver){
        return new RuntimeReceiverProxy(receiver,new RuntimeReceiverProxy.Delivery(){
            public Intent before(BroadcastReceiver guest,Intent physical,Object pending){return RuntimeReceivers.this.before(guest,physical,pending);}
            public Context context(Context physical){return RuntimeBridge.context(physical);}
            public void after(BroadcastReceiver guest,boolean success){RuntimeReceivers.this.after(guest,success);}
        });
    }
    boolean platformContext(Context context){
        Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(context!=null&&seen.size()<16&&seen.add(context)){
            if(context instanceof GuestContext)return false;
            if(context==engine.host)return true;
            if(engine.host instanceof ContextWrapper&&context==((ContextWrapper)engine.host).getBaseContext())return true;
            context=context instanceof ContextWrapper?((ContextWrapper)context).getBaseContext():null;
        }return false;
    }
    synchronized boolean owns(BroadcastReceiver receiver){if(receivers.containsKey(receiver))return true;for(FinalDelivery result:finals.values())if(result.receiver==receiver)return true;return false;}
    BroadcastReceiver instantiate(Intent physical)throws Exception{
        String ticket=RuntimeBroadcastIntent.ticket(physical);Bundle b=engine.auth();b.putString("ticket",ticket);b.putBoolean("prepare",true);
        Bundle declaration=engine.brokerCall(RuntimeWire.BROADCAST_CLAIM,b);Intent logical=RuntimeBroadcastIntent.attachBinders(RuntimeBroadcastIntent.decode(declaration.getByteArray("payload")),declaration);String component=declaration.getString("component");
        engine.startApplication();
        BroadcastReceiver receiver=engine.componentFactory.instantiateReceiver(engine.guestLoader(),component,logical);
        if(receiver==null||!engine.ownsReceiverLoader(receiver.getClass().getClassLoader()))throw new SecurityException("RECEIVER_LOADER_MISMATCH");
        Registration registration=new Registration(component,ticket,false);
        synchronized(this){receivers.put(receiver,registration);}return proxy(receiver);
    }
    /** Sticky broadcasts sent by this application. Android keeps them system-wide; in a guest only this application can send or see them, so the process-local
     *  latest-per-action store gives the same answers within one process (another process of the same app does not see it). */
    private final Map<String,Intent> appSticky=new LinkedHashMap<>();
    void sendSticky(Intent intent,String permission,Bundle options){
        if(intent==null||intent.getAction()==null)throw new IllegalArgumentException("STICKY_ACTION_REQUIRED");
        if(!engine.requestedPermissions.contains("android.permission.BROADCAST_STICKY"))throw new SecurityException("Requires BROADCAST_STICKY permission");
        Prepared prepared=prepare(new Intent(intent),permission,options,false,null,null,0,null,null);
        if(prepared==null)return;
        // Retain only the captured, validated view, before Android can call a
        // receiver. Rejected preparation never publishes a sticky observation.
        synchronized(appSticky){appSticky.put(prepared.logical.getAction(),new Intent(prepared.logical));}
        try{dispatch(prepared);}
        catch(RuntimeException refusal){
            if(RuntimeFacadeContract.platformAnswer(refusal))throw refusal;
            RuntimeFacadeContract.drop("STICKY_BROADCAST_DROPPED",refusal);
        }
    }
    void removeSticky(Intent intent){
        if(!engine.requestedPermissions.contains("android.permission.BROADCAST_STICKY"))throw new SecurityException("Requires BROADCAST_STICKY permission");
        if(intent!=null&&intent.getAction()!=null)synchronized(appSticky){appSticky.remove(intent.getAction());}
    }
    private Intent appStickyFor(IntentFilter filter){
        java.util.Objects.requireNonNull(filter,"RECEIVER_FILTER_REQUIRED");
        if(filter.countActions()==0)return null;
        synchronized(appSticky){for(Intent held:appSticky.values())if(filter.match(held.getAction(),held.getType(),held.getScheme(),held.getData(),held.getCategories(),"TripoderSticky")>=0)return new Intent(held);}
        return null;
    }
    Intent register(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags){
        if(engine.traceExit&&stickyObserverAnnounced.compareAndSet(false,true))android.util.Log.i("TripoderDeviceSticky","observer active");
        Intent heldSticky=appStickyFor(filter);
        if(receiver==null&&heldSticky!=null){observeDeviceSticky(filter,heldSticky);return heldSticky;}
        Intent platformSticky=registerRegistered(receiver,filter,permission,scheduler,flags);
        Intent result=platformSticky!=null?platformSticky:heldSticky;
        if(receiver==null)observeDeviceSticky(filter,result);
        return result;
    }
    /* Fault-diagnostic builds only: retain the platform's exact sticky intent
     * and report just the USB/battery flags needed to diagnose device reads. */
    private void observeDeviceSticky(IntentFilter filter,Intent sticky){
        try{
            if(!engine.traceExit||filter==null||filter.countActions()!=1)return;
            String action=filter.getAction(0);
            boolean usb="android.hardware.usb.action.USB_STATE".equals(action);
            if(!usb&&!Intent.ACTION_BATTERY_CHANGED.equals(action))return;
            String value=sticky==null?"null":usb?("connected="+sticky.getBooleanExtra("connected",false)+" configured="+sticky.getBooleanExtra("configured",false)):("plugged="+sticky.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,-1));
            android.util.Log.i("TripoderDeviceSticky","action="+action+" "+value);
        }catch(Throwable ignored){/* Diagnostics must never change guest behavior. */}
    }
    private Intent registerRegistered(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags){
        if(receiver!=null&&dev.tripoder.selfhook.SystemReceiverAccess.ownsType(receiver.getClass()))filter=dev.tripoder.selfhook.SystemReceiverAccess.registrationFilter(receiver.getClass(),filter,permission,scheduler,flags);
        byte[] encoded=RuntimeBroadcastIntent.filter(filter);
        Set<String> systems=RuntimeSystemBroadcasts.discover(filter);
        int physicalFlags=receiver==null?flags:RuntimeBroadcastPolicy.flags(flags,engine.info==null?34:engine.info.targetSdkVersion,RuntimeSystemBroadcasts.only(filter));
        if(receiver==null){
            if(filter.countActions()==0)return null;
            if(permission==null&&filter.countActions()==1&&RuntimeSystemBroadcasts.STICKY.contains(filter.getAction(0))){
                Bundle request=engine.auth();request.putBoolean("stickyQuery",true);request.putByteArray("filter",encoded);
                try{
                    Bundle response=engine.brokerCall(RuntimeWire.BROADCAST_REGISTER,request);
                    if(response.containsKey("payload"))return RuntimeBroadcastIntent.decode(response.getByteArray("payload"));
                }catch(Exception error){RuntimeFacadeContract.drop("STICKY_QUERY_FAILED",error);}
            }
            try{
                return engine.host.registerReceiver(null,filter);
            }catch(Throwable ignored){
                return null;
            }
        }
        if(!engine.ownsReceiverType(receiver.getClass()))throw new SecurityException("RECEIVER_LOADER_MISMATCH:"+receiver.getClass().getName()+"@"+receiver.getClass().getClassLoader()+";webview="+engine.webView.ownsProviderLoader(receiver.getClass().getClassLoader()));
        Registration prior;String receiverKey;
        synchronized(this){if(transitions.contains(receiver)){RuntimeFacadeContract.drop("RECEIVER_REGISTRATION_IN_PROGRESS",null);return null;}prior=receivers.get(receiver);if(prior!=null&&prior.closed)prior=null;receiverKey=prior==null?UUID.randomUUID().toString():prior.receiverKey;int retained=retiredActions.size();for(Registration entry:receivers.values())retained+=entry.entries.size();if(retained>=4096){RuntimeFacadeContract.drop("RECEIVER_REGISTRATION_LIMIT",null);return null;}transitions.add(receiver);}
        Bundle b=engine.auth();b.putString("receiverKey",receiverKey);b.putByteArray("filter",encoded);boolean exportedFlag=RuntimeBroadcastPolicy.exported(physicalFlags);b.putBoolean("exported",exportedFlag);b.putString("permission",permission);
        RuntimeMalwareProbeState.recordReceiverRegistration(receiver.getClass().getName(),filter,exportedFlag);
        try{
            Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_REGISTER,b);String id=admitted.getString("registration");Registration registration=new Registration("",id,true,Set.copyOf(systems),filter,receiverKey);
            Bundle ack=engine.auth();ack.putString("registration",id);
            Intent stickyReturn=null;try{
                RuntimeReceiverProxy physicalReceiver=proxy(receiver);
                registration.physicalProxy=physicalReceiver;
                synchronized(this){Registration root=receivers.get(receiver);if(root==null||root.closed)receivers.put(receiver,registration);else root.entries.add(registration);}
                // Logical payloads travel inside an authority ticket, so their private
                // physical action has no URI. System events keep Android's real filter.
                Intent sticky=engine.host.registerReceiver(physicalReceiver,new IntentFilter(admitted.getString("action")),null,scheduler,Context.RECEIVER_NOT_EXPORTED);
                // The private action is a fresh authority token and carries only ticketed deliveries, so a sticky
                // returned here can never be a logical payload. Failing the guest's registerReceiver for it (as
                // before) aborted static initializers such as Tencent Video's LoginDataStore; record and discard.
                if(sticky!=null)android.util.Log.w("TripoderReceiver","UNEXPECTED_STICKY_PRIVATE_BROADCAST ignored action="+admitted.getString("action")+" sticky="+sticky);
                if(!systems.isEmpty()){
                    IntentFilter physical=RuntimeReceiverFilters.system(filter,systems);
                    RuntimeReceiverProxy systemReceiver=proxy(receiver);
                    registration.systemProxy=systemReceiver;
                    // OEM senders can run under privileged non-system UIDs.
                    // Preserve the guest's flags and sender-permission guard;
                    // the ticket transport remains separately NOT_EXPORTED.
                    stickyReturn=engine.host.registerReceiver(systemReceiver,physical,permission,scheduler,physicalFlags);
                }
                ack.putString("state","ACTIVE");engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,ack);registration.active=true;
                if(stickyReturn==null)return null;
                Bundle view=engine.auth();view.putString("registration",id);view.putByteArray("payload",RuntimeSystemBroadcasts.observation(stickyReturn));return RuntimeBroadcastIntent.decode(engine.brokerCall(RuntimeWire.BROADCAST_STICKY_VIEW,view).getByteArray("payload"));
            }catch(Exception error){try{rollbackRegistration(receiver,registration);}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
            finally{registration.activated.countDown();}
        }catch(Exception error){RuntimeFacadeContract.drop("RECEIVER_REGISTER_FAILED",error);return null;}
        finally{synchronized(this){transitions.remove(receiver);}}
    }
    /** A failed additional filter must not retire this receiver's earlier entries. */
    private void rollbackRegistration(BroadcastReceiver receiver,Registration entry)throws Exception{
        synchronized(this){entry.closed=true;retiredActions.add(BroadcastLedger.action(entry.id));Registration root=receivers.get(receiver);if(root!=null&&root!=entry)root.entries.remove(entry);}
        if(entry.physicalProxy!=null)try{engine.host.unregisterReceiver(entry.physicalProxy);}catch(IllegalArgumentException ignored){}
        if(entry.systemProxy!=null)try{engine.host.unregisterReceiver(entry.systemProxy);}catch(IllegalArgumentException ignored){}
        Bundle request=engine.auth();request.putString("registration",entry.id);request.putString("state","CLOSED");if(!entry.active)request.putBoolean("failed",true);engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,request);
    }
    void unregister(BroadcastReceiver receiver){
        synchronized(this){if(transitions.contains(receiver)){RuntimeFacadeContract.drop("RECEIVER_REGISTRATION_IN_PROGRESS",null);return;}Registration root=receivers.get(receiver);if(root==null||!root.dynamic||root.closed)throw new IllegalArgumentException("RECEIVER_NOT_REGISTERED");transitions.add(receiver);}
        try{closeRegistered(receiver,true);}
        catch(Exception error){RuntimeFacadeContract.drop("RECEIVER_UNREGISTER_UNCONFIRMED",error);}
        finally{synchronized(this){transitions.remove(receiver);}}
    }
    private void closeRegistered(BroadcastReceiver receiver,boolean physical)throws Exception{
        List<Registration> entries;
        synchronized(this){Registration root=receivers.get(receiver);if(root==null)return;entries=new ArrayList<>(root.entries);for(Registration entry:entries){entry.closed=true;retiredActions.add(BroadcastLedger.action(entry.id));}}
        if(physical){
            for(Registration entry:entries){
                if(entry.physicalProxy!=null){
                    try{engine.host.unregisterReceiver(entry.physicalProxy);}catch(Exception ignored){}
                }
                if(entry.systemProxy!=null){
                    try{engine.host.unregisterReceiver(entry.systemProxy);}catch(Exception ignored){}
                }
            }
        }
        for(Registration entry:entries){Bundle request=engine.auth();request.putString("registration",entry.id);request.putString("state","CLOSED");if(!entry.active)request.putBoolean("failed",true);engine.brokerCall(RuntimeWire.BROADCAST_REGISTRATION,request);}
    }

    private record Prepared(Intent logical,ArrayList<Bundle> deliveries,Bundle initialExtras,boolean ordered,BroadcastReceiver result,Handler scheduler,int initialCode,String initialData){}
    void send(Intent logical,String permission,Bundle options,boolean ordered,BroadcastReceiver result,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){
        if(logical==null)throw new IllegalArgumentException("BROADCAST_INTENT_REQUIRED");
        String targetPkg=logical!=null?logical.getPackage():null;
        if(targetPkg==null&&logical!=null&&logical.getComponent()!=null)targetPkg=logical.getComponent().getPackageName();
        if(targetPkg!=null&&!engine.packageName.equals(targetPkg)){
            try{
                if(ordered)engine.host.sendOrderedBroadcast(logical,permission,result!=null?proxy(result):null,scheduler,initialCode,initialData,initialExtras);
                else engine.host.sendBroadcast(logical,permission);
            }catch(Exception ignored){}
            return;
        }
        try{
            Prepared prepared=prepare(logical,permission,options,ordered,result,scheduler,initialCode,initialData,initialExtras);
            if(prepared!=null)dispatch(prepared);
        }catch(RuntimeException refusal){
            // A guest that calls Context.sendBroadcast survives every runtime limitation on the platform, so it
            // must survive ours: only an answer the platform itself gives (SecurityException, or a cause-less
            // IllegalArgumentException naming a bad argument) may reach the caller. Anything else is recorded
            // and dropped here instead of killing the guest process (BOSS直聘 一键登录, 2026-11-13).
            if(RuntimeFacadeContract.platformAnswer(refusal))throw refusal;
            RuntimeFacadeContract.drop("BROADCAST_DROPPED",refusal);
        }
    }
    /** Returns null with the reason already recorded when this broadcast cannot be carried at all. */
    private Prepared prepare(Intent logical,String permission,Bundle options,boolean ordered,BroadcastReceiver result,Handler scheduler,int initialCode,String initialData,Bundle initialExtras){
        Objects.requireNonNull(logical,"BROADCAST_INTENT_REQUIRED");
        // The platform accepts BroadcastOptions; refusing them would be a refusal the platform never gives.
        if(options!=null){RuntimeFacadeContract.drop("BROADCAST_OPTIONS_IGNORED",null);options=null;}
        if(result!=null){
            if(!ordered)throw new IllegalArgumentException("BROADCAST_RESULT_REQUIRES_ORDERED");
            if(!engine.ownsReceiverLoader(result.getClass().getClassLoader()))throw new SecurityException("RECEIVER_LOADER_MISMATCH");
        }
        Bundle extras=RuntimeProviderFacade.primitive(initialExtras);byte[] payload;
        Intent bytesOnly=new Intent(logical);Bundle binders=RuntimeBroadcastIntent.detachBinders(bytesOnly);
        // A Binder extra or an oversized payload is reduced to the nearest encodable intent instead of refused.
        payload=RuntimeBroadcastIntent.encodeReduced(bytesOnly);
        if(payload==null)return null;
        Bundle b=engine.auth();b.putByteArray("payload",payload);if(binders!=null)b.putBundle("binders",binders);b.putBoolean("ordered",ordered);b.putString("operation",UUID.randomUUID().toString());b.putString("receiverPermission",permission);
        Intent captured=RuntimeBroadcastIntent.attachBinders(RuntimeBroadcastIntent.decode(payload),b);
        try{
            ArrayList<Bundle> routed=engine.brokerCall(RuntimeWire.BROADCAST_ROUTE,b).getParcelableArrayList("deliveries",Bundle.class);
            return new Prepared(captured,Objects.requireNonNull(routed,"BROADCAST_DELIVERIES_REQUIRED"),extras,ordered,result,scheduler,initialCode,initialData);
        }catch(Exception error){throw new IllegalStateException("BROADCAST_PREPARATION_UNCONFIRMED",error);}
    }
    private void dispatch(Prepared prepared){
        try{
            if(prepared.ordered&&prepared.deliveries.isEmpty()){
                // The final receiver is independent of matching delivery targets.
                // Let Android supply its real ordered PendingResult and scheduler.
                if(prepared.result!=null){
                    String ticket=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
                    synchronized(this){finals.put(ticket,new FinalDelivery(prepared.result,new Intent(prepared.logical),false));}
                    Intent empty=new Intent(engine.host.getPackageName()+".EMPTY_ORDERED."+ticket)
                        .setPackage(engine.host.getPackageName()).setFlags(prepared.logical.getFlags()).putExtra("tripoder.delivery",ticket);
                    try{engine.host.sendOrderedBroadcast(empty,null,proxy(prepared.result),prepared.scheduler,prepared.initialCode,prepared.initialData,prepared.initialExtras);}
                    catch(RuntimeException error){synchronized(this){finals.remove(ticket);}throw error;}
                }
                return;
            }
            if(prepared.ordered&&prepared.deliveries.size()>1){
                RuntimeOrderedBroadcastChain.start(engine,prepared.logical,prepared.deliveries,prepared.initialCode,prepared.initialData,prepared.initialExtras,
                    (code,data,extras)->dispatch(new Prepared(prepared.logical,new ArrayList<>(),extras,true,prepared.result,prepared.scheduler,code,data)));
                return;
            }
            for(Bundle delivery:prepared.deliveries){String ticket=delivery.getString("ticket");Intent physical=new Intent().setPackage(engine.host.getPackageName()).setFlags(prepared.logical.getFlags()).putExtra("tripoder.delivery",ticket);
                if(delivery.containsKey("stub"))physical.setComponent(new ComponentName(engine.host,delivery.getString("stub")));else physical.setAction(delivery.getString("action"));
                if(!prepared.ordered){engine.host.sendBroadcast(physical);continue;}
                BroadcastReceiver finalReceiver=prepared.result;
                if(finalReceiver==null)finalReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){event(ticket,"","systemDone");}};
                else{synchronized(this){finals.put(ticket,new FinalDelivery(finalReceiver,new Intent(prepared.logical)));}finalReceiver=proxy(finalReceiver);}
                engine.host.sendOrderedBroadcast(physical,null,finalReceiver,prepared.scheduler,prepared.initialCode,prepared.initialData,prepared.initialExtras);
            }
        }catch(Exception error){throw new IllegalStateException("BROADCAST_SEND_UNCONFIRMED",error);}
    }
    Intent before(BroadcastReceiver receiver,Intent physical,Object token){
        if(token==null)throw new SecurityException("ANDROID_PENDING_RESULT_REQUIRED");
        String ticket=RuntimeSystemBroadcasts.action(physical.getAction())?null:RuntimeBroadcastIntent.ticket(physical);
        FinalDelivery finalDelivery;synchronized(this){finalDelivery=ticket==null?null:finals.get(ticket);}
        if(finalDelivery!=null&&dev.tripoder.selfhook.ComponentFrameworkAccess.pendingKind(token)==2){
            if(finalDelivery.receiver!=receiver)throw new SecurityException("BROADCAST_FINAL_RECEIVER_MISMATCH");
            if(finalDelivery.authorityTicket)event(ticket,"","systemDone");
            synchronized(this){finals.remove(ticket);}Invocation invocation=new Invocation(receiver,ticket,"",token);invocation.finalResult=true;current.get().push(invocation);return new Intent(finalDelivery.logical);
        }
        Registration registration;synchronized(this){Registration root=receivers.get(receiver);if(root!=null&&root.closed)return null;if(root!=null&&root.dynamic&&retiredActions.contains(physical.getAction()))return null;registration=root==null?null:root.select(physical);}
        if(registration==null){RuntimeFacadeContract.drop("RECEIVER_NOT_OWNED",null);return physical;}
        try{if(!registration.activated.await(5,java.util.concurrent.TimeUnit.SECONDS)){RuntimeFacadeContract.drop("RECEIVER_ACTIVATION_UNCONFIRMED",null);return physical;}}
        catch(InterruptedException error){Thread.currentThread().interrupt();RuntimeFacadeContract.drop("RECEIVER_ACTIVATION_INTERRUPTED",error);return physical;}
        synchronized(this){if(registration.closed)return null;}
        if(registration.dynamic&&RuntimeSystemBroadcasts.action(physical.getAction())&&registration.systems.contains(physical.getAction())){
            if(receiver.isOrderedBroadcast())throw new UnsupportedOperationException("ORDERED_SYSTEM_BROADCAST_UNSUPPORTED");
            if(!RuntimeSystemBroadcasts.ownPackageEvent(physical,engine.packageName))return null;
            if(RuntimeSystemBroadcasts.SELF_PACKAGE.contains(physical.getAction())&&!engine.host.getPackageName().equals(physical.getPackage()))return null;
            String instance=UUID.randomUUID().toString();Bundle b=engine.auth();b.putString("registration",registration.id);b.putString("instance",instance);b.putByteArray("payload",RuntimeSystemBroadcasts.observation(physical));
            synchronized(this){if(pending.containsKey(token))throw new SecurityException("PENDING_RESULT_REUSED");}
            try{Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_SYSTEM_CLAIM,b);if(admitted.getBoolean("rejectedClosedRegistration"))return null;Invocation invocation=new Invocation(receiver,RuntimeWire.required(admitted,"ticket"),instance,token);synchronized(this){pending.put(token,invocation);}current.get().push(invocation);return RuntimeBroadcastIntent.decode(admitted.getByteArray("payload"));}
            catch(Exception error){throw new IllegalStateException("SYSTEM_RECEIVER_CLAIM_FAILED",error);}
        }
        if(ticket==null)throw new SecurityException("SYSTEM_RECEIVER_NOT_REGISTERED");
        if(registration.dynamic?!BroadcastLedger.action(registration.id).equals(physical.getAction()):!registration.id.equals(ticket))throw new SecurityException("BROADCAST_RECEIVER_MISMATCH");
        String instance=UUID.randomUUID().toString();Bundle b=engine.auth();b.putString("ticket",ticket);b.putString("component",registration.component);b.putString("registration",registration.dynamic?registration.id:"");b.putString("instance",instance);
        b.putBoolean("observedOrdered",receiver.isOrderedBroadcast());
        try{Bundle admitted=engine.brokerCall(RuntimeWire.BROADCAST_CLAIM,b);if(admitted.getBoolean("rejectedClosedRegistration"))return null;if(admitted.getBoolean("ordered")!=receiver.isOrderedBroadcast())throw new SecurityException("BROADCAST_ORDERED_MISMATCH");Invocation invocation=new Invocation(receiver,ticket,instance,token);invocation.ordered=admitted.getBoolean("ordered");synchronized(this){if(pending.put(token,invocation)!=null)throw new SecurityException("PENDING_RESULT_REUSED");}current.get().push(invocation);return RuntimeBroadcastIntent.attachBinders(RuntimeBroadcastIntent.decode(admitted.getByteArray("payload")),admitted);}
        catch(Exception error){throw new IllegalStateException("RECEIVER_CLAIM_FAILED",error);}
    }
    void after(BroadcastReceiver receiver,boolean success){Invocation invocation=current.get().poll();if(invocation==null||invocation.receiver!=receiver){RuntimeFacadeContract.drop("RECEIVER_CALLBACK_STACK_MISMATCH",null);if(current.get().isEmpty())current.remove();return;}if(current.get().isEmpty())current.remove();if(invocation.finalResult)return;
        event(invocation.ticket,invocation.instance,success?"returned":"failed");synchronized(this){invocation.returned=true;cleanup(invocation);}
    }
    void asynchronous(BroadcastReceiver receiver,Object token){Invocation invocation=current.get().peek();if(invocation==null||invocation.receiver!=receiver)throw new SecurityException("RECEIVER_ASYNC_OUTSIDE_CALLBACK");if(invocation.finalResult)return;if(invocation.pending!=token)throw new SecurityException("PENDING_RESULT_IDENTITY_MISMATCH");event(invocation.ticket,invocation.instance,"asynchronous");}
    void finishing(Object token){
        Invocation invocation;synchronized(this){invocation=pending.get(token);}
        if(invocation==null||invocation.finalResult||!invocation.ordered)return;
        Bundle request=engine.auth();request.putString("ticket",invocation.ticket);request.putString("instance",invocation.instance);request.putString("event","orderedResult");
        request.putBoolean("aborted",((BroadcastReceiver.PendingResult)token).getAbortBroadcast());
        try{engine.brokerCall(RuntimeWire.BROADCAST_EVENT,request);}catch(Exception error){throw new IllegalStateException("BROADCAST_RESULT_UNCONFIRMED",error);}
    }
    void finished(Object token){Invocation invocation;synchronized(this){invocation=pending.get(token);}if(invocation==null)return;event(invocation.ticket,invocation.instance,"finished");synchronized(this){invocation.finished=true;pending.remove(token);cleanup(invocation);}}
    private void cleanup(Invocation invocation){Registration registration=receivers.get(invocation.receiver);if(invocation.returned&&invocation.finished&&registration!=null&&!registration.dynamic)receivers.remove(invocation.receiver);}
    private void event(String ticket,String instance,String event){Bundle b=engine.auth();b.putString("ticket",ticket);b.putString("instance",instance);b.putString("event",event);try{engine.brokerCall(RuntimeWire.BROADCAST_EVENT,b);}catch(Exception error){throw new IllegalStateException("RECEIVER_EVENT_UNCONFIRMED:"+event,error);}}
    private static String actions(IntentFilter filter){if(filter==null)return "null";StringBuilder result=new StringBuilder();for(int i=0;i<Math.min(filter.countActions(),8);i++){if(i>0)result.append(',');String action=filter.getAction(i);result.append(action.substring(0,Math.min(action.length(),256)));}return result.toString();}
}
