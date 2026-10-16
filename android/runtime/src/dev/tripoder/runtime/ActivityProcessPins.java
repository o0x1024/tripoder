package dev.tripoder.runtime;

/** Execution exclusivity is separate from retained Android component/affinity names. */
final class ActivityProcessPins {
    static boolean pinned(RuntimeStore store,String processStub){
        return pinned(store,processStub,null);
    }
    static boolean pinnedByOther(RuntimeStore store,String processStub,ApkStore.BundleInstall install,String process){
        return pinned(store,processStub,new String[]{install.space,install.info.packageName,install.generation,process});
    }
    private static boolean pinned(RuntimeStore store,String processStub,String[] owner){
        String[] arguments=owner==null?new String[]{processStub}:new String[]{processStub,owner[0],owner[1],owner[2],owner[3]};
        String tickets=owner==null?"":" AND (COALESCE(s.state,'UNKNOWN')!='DEAD' OR NOT(t.space IS ? AND t.package IS ? AND t.installGeneration IS ? AND t.processName IS ?))",components=owner==null?"":" AND (COALESCE(s.state,'UNKNOWN')!='DEAD' OR NOT(ac.space IS ? AND ac.package IS ? AND ac.installGeneration IS ? AND ac.processName IS ?))";
        // A live/unknown instance or any old entry still allowed to construct its
        // guest requires this process identity. Close/fence alone never proves death.
        try(var c=store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.processStub=? AND (COALESCE(s.state,'UNKNOWN')!='DEAD' OR (t.state NOT IN ('FINISHED','REVOKED','RETIRED') AND t.entryFenceOperation IS NULL AND COALESCE(s.closeRequested,0)=0))"+tickets+" LIMIT 1",arguments)){if(c.moveToFirst())return true;}
        // Keep an unassociated reservation unless its exact owner was closed and
        // died. This query never releases the component, alias or affinity lease.
        try(var c=store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_components ac LEFT JOIN sessions s ON s.generation=ac.generation WHERE ac.processStub=? AND (COALESCE(s.state,'UNKNOWN')!='DEAD' OR (COALESCE(s.closeRequested,0)=0 AND NOT EXISTS(SELECT 1 FROM activity_tickets t WHERE t.componentLease=ac.lease)))"+components+" LIMIT 1",arguments)){return c.moveToFirst();}
    }
    private ActivityProcessPins(){}
}
