package dev.tripoder.runtime;

import android.content.ContentResolver;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.net.Uri;
import java.util.*;

/**
 * Content observation through the guest's resolver.
 *
 * <p>A registration is not a data read, but it is still an authority lookup: the platform resolves the authority to the
 * package it has installed, which for a guest authority is a different application (or nothing at all). Sending the
 * guest's own authority to the platform therefore produces a permission denial on the host's UID. Registrations are
 * routed by ownership instead: guest authorities stay inside this runtime and are notified by the guest's own writes,
 * while every other authority keeps the platform's real registration.
 */
final class RuntimeSettingsObservers {
    private record Registration(Uri uri,boolean descendants){}
    private final ContentResolver system;
    private final RuntimeProviderIndex guests;
    private final Set<ContentObserver> platform=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ContentObserver,List<Registration>> local=new IdentityHashMap<>();

    RuntimeSettingsObservers(ContentResolver system,RuntimeProviderIndex guests){this.system=system;this.guests=guests;}

    synchronized void register(Uri uri,boolean descendants,ContentObserver observer,int user){
        if(uri==null||!"content".equals(uri.getScheme()))throw new UnsupportedOperationException("PROVIDER_URI_UNSUPPORTED");
        if(observer==null||user!=android.os.Process.myUserHandle().hashCode())throw new UnsupportedOperationException("SETTINGS_OBSERVER_SCOPE_UNSUPPORTED");
        if(guestAuthority(uri)){
            local.computeIfAbsent(observer,ignored->new ArrayList<>()).add(new Registration(uri,descendants));
            return;
        }
        system.registerContentObserver(uri,descendants,observer);platform.add(observer);
    }
    synchronized void unregister(ContentObserver observer){
        if(local.remove(observer)!=null)return;
        if(!platform.contains(observer))throw new UnsupportedOperationException("PROVIDER_OBSERVER_NOT_OWNED");
        system.unregisterContentObserver(observer);platform.remove(observer);
    }
    /**
     * A write to a guest authority is only observable inside this process: the platform never hosted the provider and
     * has no observer registry for it. Matching follows the platform's contract (exact URI, or a descendant when the
     * registration asked for descendants) and dispatches on the observer's own handler.
     */
    synchronized boolean notifyLocal(Uri uri){
        if(uri==null||!guestAuthority(uri))return false;
        String target=uri.toString();
        for(var entry:local.entrySet())for(Registration registration:entry.getValue()){
            String registered=registration.uri().toString();
            if(target.equals(registered)||(registration.descendants()&&target.startsWith(registered))){
                entry.getKey().dispatchChange(false,uri);
            }
        }
        return true;
    }
    private boolean guestAuthority(Uri uri){
        String authority=uri.getAuthority();
        if(authority==null)return false;
        ProviderInfo info=guests.authority(authority);
        return info!=null;
    }
}
