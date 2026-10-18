package dev.tripoder.hooklab;

import org.json.JSONObject;
import android.os.Bundle;
import java.util.*;

/** Ephemeral preparation tokens survive navigation, never controller death or a new lease. */
final class WorkbenchHooks {
    static final class Session {
        Bundle target;String lease="",process="",name="",preparedSource="";int pid;
        JSONObject observed,prepared,status;boolean uncertain=true;String message="选择运行进程后查询实际状态。";
        void clearEvidence(){observed=null;prepared=null;preparedSource="";status=null;uncertain=true;}
        void select(Bundle target,JSONObject process,String lease)throws Exception{
            String next=process.getString("processGeneration");
            if(!next.equals(this.process)||!lease.equals(this.lease)||this.target==null||!target.getString("bindingOperation").equals(this.target.getString("bindingOperation")))clearEvidence();
            this.target=new Bundle(target);this.process=next;this.pid=process.getInt("pid");this.name=process.getString("processName");this.lease=lease;
        }
    }
    private final Map<String,Session> sessions=new HashMap<>();
    Session project(String project){return sessions.computeIfAbsent(project,key->new Session());}
    /** Existing session only; lists read state without creating one per project. */
    Session peek(String project){return sessions.get(project);}
    void forget(String project){sessions.remove(project);}
}
