package dev.tripoder.controltest;

import android.content.Intent;
import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Real worker preparation and the issued root entry; starting that entry is a separate UI gate. */
final class ControlLaunchChecks {
    private final ControlTestBase test;private final IBinder session;
    ControlLaunchChecks(ControlTestBase test,IBinder session){this.test=test;this.session=session;}
    void run()throws Exception{
        String project=test.report.getString("project"),binding=test.report.getString("bindingOperation"),generation=test.report.getString("installGeneration");
        Bundle targets=test.execute(session,"launchTargets","project",project);JSONArray components=new JSONArray(targets.getString("targets"));
        test.check("launch-targets-come-from-bound-install",generation.equals(targets.getString("generation"))&&binding.equals(targets.getString("bindingOperation"))&&components.length()==1&&"dev.tripoder.p0.a.AActivity".equals(components.getJSONObject(0).getString("component")),test.json(targets));
        String stale=UUID.randomUUID().toString();
        test.check("launch-rejects-stale-project-binding",test.rejected(()->test.execute(session,"prepareLaunch","operation",stale,"project",project,"bindingOperation",UUID.randomUUID().toString(),"generation",generation,"component","dev.tripoder.p0.a.AActivity"),IllegalStateException.class,"LAUNCH_BINDING_STALE"),true);
        test.check("rejected-launch-has-failed-receipt","FAILED".equals(test.execute(session,"query","operation",stale).getString("state")),stale);
        test.execute(session,"setProjectMode","operation",UUID.randomUUID().toString(),"project",project,"mode","thin");
        String thinLaunch=UUID.randomUUID().toString();
        Bundle thinPrepared=ControlWire.call(session,ControlWire.EXECUTE,ControlWire.request("prepareLaunch","operation",thinLaunch,"project",project,"bindingOperation",binding,"generation",generation,"component","dev.tripoder.p0.a.AActivity"));
        JSONArray thinProcesses=new JSONArray(test.execute(session,"hookProcesses","project",project).getString("processes"));
        test.check("thin-mode-launch-prepares-slot-in-thin-mode","RUNNING".equals(thinPrepared.getString("state"))&&"thin".equals(thinPrepared.getString("mode"))&&thinProcesses.length()==1&&"thin".equals(thinProcesses.getJSONObject(0).getString("mode")),test.json(thinPrepared));
        JSONObject thinPolicy=executionPolicy(project,binding,generation,thinPrepared.getString("generation"));
        test.check("thin-preserves-art-compilation-without-global-deoptimization",thinPolicy.getBoolean("preserveCompilation")&&!thinPolicy.getBoolean("owned")&&thinPolicy.getInt("claims")==0&&"method-entry-only".equals(thinPolicy.getString("policy")),thinPolicy);
        test.execute(session,"abandonLaunch","operation",thinLaunch);
        test.execute(session,"stopApplication","operation",UUID.randomUUID().toString(),"space",test.report.getString("space"),"package","dev.tripoder.p0.a","generation",generation);
        test.execute(session,"setProjectMode","operation",UUID.randomUUID().toString(),"project",project,"mode","thick");
        String operation=UUID.randomUUID().toString();Bundle request=ControlWire.request("prepareLaunch","operation",operation,"project",project,"bindingOperation",binding,"generation",generation,"component","dev.tripoder.p0.a.AActivity");
        Bundle prepared=ControlWire.call(session,ControlWire.EXECUTE,request);Intent entry=prepared.getParcelable("intent",Intent.class);Bundle facts=new Bundle(prepared);facts.remove("intent");
        JSONObject thickPolicy=executionPolicy(project,binding,generation,prepared.getString("generation"));
        test.check("thick-retains-process-interpreter-policy",!thickPolicy.getBoolean("preserveCompilation")&&thickPolicy.getBoolean("owned")&&thickPolicy.getInt("claims")==1,thickPolicy);
        // One call waits for the guest Application and returns the root entry; nothing is started until the caller starts that Intent.
        test.check("launch-waits-for-guest-application",generation.equals(prepared.getString("installGeneration"))&&prepared.getString("generation")!=null&&"RUNNING".equals(prepared.getString("state"))&&"thick".equals(prepared.getString("mode"))&&prepared.getString("processName")!=null,test.json(facts));
        test.check("launch-entry-is-own-stub-with-ticket",entry!=null&&entry.getComponent()!=null&&test.enginePackage().equals(entry.getComponent().getPackageName())&&entry.getStringExtra("tripoder.ticket")!=null&&prepared.getString("generation").equals(entry.getStringExtra("tripoder.generation"))&&(entry.getFlags()&(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK))==(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),entry==null?"null":entry.toString());
        test.check("duplicate-launch-does-not-return-another-entry",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,request),IllegalStateException.class,"LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED"),true);
        Bundle pending=test.execute(session,"query","operation",operation);
        test.check("issued-entry-is-not-activity-success","RUNNING".equals(pending.getString("state")),test.json(pending));
        test.check("cancel-not-accepted-does-not-cancel-issued-launch","RUNNING".equals(test.execute(session,"cancelNotAccepted","operation",operation).getString("state")),operation);
        String concurrent=UUID.randomUUID().toString();Bundle second=new Bundle(request);second.putString("operation",concurrent);
        test.check("issued-entry-holds-the-application-root",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,second),IllegalStateException.class,"APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED"),true);
        Bundle abandoned=test.execute(session,"abandonLaunch","operation",operation);Bundle settled=test.execute(session,"query","operation",operation);
        test.check("unstarted-entry-is-handed-back","FAILED".equals(settled.getString("state"))&&"LAUNCH_NOT_DISPATCHED".equals(settled.getString("detail"))&&entry.getStringExtra("tripoder.ticket").equals(abandoned.getString("ticket")),test.json(settled));
        test.check("entry-is-handed-back-once",test.rejected(()->test.execute(session,"abandonLaunch","operation",operation),IllegalStateException.class,"LAUNCH_ENTRY_NOT_PENDING"),true);
        test.check("abandoned-operation-cannot-be-launched-again",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,request),IllegalStateException.class,"LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED"),true);
        // A start the platform drops silently leaves no error for the caller to report; the entry expires instead of holding the root.
        String dropped=UUID.randomUUID().toString();Bundle drop=new Bundle(request);drop.putString("operation",dropped);ControlWire.call(session,ControlWire.EXECUTE,drop);
        long until=SystemClock.elapsedRealtime()+45000;Bundle expired;do{SystemClock.sleep(500);expired=test.execute(session,"query","operation",dropped);}while("RUNNING".equals(expired.getString("state"))&&SystemClock.elapsedRealtime()<until);
        test.check("unentered-entry-expires-and-releases-root","FAILED".equals(expired.getString("state"))&&"LAUNCH_ENTRY_NOT_ENTERED".equals(expired.getString("detail")),test.json(expired));
        String interrupted=UUID.randomUUID().toString();Bundle next=new Bundle(request);next.putString("operation",interrupted);Bundle ready=ControlWire.call(session,ControlWire.EXECUTE,next);test.report.put("readyLaunchOperation",interrupted);
        test.check("second-launch-issued-for-real-authority-death","RUNNING".equals(test.execute(session,"query","operation",interrupted).getString("state"))&&ready.getParcelable("intent",Intent.class)!=null,interrupted);
    }
    private JSONObject executionPolicy(String project,String binding,String install,String process)throws Exception{
        JSONObject raw=new JSONObject().put("command","status").put("processGeneration",process);
        Bundle result=test.execute(session,"hook","operation",UUID.randomUUID().toString(),"project",project,"bindingOperation",binding,"generation",install,"processGeneration",process,"request",raw.toString());
        return new JSONObject(result.getString("result")).getJSONObject("result").getJSONObject("executionPolicy");
    }
}
