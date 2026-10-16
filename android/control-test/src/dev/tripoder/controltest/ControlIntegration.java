package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

public final class ControlIntegration extends ControlTestBase {
    @Override void test()throws Exception{
        startThroughWorkbench();
        IBinder entry=engine();
        if("recovery".equals(args.getString("phase"))){
            Bundle connection=connect(entry,life());IBinder session=connection.getBinder("session");String operation=args.getString("operation"),space=args.getString("space");Bundle result=execute(session,"query","operation",operation);
            check("operation-survives-real-authority-process-death","SUCCEEDED".equals(result.getString("state"))&&space.equals(result.getString("result")),json(result));
            Bundle catalog=execute(session,"catalog");check("space-survives-real-authority-process-death",new JSONArray(catalog.getString("spaces")).toString().contains(space),json(catalog));
            Bundle target=execute(session,"resolveProject","project",args.getString("project"));
            check("project-and-install-survive-real-authority-process-death",args.getString("installGeneration").equals(target.getString("generation"))&&args.getString("bindingOperation").equals(target.getString("bindingOperation"))&&args.getString("dataId").equals(target.getString("dataId")),json(target));
            Bundle interrupted=execute(session,"query","operation",args.getString("readyLaunchOperation"));check("unconsumed-launch-recovered-with-no-dispatch-proof","FAILED".equals(interrupted.getString("state"))&&"LAUNCH_AUTHORITY_RESTARTED_BEFORE_DISPATCH".equals(interrupted.getString("detail"))&&interrupted.getString("evidence","").contains("capability=READY;ticket=null"),json(interrupted));
            report.put("status",json(execute(session,"status")));execute(session,"disconnect");return;
        }
        check("plain-binder-cannot-impersonate-controller-lifetime",rejected(()->connect(entry,new Binder()),SecurityException.class,"CONTROL_OWNER_PROTOCOL_REJECTED"),true);
        IBinder other=bind("dev.tripoder.controltest","dev.tripoder.controltest.RejectedClient");
        Bundle wrong=execute(other,"attempt");check("same-certificate-wrong-package-rejected",wrong.getBoolean("blocked")&&"CONTROL_PACKAGE_REJECTED".equals(wrong.getString("message")),json(wrong));
        IBinder foreignLife=execute(other,"owner").getBinder("owner");
        check("forwarded-lifetime-binder-host-process-rejected",rejected(()->connect(entry,foreignLife),SecurityException.class,"CONTROL_OWNER_PROCESS_MISMATCH"),true);
        Bundle connection=connect(entry,life());IBinder session=connection.getBinder("session");Bundle status=execute(session,"status");report.put("status",json(status));
        check("workbench-and-engine-have-distinct-real-uids",status.getInt("controllerUid")==android.os.Process.myUid()&&status.getInt("engineUid")!=android.os.Process.myUid()&&status.getInt("enginePid")!=android.os.Process.myPid(),json(status));
        check("owner-conflict-rejected",rejected(()->connect(entry,life()),IllegalStateException.class,"CONTROL_OWNER_CONFLICT"),true);
        check("probe-command-not-in-production-api",rejected(()->execute(session,"probeCrash"),UnsupportedOperationException.class,"CONTROL_COMMAND_UNSUPPORTED"),true);
        check("raw-worker-command-not-in-production-api",rejected(()->execute(session,"allocate"),UnsupportedOperationException.class,"CONTROL_COMMAND_UNSUPPORTED"),true);
        check("unknown-control-fields-rejected",rejected(()->execute(session,"status","path","/data/local/tmp"),IllegalArgumentException.class,"CONTROL_FIELDS_UNSUPPORTED"),true);
        String canceled=UUID.randomUUID().toString();Bundle cancellation=execute(session,"cancelNotAccepted","operation",canceled);
        check("unaccepted-operation-cancel-record-is-durable","FAILED".equals(cancellation.getString("state"))&&"CANCELED_BEFORE_ACCEPT".equals(cancellation.getString("detail")),json(cancellation));
        check("late-request-cannot-execute-after-cancel",rejected(()->execute(session,"createSpace","operation",canceled,"space",run+"-canceled"),IllegalStateException.class,"OPERATION_CONFLICT"),true);
        String operation=UUID.randomUUID().toString(),space=run;report.put("operation",operation).put("space",space);
        Bundle created=execute(session,"createSpace","operation",operation,"space",space);check("space-created-by-real-runtime-catalog",space.equals(created.getString("space")),json(created));
        Bundle queried=execute(session,"query","operation",operation);check("space-operation-has-durable-receipt","SUCCEEDED".equals(queried.getString("state"))&&space.equals(queried.getString("result")),json(queried));
        check("cancel-does-not-rewrite-accepted-success","SUCCEEDED".equals(execute(session,"cancelNotAccepted","operation",operation).getString("state")),operation);
        execute(session,"createSpace","operation",operation,"space",space);JSONArray spaces=new JSONArray(execute(session,"catalog").getString("spaces"));int found=0;for(int i=0;i<spaces.length();i++)if(space.equals(spaces.getString(i)))found++;
        check("duplicate-operation-does-not-create-second-space",found==1,spaces);
        new ControlInstallChecks(this,session).run(space);
        new ControlLaunchChecks(this,session).run();
        ControlHookChecks hooks=new ControlHookChecks(this,session);hooks.run();
        String old=connection.getString("lease");execute(session,"disconnect");
        check("retired-session-cannot-control-engine",rejected(()->execute(session,"status"),SecurityException.class,"CONTROL_LEASE_REVOKED"),true);
        Bundle replacement=connect(entry,life());check("new-connection-has-new-lease",!old.equals(replacement.getString("lease")),json(replacement));IBinder next=replacement.getBinder("session");
        Bundle nextStatus=execute(next,"status");check("navigation-disconnect-does-not-restart-authority",nextStatus.getInt("enginePid")==status.getInt("enginePid")&&nextStatus.getString("authority").equals(status.getString("authority")),json(nextStatus));
        hooks.reconnected(next);
        check("new-connection-queries-existing-operation","SUCCEEDED".equals(execute(next,"query","operation",operation).getString("state")),operation);execute(next,"disconnect");
    }
}
