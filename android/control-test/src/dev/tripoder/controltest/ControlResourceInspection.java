package dev.tripoder.controltest;

import android.os.*;
import java.lang.reflect.Field;

/** Production resource inspection and authenticated empty-task reclamation. */
public final class ControlResourceInspection extends ControlTestBase {
    @Override void test()throws Exception{
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();Object engine=field(app,"engine");Bundle resource=execute((IBinder)field(engine,"session"),"resources");report.put("resources",json(resource));
        Bundle status=execute((IBinder)field(engine,"session"),"status");report.put("workerUid",status.getInt("engineUid"));Bundle reclaimed=execute((IBinder)field(engine,"session"),"reclaimEmptyTasks");report.put("afterReclaim",json(reclaimed));check("resource-capacity-is-accounted",resource.getInt("allocated")+resource.getInt("retained")+resource.getInt("available")==resource.getInt("total"),json(resource));
    }
    private static Object field(Object target,String name)throws Exception{Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
}
