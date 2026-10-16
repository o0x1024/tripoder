package dev.tripoder.runtime;

import android.content.ContentProvider;
import android.content.AttributionSource;
import android.os.Bundle;
import java.util.*;

/** Logical callback identity is scoped separately from the real OS Binder identity. */
public final class RuntimeProviderIdentity {
    private static final ThreadLocal<Scope> current=new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        final ContentProvider provider;final Bundle caller;final Scope prior;final Deque<Object> cleared=new ArrayDeque<>();
        Scope(ContentProvider provider,Bundle caller){this.provider=provider;this.caller=caller;prior=current.get();current.set(this);}
        @Override public void close(){if(current.get()!=this)throw new IllegalStateException("PROVIDER_IDENTITY_STACK_CORRUPT");current.set(prior);}
    }
    static Scope enter(ContentProvider provider,Bundle caller){return new Scope(provider,caller);}
    public static boolean owns(ContentProvider provider){return RuntimeEngine.current!=null&&RuntimeEngine.current.providers.owns(provider);}
    public static String callingPackage(ContentProvider provider){Scope scope=current.get();return scope==null||scope.provider!=provider||!scope.cleared.isEmpty()?null:scope.caller.getString("callerPackage");}
    public static AttributionSource attribution(ContentProvider provider){Scope scope=current.get();if(callingPackage(provider)==null)return null;return new AttributionSource.Builder(scope.caller.getInt("callerUid")).setPackageName(scope.caller.getString("callerPackage")).build();}
    public static void clear(ContentProvider provider,Object token){Scope scope=current.get();if(scope==null||scope.provider!=provider)throw new IllegalStateException("PROVIDER_IDENTITY_SCOPE_MISSING");scope.cleared.push(token);}
    public static void restore(ContentProvider provider,Object token){Scope scope=current.get();if(scope==null||scope.provider!=provider||scope.cleared.isEmpty()||scope.cleared.peek()!=token)throw new SecurityException("PROVIDER_IDENTITY_RESTORE_MISMATCH");scope.cleared.pop();}
    private RuntimeProviderIdentity(){}
}
