package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.os.Parcel;
import java.util.Objects;

/** Dynamic scope for one admitted publication, including its private delegated providers. */
final class RuntimeProviderAttachments {
    private static final ThreadLocal<Scope> current=new ThreadLocal<>();
    private static final class Scope {
        final RuntimeEngine engine;final Context context;final ProviderInfo declaration;
        Scope(RuntimeEngine engine,Context context,ProviderInfo info){this.engine=engine;this.context=context;declaration=new ProviderInfo(info);}
    }
    static void attach(RuntimeEngine engine,ContentProvider root,Context context,ProviderInfo info){
        if(!engine.providers.owns(root))throw new SecurityException("PROVIDER_PUBLICATION_NOT_OWNED");
        ProviderInfo instance=instanceInfo(info);
        Scope previous=current.get();current.set(new Scope(engine,context,info));
        try{root.attachInfo(context,instance);}finally{if(previous==null)current.remove();else current.set(previous);}
    }
    /** Guest attach configuration must never mutate the admitted entry or a
     * nested ApplicationInfo retained for future requests and terminal errors. */
    private static ProviderInfo instanceInfo(ProviderInfo declaration){
        if(declaration==null||declaration.getClass()!=ProviderInfo.class)throw new SecurityException("PROVIDER_ATTACH_DECLARATION_REQUIRED");
        Parcel parcel=Parcel.obtain();try{declaration.writeToParcel(parcel,0);parcel.setDataPosition(0);return ProviderInfo.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
    }
    static boolean scoped(ContentProvider provider){Scope scope=current.get();return scope!=null&&scope.engine.ownsLoader(provider.getClass().getClassLoader());}
    static Context platformContext(ContentProvider provider,Context context,ProviderInfo info,boolean testing){
        Scope scope=current.get();
        if(scope==null||!scoped(provider)||scope.context!=context||testing||info==null)throw new SecurityException("PROVIDER_ATTACH_SCOPE_MISMATCH");
        ProviderInfo expected=scope.declaration;
        if(!Objects.equals(info.packageName,expected.packageName)||!Objects.equals(info.name,expected.name)||!Objects.equals(info.authority,expected.authority)||!Objects.equals(info.processName,expected.processName))throw new SecurityException("PROVIDER_ATTACH_IDENTITY_CHANGED");
        // A private publication remains private in the broker's immutable
        // manifest at acquisition AND invocation. Its instance may configure
        // SDK superclass requirements; this creates no exported capability.
        // Externally published policy retains the exact admitted contract.
        if(expected.exported&&(info.exported!=expected.exported||!Objects.equals(info.readPermission,expected.readPermission)||!Objects.equals(info.writePermission,expected.writePermission)))throw new SecurityException("PROVIDER_ATTACH_IDENTITY_CHANGED");
        // A private delegate is not published, registered, or granted new authorities.
        return new RuntimeProviderAttachContext(context,scope.engine.host);
    }
    private RuntimeProviderAttachments(){}
}
