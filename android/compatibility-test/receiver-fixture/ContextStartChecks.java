package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.Intent;
import java.util.concurrent.TimeUnit;

/** Real Application Context starts have no source Activity/result capability. */
final class ContextStartChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        boolean denied=false;try{activity.getApplication().startActivity(new Intent(activity,ContextTarget.class));}catch(android.util.AndroidRuntimeException expected){denied=true;}
        check.test("application-context-requires-new-task-before-start",denied&&ContextTarget.created.get()==0);
        activity.getApplication().startActivity(new Intent(activity,ContextTarget.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        check.test("application-context-real-target-reaches-on-resume",ContextTarget.resumed.await(8,TimeUnit.SECONDS)&&ContextTarget.created.get()==1);
        check.test("context-start-has-no-activity-result-owner-and-keeps-logical-referrer",ContextTarget.failure==null&&ContextTarget.originValid);
        check.test("context-start-keeps-real-platform-caller-token-identity",ContextTarget.callerValid);
        check.test("context-start-target-really-completes-destruction",ContextTarget.destroyed.await(8,TimeUnit.SECONDS));
    }
    private ContextStartChecks(){}
}
