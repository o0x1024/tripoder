package dev.tripoder.runtime;

import android.app.Application;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.PhysicalApplication;

/** The single fixed JNI physical observer. Native loading remains the bootstrap owner's duty. */
final class RuntimeSelfHookPhysical implements RuntimeSelfHookApplication.PhysicalAccess {
    static final RuntimeSelfHookPhysical INSTANCE=new RuntimeSelfHookPhysical();
    private RuntimeSelfHookPhysical(){}
    public void prepare(Application host,FrameworkAccess.Members members){PhysicalApplication.prepare(host,members);}
    public void check(Application host){PhysicalApplication.check(host);}
}
