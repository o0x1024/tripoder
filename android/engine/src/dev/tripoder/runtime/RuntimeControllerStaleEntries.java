package dev.tripoder.runtime;

import java.util.*;

/** Retires launch entries that died before attaching a task, so a dead process never blocks the next cold start.
 *  Proof is the same as the explicit close: the session is DEAD, the ticket never reached a task, and the
 *  original outcome stays UNKNOWN. Physical resources stay pinned. */
final class RuntimeControllerStaleEntries {
    private static final String CALLER="runtime-entry-autoclose";
    static int retire(RuntimeBroker broker,String space,String pkg,String generation)throws Exception{
        int closed=0;
        synchronized(broker){
            Map<String,Boolean> found=new LinkedHashMap<>();
            String[] args={space,pkg,generation};var db=broker.store.getWritableDatabase();
            try(var c=db.rawQuery("SELECT l.operation FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN sessions s ON s.generation=t.generation JOIN operations o ON o.id=l.operation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND l.callerKind='CONTROLLER' AND t.entryFenceOperation IS NULL AND t.taskId<0 AND t.state IN ('PREPARED','CLAIMED') AND s.state='DEAD' AND o.state='UNKNOWN' ORDER BY l.operation LIMIT 129",args)){
                while(c.moveToNext())found.put(c.getString(0),false);
            }
            try(var c=db.rawQuery("SELECT l.operation FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN sessions s ON s.generation=t.generation JOIN operations o ON o.id=l.operation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND l.callerKind='ACTIVITY' AND l.sourceTicket IS NOT NULL AND l.sourceInstance IS NOT NULL AND l.payloadDigest IS NOT NULL AND l.state='UNKNOWN' AND t.entryFenceOperation IS NULL AND t.taskId<0 AND t.state IN ('PREPARED','CLAIMED') AND s.state='DEAD' AND o.state='UNKNOWN' ORDER BY l.operation LIMIT 129",args)){
                while(c.moveToNext())found.put(c.getString(0),true);
            }
            if(found.size()>128)throw new IllegalStateException("LAUNCH_ENTRY_PAGE_REQUIRED");
            for(var item:found.entrySet()){
                String launch=item.getKey(),digest=ApkStore.textDigest("auto-close-unattached-entry-v1\n"+launch),operation="auto-close-"+digest;
                db.beginTransaction();try{
                    var admitted=broker.contract.accept(operation,CALLER,digest);
                    if(admitted.created){
                        broker.contract.start(operation);String ticket;
                        try(var c=db.rawQuery("SELECT candidate FROM activity_launches WHERE operation=?",new String[]{launch})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_ENTRY_BINDING_MISMATCH");ticket=c.getString(0);}
                        if(item.getValue())ActivityEntryFences.closeRoute(broker,ticket,launch,operation);else ActivityEntryFences.closeController(broker,ticket,launch,operation);
                        broker.contract.succeed(operation,"ENTRY_FENCED_RESOURCES_RETAINED:"+ticket);closed++;
                    }
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
        }
        return closed;
    }
    private RuntimeControllerStaleEntries(){}
}
