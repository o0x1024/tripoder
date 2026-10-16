package dev.tripoder.runtime;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import java.util.*;

/** Explicit settings-only UI capability; registrations never grant access to guest authorities. */
final class RuntimeSettingsObservers {
    private final ContentResolver system;
    private final Set<ContentObserver> owned=Collections.newSetFromMap(new IdentityHashMap<>());
    RuntimeSettingsObservers(ContentResolver system){this.system=system;}
    synchronized void register(Uri uri,boolean descendants,ContentObserver observer,int user){
        SettingsReadProvider.uri(uri);
        if(observer==null||user!=android.os.Process.myUserHandle().hashCode())throw new UnsupportedOperationException("SETTINGS_OBSERVER_SCOPE_UNSUPPORTED");
        system.registerContentObserver(uri,descendants,observer);owned.add(observer);
    }
    synchronized void unregister(ContentObserver observer){
        if(!owned.contains(observer))throw new UnsupportedOperationException("PROVIDER_OBSERVER_NOT_OWNED");
        system.unregisterContentObserver(observer);owned.remove(observer);
    }
}
