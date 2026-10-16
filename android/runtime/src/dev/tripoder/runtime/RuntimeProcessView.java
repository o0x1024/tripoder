package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import java.util.*;

/** Registry scopes authority; live Android observations supply process facts. */
final class RuntimeProcessView {
    private record Candidate(RuntimeBroker.Slot slot,ExecutionContract.Session session){}
    static Bundle query(RuntimeBroker broker,Bundle input,int pid,int uid){
        if(uid!=android.os.Process.myUid()||input==null||!input.keySet().equals(Set.of("generation")))throw new SecurityException("PROCESS_VIEW_CALLER_OR_SHAPE_REJECTED");
        String generation=RuntimeWire.required(input,"generation");List<Candidate> scope=new ArrayList<>();
        synchronized(broker){
            var owner=broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(generation);
            for(var slot:broker.processSlots()){
                var current=broker.contract.getSession(slot.allocation.session.processGeneration);var identity=current.identity;
                if(!slot.closing&&(current.state==ExecutionContract.ProcessState.ATTACHED||current.state==ExecutionContract.ProcessState.RUNNING)
                    &&identity.spaceId.equals(owner.identity.spaceId)&&identity.packageName.equals(owner.identity.packageName)&&identity.installGeneration.equals(owner.identity.installGeneration))scope.add(new Candidate(slot,current));
            }
        }
        ActivityManager manager=(ActivityManager)broker.host.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager==null)throw new IllegalStateException("PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE");
        List<ActivityManager.RunningAppProcessInfo> observed=manager.getRunningAppProcesses();
        if(observed==null)throw new IllegalStateException("PLATFORM_PROCESS_OBSERVATION_UNAVAILABLE");
        ArrayList<ActivityManager.RunningAppProcessInfo> result=new ArrayList<>();
        synchronized(broker){
            broker.contract.authenticate(generation,pid,uid);broker.requiredSlot(generation);
            for(var candidate:scope){
                var current=broker.contract.getSession(candidate.session.processGeneration);
                if(candidate.slot.closing||current.pid!=candidate.session.pid||current.uid!=candidate.session.uid
                    ||current.state!=ExecutionContract.ProcessState.ATTACHED&&current.state!=ExecutionContract.ProcessState.RUNNING)continue;
                String physical=broker.host.getPackageName()+":slot"+RuntimeSlot.index(candidate.slot.activityStub);
                for(var actual:observed)if(actual.pid==current.pid&&actual.uid==current.uid&&physical.equals(actual.processName)){
                    var copy=new ActivityManager.RunningAppProcessInfo(current.identity.processName,actual.pid,new String[]{current.identity.packageName});
                    copy.uid=actual.uid;copy.importance=actual.importance;copy.lastTrimLevel=actual.lastTrimLevel;copy.lru=actual.lru;
                    // Physical components and reason PIDs have no logical authority mapping in this capability.
                    copy.importanceReasonCode=ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;copy.importanceReasonPid=0;copy.importanceReasonComponent=null;result.add(copy);break;
                }
            }
        }
        result.sort(Comparator.comparingInt(value->value.pid));Bundle reply=new Bundle();reply.putParcelableArrayList("processes",result);return reply;
    }
}
