package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Intent;
import java.util.Objects;

/** Task attachment outlives Android's non-persisted Intent extras. Never infer a root from task id alone. */
final class RuntimeTaskRootIdentity {
    static String resolve(RuntimeBroker broker,ActivityManager.RecentTaskInfo info)throws Exception {
        Intent intent=info.baseIntent;
        if(intent==null||intent.getComponent()==null||!broker.host.getPackageName().equals(intent.getComponent().getPackageName()))return null;
        String physical=intent.getComponent().getClassName(),recorded=null;
        // Only the trusted controller creates a fresh NEW_TASK|MULTIPLE_TASK root.
        // A received CREATE and its unchanged non-reparenting component lease prove
        // this attachment. A guest child, reused delivery or recreated instance does not.
        try(var rows=broker.store.getReadableDatabase().rawQuery(
            "SELECT t.ticket FROM activity_tickets t JOIN activity_launches l ON l.candidate=t.ticket JOIN operations o ON o.id=l.operation JOIN activity_components ac ON ac.lease=t.componentLease "+
            "WHERE t.taskId=? AND t.stub=? AND t.instance IS NOT NULL AND l.receiver=t.ticket AND l.instance=t.instance AND l.generation=t.generation AND l.kind='CREATE' AND l.state='RECEIVED' "+
            "AND l.callerKind='CONTROLLER' AND l.sourceTicket IS NULL AND l.sourceInstance IS NULL AND l.callerComponent IS NULL AND l.expectsResult=0 AND o.caller=? "+
            "AND ac.stub=t.stub AND ac.space=t.space AND ac.package=t.package AND ac.installGeneration=t.installGeneration AND ac.processName=t.processName AND ac.processStub=t.processStub AND ac.component=t.component AND ac.profile LIKE '%:false'",
            new String[]{Integer.toString(info.taskId),physical,ControllerTrust.OPERATIONS})){
            if(rows.moveToFirst()){recorded=rows.getString(0);if(rows.moveToNext())return null;}
        }
        String supplied=intent.getStringExtra("tripoder.ticket");
        if(recorded!=null&&supplied!=null&&!recorded.equals(supplied))return null;
        // An issued entry that has not acknowledged a task still needs the complete
        // authenticated delivery carried by Android. It has no durable attachment.
        String root=recorded!=null?recorded:supplied;if(root==null)return null;
        var ticket=broker.activities.get(root);var delivery=broker.activities.launches.forTicket(root);var launch=broker.activities.launches.get(delivery);
        if(!physical.equals(ticket.stub)||!Objects.equals(launch.generation,ticket.generation))return null;
        if(recorded!=null&&info.baseActivity!=null&&!info.baseActivity.equals(intent.getComponent()))return null;
        String[] keys={"tripoder.ticket","tripoder.component","tripoder.delivery","tripoder.generation"};String[] values={root,ticket.component,delivery,launch.generation};
        for(int i=0;i<keys.length;i++)if((recorded==null||intent.hasExtra(keys[i]))&&!Objects.equals(values[i],intent.getStringExtra(keys[i])))return null;
        return root;
    }
    private RuntimeTaskRootIdentity(){}
}
