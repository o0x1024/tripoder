package dev.tripoder.runtime;

import android.content.*;
import android.os.Bundle;
import java.util.*;

/** Matching is local to the caller's space; raw logical actions never reach Android. */
final class RuntimeBroadcastBroker {
    private final RuntimeBroker broker;
    RuntimeBroadcastBroker(RuntimeBroker broker){this.broker=broker;}
    Bundle handle(int code,Bundle input,String generation)throws Exception{
        if(code==RuntimeWire.BROADCAST_ROUTE)return route(input,broker.contract.getSession(generation));
        return handleTransaction(code,input,generation);
    }
    private Bundle handleTransaction(int code,Bundle input,String generation)throws Exception{
        BroadcastLedger ledger=broker.broadcasts;Bundle out=new Bundle();var source=broker.contract.getSession(generation);
        if(code==RuntimeWire.BROADCAST_REGISTER){
            byte[] filter=input.getByteArray("filter");IntentFilter declared=RuntimeBroadcastIntent.filter(filter);
            if(input.getBoolean("stickyQuery")){
                if(declared.countActions()!=1||!RuntimeSystemBroadcasts.STICKY.contains(declared.getAction(0))||input.getString("permission")!=null)throw new UnsupportedOperationException("STICKY_QUERY_SHAPE_UNSUPPORTED");
                Intent sticky=broker.host.registerReceiver(null,declared,null,null,Context.RECEIVER_NOT_EXPORTED);
                if(sticky!=null){if(!RuntimeBroadcastIntent.matches(declared,sticky))throw new SecurityException("STICKY_QUERY_FILTER_MISMATCH");out.putByteArray("payload",RuntimeSystemBroadcasts.encode(sticky,Set.of()));}return out;
            }
            // A receiver may guard with any permission (its delivery restriction is enforced
            // in resolve(); an unowned one simply never matches a sender), so registration is
            // not pre-rejected for an unowned permission.
            boolean media=false;for(int i=0;i<declared.countActions();i++)media|=RuntimeSystemBroadcasts.MEDIA.contains(declared.getAction(i));
            Set<String> roots=media?RuntimeSystemBroadcasts.roots(broker.host):Set.of();
            String id=ledger.registration(source,filter,input.getBoolean("exported"),roots,input.getString("permission"));out.putString("registration",id);out.putString("action",BroadcastLedger.action(id));return out;
        }
        if(code==RuntimeWire.BROADCAST_STICKY_VIEW){
            String registration=RuntimeWire.required(input,"registration");Intent sticky=RuntimeBroadcastIntent.decode(input.getByteArray("payload"));
            if(!RuntimeSystemBroadcasts.STICKY.contains(sticky.getAction())||!RuntimeBroadcastIntent.matches(ledger.activeParsedFilter(registration,generation),sticky))throw new SecurityException("STICKY_RETURN_FILTER_MISMATCH");
            out.putByteArray("payload",RuntimeSystemBroadcasts.encode(sticky,ledger.activeRoots(registration,generation)));return out;
        }
        if(code==RuntimeWire.BROADCAST_SYSTEM_CLAIM){
            String registration=RuntimeWire.required(input,"registration"),instance=RuntimeWire.required(input,"instance");
            if(!ledger.registrationActive(registration,generation)){out.putBoolean("rejectedClosedRegistration",true);return out;}
            Set<String> roots=ledger.activeRoots(registration,generation);
            Intent observedIntent=RuntimeBroadcastIntent.decode(input.getByteArray("payload"));boolean media=observedIntent.getAction()!=null&&RuntimeSystemBroadcasts.MEDIA.contains(observedIntent.getAction());
            if(media){Set<String> observed=new HashSet<>(roots);observed.addAll(RuntimeSystemBroadcasts.roots(broker.host));roots=Set.copyOf(observed);}
            Intent logical=RuntimeSystemBroadcasts.visible(observedIntent,roots);
            if(!RuntimeSystemBroadcasts.ownPackageEvent(logical,source.identity.packageName))throw new SecurityException("PACKAGE_EVENT_NOT_VISIBLE");
            if(!RuntimeBroadcastIntent.matches(ledger.activeParsedFilter(registration,generation),logical))throw new SecurityException("SYSTEM_RECEIVER_FILTER_MISMATCH");
            if(media)ledger.rememberRoots(registration,generation,roots);
            byte[] payload=RuntimeSystemBroadcasts.encode(logical,roots);String operation="system-receiver-"+UUID.randomUUID();
            broker.contract.accept(operation,source.identity.key(),RuntimeBroadcastIntent.digest(payload));broker.contract.start(operation);
            String ticket=ledger.prepareSystem(operation,source,registration,payload);Bundle claimed=ledger.claim(ticket,generation,"",registration,instance);claimed.putString("ticket",ticket);return claimed;
        }
        if(code==RuntimeWire.BROADCAST_REGISTRATION){String state=RuntimeWire.required(input,"state");String prior=state.equals("ACTIVE")||input.getBoolean("failed")?"PREPARED":"ACTIVE";if(!Set.of("ACTIVE","CLOSED").contains(state))throw new SecurityException("RECEIVER_STATE_INVALID");String registration=RuntimeWire.required(input,"registration");ledger.registrationState(registration,generation,prior,state);if(state.equals("CLOSED"))ledger.closePending(registration,generation,broker.contract);return out;}
        String ticket=RuntimeWire.required(input,"ticket");
        if(code==RuntimeWire.BROADCAST_CLAIM){
            boolean prepare=input.getBoolean("prepare");Bundle b=ledger.get(ticket);
            if(!generation.equals(b.getString("target")))throw new SecurityException("BROADCAST_TARGET_MISMATCH");
            if(prepare){if(!b.getString("state").equals("PREPARED"))throw new SecurityException("BROADCAST_TARGET_MISMATCH");if(b.getString("component").isEmpty())throw new SecurityException("BROADCAST_STATIC_COMPONENT_REQUIRED");return withBinders(b,ticket);}
            if(b.getBoolean("externalOrigin")){
                if(!input.containsKey("observedOrdered"))throw new SecurityException("PENDING_ORDER_OBSERVATION_REQUIRED");
                String version=b.getString("source").substring("pending:".length());var pending=broker.pendingIntents.get(version);
                var install=broker.apks.row(pending.space,pending.pkg);
                if(pending.fenced||install==null||!"COMMITTED".equals(install.state)||!pending.install.equals(install.generation)||!pending.data.equals(install.dataId)){ledger.rejectExternal(ticket,generation,broker.contract);out.putBoolean("rejectedClosedRegistration",true);return out;}
                if(!b.getString("registration").isEmpty()&&b.getBoolean("ordered")!=input.getBoolean("observedOrdered"))throw new SecurityException("PENDING_DYNAMIC_ORDER_MISMATCH");
                ledger.externalOrdered(ticket,generation,input.getBoolean("observedOrdered"));
            }else if(input.containsKey("observedOrdered")&&b.getBoolean("ordered")!=input.getBoolean("observedOrdered"))throw new SecurityException("BROADCAST_ORDER_MISMATCH");
            return withBinders(ledger.claim(ticket,generation,input.getString("component",""),input.getString("registration",""),RuntimeWire.required(input,"instance")),ticket);
        }
        if(code==RuntimeWire.BROADCAST_EVENT){ledger.event(ticket,generation,input.getString("instance",""),RuntimeWire.required(input,"event"),broker.contract);return out;}
        throw new IllegalArgumentException("BROADCAST_PROTOCOL_UNSUPPORTED");
    }
    /** Binder extras live only in the authority's memory, by delivery ticket, and are handed to the one target that claims it. */
    private Bundle withBinders(Bundle reply,String ticket){Bundle binders=broker.broadcastBinders.get(ticket);if(binders==null)return reply;Bundle copy=new Bundle(reply);copy.putBundle("binders",binders);return copy;}
    private List<Bundle> resolve(ApkStore.BundleInstall sender,Intent intent,String receiverPermission)throws Exception{
        List<Bundle> targets=new ArrayList<>();
        // A sender's receiverPermission restricts which receivers may be reached; it is applied
        // per target below (holds()), not pre-rejected here when the sender does not own it.
        if(intent.getComponent()!=null){
            var component=intent.getComponent();if(intent.getPackage()!=null&&!intent.getPackage().equals(component.getPackageName()))throw new SecurityException("BROADCAST_PACKAGE_MISMATCH");
            var install=broker.apks.get(sender.space,component.getPackageName());var declared=RuntimeComponents.declared(install,"receiver",component.getClassName());RuntimeComponents.authorize(declared,install.info.packageName,sender.info.packageName);
            if(receiverPermission==null||RuntimeSystemBroadcasts.holds(install,sender,receiverPermission)){var to=broker.processes.reserveComponent(install,declared.process);Bundle target=new Bundle();target.putString("target",to.allocation.session.processGeneration);target.putString("component",declared.name);target.putString("registration","");target.putString("stub",RuntimeReceiverSlots.type(RuntimeSlot.index(to.activityStub)).getName());targets.add(target);}
        }else{
            requireDynamicImplicit(sender,intent);
            for(BroadcastLedger.ActiveRegistration registration:broker.broadcasts.activeRegistrationsForSpace(sender.space)){
                if(intent.getPackage()!=null&&!intent.getPackage().equals(registration.pkg))continue;
                if(!registration.exported&&!sender.info.packageName.equals(registration.pkg))continue;
                if(!RuntimeBroadcastIntent.matches(registration.parsedFilter,intent))continue;
                String permission=registration.permission;
                if(receiverPermission!=null||!permission.isEmpty()){
                    var owner=broker.apks.get(sender.space,registration.pkg);
                    if(receiverPermission!=null&&!RuntimeSystemBroadcasts.holds(owner,sender,receiverPermission))continue;
                    if(!permission.isEmpty()&&!RuntimeSystemBroadcasts.holds(sender,owner,permission))continue;
                }
                broker.requiredSlot(registration.generation);Bundle target=new Bundle();target.putString("target",registration.generation);target.putString("component","");target.putString("registration",registration.id);target.putString("action",BroadcastLedger.action(registration.id));targets.add(target);
            }
        }
        if(targets.size()>128)throw new IllegalArgumentException("BROADCAST_FANOUT_LIMIT");return targets;
    }
    void requireDynamicImplicit(ApkStore.BundleInstall sender,Intent intent)throws Exception{
        if(intent.getComponent()!=null)throw new IllegalArgumentException("IMPLICIT_INTENT_REQUIRED");
        String pkg=intent.getPackage()!=null?intent.getPackage():sender.info.packageName;
        if(!pkg.equals(sender.info.packageName)&&broker.apks.row(sender.space,pkg)==null)return;
        var install=pkg.equals(sender.info.packageName)?sender:broker.apks.get(sender.space,pkg);
        for(var c:install.components())if(c.enabled&&c.type.equals("receiver"))for(var filter:c.declaration.children)if(filter.name.equals("intent-filter"))for(var action:filter.children)if(action.name.equals("action")&&Objects.equals(action.android("name",""),intent.getAction()))throw new UnsupportedOperationException("MANIFEST_IMPLICIT_BROADCAST_NOT_IMPLEMENTED");
    }
    ArrayList<Bundle> pending(PendingIntentLedger.Version value,Intent intent,boolean ordered)throws Exception{
        ApkStore.BundleInstall sender=broker.apks.get(value.space,value.pkg);broker.pendingIntentBroker.current(sender);
        if(!value.install.equals(sender.generation)||!value.data.equals(sender.data.getName()))throw new SecurityException("PENDING_INSTALL_STALE");
        List<Bundle> targets=resolve(sender,intent,null);if(ordered&&targets.size()>1)throw new UnsupportedOperationException("ORDERED_BROADCAST_SINGLE_TARGET_REQUIRED");
        byte[] payload=RuntimeBroadcastIntent.encode(intent);String operation="pending-implicit-"+UUID.randomUUID(),creator="pending-install:"+value.install+":"+value.data;ArrayList<Bundle> deliveries=new ArrayList<>();
        if((value.flags&RuntimePendingIntentPolicy.ONE_SHOT)!=0)broker.pendingIntents.consume(value.version);
        if(targets.isEmpty()){broker.contract.accept(operation,creator,RuntimeBroadcastIntent.digest(payload));broker.contract.start(operation);broker.contract.succeed(operation,"NO_MATCHING_ACTIVE_REGISTRATION_OBSERVED");}
        for(int i=0;i<targets.size();i++){
            Bundle target=targets.get(i);String op=operation+"-"+i;broker.contract.accept(op,creator,ApkStore.textDigest(value.version+":"+target.getString("registration")+":"+RuntimeBroadcastIntent.digest(payload)));broker.contract.start(op);
            String ticket=broker.broadcasts.prepareExternal(op,value.version,creator,target.getString("target"),target.getString("component"),target.getString("registration"),payload,ordered);target.putString("ticket",ticket);deliveries.add(target);
        }return deliveries;
    }
    Bundle route(Bundle input,ExecutionContract.Session source)throws Exception{
        byte[] payload=input.getByteArray("payload");Intent intent=RuntimeBroadcastIntent.decode(payload);RuntimeBroadcastIntent.envelope(intent);
        boolean ordered=input.getBoolean("ordered");List<Bundle> targets=new ArrayList<>();
        String receiverPermission=input.getString("receiverPermission");var sender=broker.apks.get(source.identity.spaceId,source.identity.packageName);
        targets=resolve(sender,intent,receiverPermission);
        if(targets.size()>128)throw new IllegalArgumentException("BROADCAST_FANOUT_LIMIT");
        if(ordered&&targets.size()!=1)throw new UnsupportedOperationException("ORDERED_BROADCAST_SINGLE_TARGET_REQUIRED");
        if(targets.isEmpty()){Bundle out=new Bundle();out.putParcelableArrayList("deliveries",new ArrayList<>());return out;}
        String operation=RuntimeWire.required(input,"operation");ArrayList<Bundle> deliveries=new ArrayList<>();
        for(int i=0;i<targets.size();i++){
            Bundle target=targets.get(i);String op=operation+"-"+i;
            String digest=ApkStore.textDigest(RuntimeBroadcastIntent.digest(payload)+"/"+target.getString("target")+"/"+target.getString("component")+"/"+target.getString("registration")+"/"+ordered+"/receiverPermission="+receiverPermission);
            if(!broker.contract.accept(op,source.identity.key(),digest).created)throw new SecurityException("BROADCAST_ALREADY_ADMITTED_QUERY_REQUIRED");broker.contract.start(op);
            String ticket=broker.broadcasts.prepare(op,source,target.getString("target"),target.getString("component"),target.getString("registration"),payload,ordered);target.putString("ticket",ticket);deliveries.add(target);
            if(input.getBundle("binders")!=null)broker.broadcastBinders.put(ticket,input.getBundle("binders"));
        }
        Bundle out=new Bundle();out.putParcelableArrayList("deliveries",deliveries);return out;
    }
}
