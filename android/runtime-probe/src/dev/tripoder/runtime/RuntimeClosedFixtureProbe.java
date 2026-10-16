package dev.tripoder.runtime;

import android.content.*;
import android.os.Bundle;
import org.json.*;

/** Only the named, already dead self-owned fixture is closed; historical rows are not rewritten. */
public final class RuntimeClosedFixtureProbe {
    public static Bundle command(RuntimeBroker broker,Bundle input)throws Exception{
        String generation=RuntimeWire.required(input,"generation"),space=RuntimeWire.required(input,"space"),action=RuntimeWire.required(input,"action");
        String before;
        synchronized(broker){validate(broker,generation,space);before=snapshot(broker,generation);}
        if(action.equals("close"))broker.processes.close(generation);
        else if(!action.equals("inspect")&&!action.equals("successor"))throw new IllegalArgumentException("FIXTURE_CLOSE_ACTION");
        synchronized(broker){
            var old=validate(broker,generation,space);String after=snapshot(broker,generation);
            if(!before.equals(after))throw new AssertionError("FIXTURE_CLOSE_REWROTE_HISTORY");
            String ticket=null;try(var c=broker.store.getReadableDatabase().rawQuery("SELECT ticket FROM activity_tickets WHERE generation=? AND state IN ('PREPARED','CLAIMED') ORDER BY ticket",new String[]{generation})){
                if(!c.moveToFirst())throw new IllegalStateException("FIXTURE_UNCONFIRMED_ENTRY_REQUIRED");ticket=c.getString(0);if(c.moveToNext())throw new IllegalStateException("FIXTURE_ENTRY_AMBIGUOUS");
            }
            var entry=broker.activities.get(ticket);Bundle out=new Bundle();out.putString("snapshot",after);out.putString("processStub",entry.processStub);out.putString("ticket",ticket);
            out.putBoolean("closed",broker.store.closeRequested(generation));out.putBoolean("entryRejected",broker.activities.closedEntry(ticket,entry.stub));out.putBoolean("processPinned",ActivityProcessPins.pinned(broker.store,entry.processStub));
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_components WHERE stub=? AND lease=?",new String[]{RuntimeActivitySlots.target(entry.stub),entry.componentLease})){out.putBoolean("componentRetained",c.moveToFirst());}
            Intent intent=new Intent().setComponent(new ComponentName(broker.host,entry.stub)).putExtra("tripoder.component",entry.component).putExtra("tripoder.generation",generation).putExtra("tripoder.ticket",ticket).putExtra("tripoder.delivery",broker.activities.launches.forTicket(ticket));out.putParcelable("intent",intent);
            int rejected=0;for(int i=0;i<broker.events.length();i++){JSONObject event=broker.events.getJSONObject(i);if("closed-activity-entry-rejected".equals(event.optString("stage"))&&ticket.equals(event.optString("ticket")))rejected++;}out.putInt("rejected",rejected);
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM application_starts WHERE space=? AND package=?",new String[]{space,"dev.tripoder.provideridentity"})){c.moveToFirst();out.putInt("applicationStarts",c.getInt(0));}
            if(action.equals("successor")){
                String next=RuntimeWire.required(input,"successor");var current=broker.contract.getSession(next);var slot=broker.requiredSlot(next);
                if(current.state!=ExecutionContract.ProcessState.RUNNING||!current.identity.packageName.equals("dev.tripoder.provideridentity")||current.identity.installGeneration.equals(old)||!slot.activityStub.equals(entry.processStub))throw new SecurityException("FIXTURE_SUCCESSOR_NOT_DISTINCT_SAME_SLOT");
                out.putInt("successorPid",current.pid);out.putString("successorGeneration",next);out.putString("successorSpace",current.identity.spaceId);
            }
            return out;
        }
    }
    private static String validate(RuntimeBroker broker,String generation,String space){
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT space,package,installGeneration,state FROM sessions WHERE generation=?",new String[]{generation})){
            if(!c.moveToFirst()||!"DEAD".equals(c.getString(3))||!"dev.tripoder.provideridentity".equals(c.getString(1))||!space.equals(c.getString(0))||!space.matches("provider-identity-[a-f0-9]{16}-provider-identity"))throw new SecurityException("EXACT_DEAD_OWNED_FIXTURE_REQUIRED");
            return c.getString(2);
        }
    }
    private static String snapshot(RuntimeBroker broker,String generation)throws Exception{
        JSONObject result=new JSONObject();String[][] queries={
            {"tickets","SELECT t.*,l.state AS deliveryState,op.state AS operationState FROM activity_tickets t LEFT JOIN activity_launches l ON l.candidate=t.ticket LEFT JOIN operations op ON op.id=l.operation WHERE t.generation=? ORDER BY t.ticket"},
            {"components","SELECT ac.* FROM activity_components ac WHERE ac.lease IN (SELECT componentLease FROM activity_tickets WHERE generation=?) ORDER BY ac.stub"},
            {"affinities","SELECT af.* FROM activity_affinities af WHERE af.lease IN (SELECT ac.affinityLease FROM activity_components ac JOIN activity_tickets t ON t.componentLease=ac.lease WHERE t.generation=?) ORDER BY af.lease"}
        };
        for(String[] query:queries){JSONArray rows=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery(query[1],new String[]{generation})){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));rows.put(row);}}result.put(query[0],rows);}return result.toString();
    }
    private RuntimeClosedFixtureProbe(){}
}
