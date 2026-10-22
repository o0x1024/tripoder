package dev.tripoder.selfhook;

import android.content.Context;
import java.lang.reflect.Field;
import java.util.Objects;

/** Exact prompt field from the pinned boot registry. */
public final class BiometricFrameworkAccess {
    private static Field context;
    private static boolean attempted;
    private BiometricFrameworkAccess(){}
    public static synchronized void prepare()throws Exception{
        if(attempted)throw new IllegalStateException("BIOMETRIC_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;
        context=FixedFrameworkMembers.field(43,61,"mContext","Landroid/content/Context;",false);
    }
    public static Context context(Object prompt){
        if(context==null)throw new IllegalStateException("BIOMETRIC_FRAMEWORK_NOT_READY");
        return (Context)Objects.requireNonNull(FixedFrameworkMembers.get(context,Objects.requireNonNull(prompt)),"BIOMETRIC_CONTEXT_REQUIRED");
    }
}
