package dev.tripoder.runtime;
import android.content.*;

/** Used only inside platform ContentProvider.attachInfo, after the guest override has run. */
final class RuntimeProviderAttachContext extends ContextWrapper {
    private Object appOps;
    RuntimeProviderAttachContext(Context guest,Context host){super(guest);appOps=host.getSystemService(Context.APP_OPS_SERVICE);if(appOps==null)throw new IllegalStateException("PROVIDER_PLATFORM_APPOPS_MISSING");}
    @Override public Object getSystemService(String name){if(Context.APP_OPS_SERVICE.equals(name)&&appOps!=null){Object service=appOps;appOps=null;return service;}return super.getSystemService(name);}
}
