package dev.tripoder.runtime;

import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The kernel process name is established before guest initialization. Binder
 * ownership, UID, process generation and AMS slot identity remain separate. */
final class RuntimeProcessIdentity {
    private RuntimeProcessIdentity(){}
    static void establish(String name)throws Exception{
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("PROCESS_NAME_MAIN_THREAD_REQUIRED");
        dev.tripoder.selfhook.FrameworkAccess.setProcessArgV0(name);
        byte[] actual=RuntimeSelfHookStorage.readPhysicalProc("/proc/self/cmdline",65536);
        int length=0;while(length<actual.length&&actual[length]!=0)length++;
        if(!Arrays.equals(name.getBytes(StandardCharsets.UTF_8),Arrays.copyOf(actual,length)))
            throw new IllegalStateException("PROCESS_NAME_KERNEL_IDENTITY_MISMATCH");
    }
}
