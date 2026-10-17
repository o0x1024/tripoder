package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Real worker preparation and one-use admission; actual gateway presentation is a separate UI gate. */
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
        String operation=UUID.randomUUID().toString();Bundle request=ControlWire.request("prepareLaunch","operation",operation,"project",project,"bindingOperation",binding,"generation",generation,"component","dev.tripoder.p0.a.AActivity");
        Bundle prepared=ControlWire.call(session,ControlWire.EXECUTE,request);String capability=prepared.getString("capability");Bundle facts=new Bundle(prepared);facts.remove("capability");
        test.check("launch-preparation-has-real-running-worker",prepared.getInt("pid")>0&&prepared.getInt("uid")==android.os.Process.myUid()&&prepared.getInt("pid")!=android.os.Process.myPid()&&"RUNNING".equals(prepared.getString("state"))&&generation.equals(prepared.getString("installGeneration")),test.json(facts));
        test.check("launch-capability-is-256-bit-one-time-value",capability!=null&&capability.matches("[a-f0-9]{64}"),"raw token omitted");
        test.check("duplicate-launch-does-not-return-another-capability",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,request),IllegalStateException.class,"LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED"),true);
        Bundle pending=test.execute(session,"query","operation",operation);
        test.check("preparation-is-not-activity-success","RUNNING".equals(pending.getString("state"))&&!pending.containsKey("capability"),test.json(pending));
        test.check("cancel-not-accepted-does-not-cancel-prepared-launch","RUNNING".equals(test.execute(session,"cancelNotAccepted","operation",operation).getString("state")),operation);
        long end=SystemClock.elapsedRealtime()+35000;Bundle expired;
        do{SystemClock.sleep(250);expired=test.execute(session,"query","operation",operation);}while("RUNNING".equals(expired.getString("state"))&&SystemClock.elapsedRealtime()<end);
        test.check("unconsumed-launch-expires-without-component-replay","FAILED".equals(expired.getString("state"))&&"LAUNCH_CAPABILITY_EXPIRED_BEFORE_DISPATCH".equals(expired.getString("detail")),test.json(expired));
        test.check("expired-operation-cannot-be-prepared-again",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,request),IllegalStateException.class,"LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED"),true);
        String interrupted=UUID.randomUUID().toString();Bundle next=new Bundle(request);next.putString("operation",interrupted);Bundle ready=ControlWire.call(session,ControlWire.EXECUTE,next);test.report.put("readyLaunchOperation",interrupted);
        test.check("second-launch-prepared-for-real-authority-death","RUNNING".equals(test.execute(session,"query","operation",interrupted).getString("state"))&&ready.containsKey("capability"),interrupted);
    }
}
