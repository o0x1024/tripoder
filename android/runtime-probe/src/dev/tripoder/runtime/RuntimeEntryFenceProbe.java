package dev.tripoder.runtime;

import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Test-only orchestration of production entry/fence/lease primitives, using owned fixtures. */
public final class RuntimeEntryFenceProbe {
    public static Bundle command(RuntimeBroker broker,Bundle input)throws Exception{
        String action=RuntimeWire.required(input,"action"),space=RuntimeWire.required(input,"space");
        var install=broker.apks.get(space,"dev.tripoder.p2");Bundle out=new Bundle();
        if(action.equals("prepare")){
            String component=RuntimeWire.required(input,"component");if(!Set.of("dev.tripoder.p2.EntryFenceActivity$Standard","dev.tripoder.p2.EntryFenceActivity$Top").contains(component))throw new SecurityException("ENTRY_FIXTURE_COMPONENT_REQUIRED");
            synchronized(broker){
                var slot=broker.findProcess(space,"dev.tripoder.p2","dev.tripoder.p2");if(slot==null)throw new IllegalStateException("ENTRY_FIXTURE_PROCESS_REQUIRED");
                ActivityEntryFences.requireNoOpenRoot(broker.store,space,"dev.tripoder.p2",install.generation);
                String project=UUID.randomUUID().toString(),binding=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
                ContentValues ref=new ContentValues();ref.put("id",project);ref.put("space",space);ref.put("package","dev.tripoder.p2");ref.put("generation",install.generation);ref.put("sourceSha",install.sha256);ref.put("snapshot","{}");ref.put("state","COMMITTED");ref.put("operation",binding);ref.put("kind","DIRECT");broker.store.getWritableDatabase().insertOrThrow("project_refs",null,ref);
                Bundle target=new Bundle();target.putString("project",project);target.putString("space",space);target.putString("package","dev.tripoder.p2");target.putString("generation",install.generation);target.putString("bindingOperation",binding);
                broker.contract.accept(operation,"owned-entry-fixture",ApkStore.textDigest(operation));broker.contract.start(operation);
                var entries=new ControllerLaunchLedger(broker);String capability=entries.prepare(operation,"owned-entry-fixture",android.os.Process.myUid(),target,slot,component,false);
                Intent intent=entries.consume(operation,capability,android.os.Process.myUid(),broker.host.getPackageName(),input.getInt("taskId"));
                out.putParcelable("intent",intent);out.putString("operation",operation);out.putString("generation",slot.allocation.session.processGeneration);out.putInt("pid",broker.contract.getSession(slot.allocation.session.processGeneration).pid);return out;
            }
        }
        if(action.equals("close")){
            String launch=RuntimeWire.required(input,"launchOperation"),operation=RuntimeWire.required(input,"operation");synchronized(broker){
                var entry=new ControllerLaunchLedger(broker).get(launch);if(entry==null||!space.equals(entry.space)||!"dev.tripoder.p2".equals(entry.pkg))throw new SecurityException("ENTRY_FIXTURE_OWNER_MISMATCH");
                var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                    var accepted=broker.contract.accept(operation,"owned-entry-fixture",ApkStore.textDigest(launch));
                    if(accepted.created){broker.contract.start(operation);ActivityEntryFences.close(broker,entry,operation);broker.contract.succeed(operation,"ENTRY_FENCED_RESOURCES_RETAINED");}
                    if(!broker.activities.closedEntry(entry.ticket,broker.activities.get(entry.ticket).stub))throw new AssertionError("FENCE_NOT_PERSISTED");
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }return out;
        }
        if(action.equals("finish")){Files.writeString(new File(install.data,"files/entry-finish").toPath(),"finish");return out;}
        if(action.equals("normalIntent")){Files.writeString(new File(install.data,"files/entry-new-intent").toPath(),"start");return out;}
        if(action.equals("reset")){Files.deleteIfExists(new File(install.data,"files/entry-finish").toPath());Files.deleteIfExists(new File(install.data,"files/entry-state.json").toPath());return out;}
        if(action.equals("state")){
            File file=new File(install.data,"files/entry-state.json");if(file.exists())out.putString("fixture",Files.readString(file.toPath()));
            File starts=new File(install.data,"files/starts-dev.tripoder.p2");out.putInt("starts",starts.exists()?Integer.parseInt(Files.readString(starts.toPath())):0);
            synchronized(broker){
                if(input.containsKey("launchOperation")){var entry=new ControllerLaunchLedger(broker).get(input.getString("launchOperation"));if(entry==null||!entry.space.equals(space))throw new SecurityException("ENTRY_FIXTURE_OWNER_MISMATCH");var ticket=broker.activities.get(entry.ticket);out.putString("ticket",ticket.id);out.putString("ticketState",ticket.state);out.putInt("taskId",ticket.taskId);out.putString("componentLease",ticket.componentLease);out.putString("stub",ticket.stub);out.putString("processStub",ticket.processStub);out.putBoolean("closed",broker.activities.closedEntry(ticket.id,ticket.stub));out.putString("outcome",broker.contract.query(entry.operation).state.name());int rejected=0;String kind="";for(int i=0;i<broker.events.length();i++){JSONObject event=broker.events.getJSONObject(i);if("closed-activity-entry-rejected".equals(event.optString("stage"))&&ticket.id.equals(event.optString("ticket"))){rejected++;kind=event.getString("kind");}}out.putInt("rejected",rejected);out.putString("rejectionKind",kind);}
                out.putBoolean("installPinned",false);try{broker.catalog.requireUnused(space,"dev.tripoder.p2");}catch(IllegalStateException pinned){out.putBoolean("installPinned",true);out.putString("pinReason",pinned.getMessage());}
                var slot=broker.findProcess(space,"dev.tripoder.p2","dev.tripoder.p2");out.putBoolean("workerAllocated",slot!=null);if(slot!=null)out.putInt("pid",broker.contract.getSession(slot.allocation.session.processGeneration).pid);
            }return out;
        }
        throw new IllegalArgumentException("ENTRY_FIXTURE_ACTION_UNSUPPORTED");
    }
    private RuntimeEntryFenceProbe(){}
}
