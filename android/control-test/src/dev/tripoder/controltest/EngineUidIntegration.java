package dev.tripoder.controltest;

import android.os.*;

public final class EngineUidIntegration extends ControlTestBase {
    @Override void test()throws Exception{
        IBinder entry=engine();check("engine-uid-cannot-become-controller",rejected(()->connect(entry,life()),SecurityException.class,"CONTROL_CALLER_REJECTED"),android.os.Process.myUid());
    }
}
