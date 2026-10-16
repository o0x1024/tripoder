package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.os.Parcel;
import java.util.*;

/** Protected platform observations; media data is constrained to authority-observed volume roots. */
final class RuntimeSystemBroadcasts {
    private static final Set<String> BASIC=Set.of(Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_TICK,Intent.ACTION_BATTERY_LOW,Intent.ACTION_BATTERY_OKAY,Intent.ACTION_POWER_CONNECTED,Intent.ACTION_POWER_DISCONNECTED,android.os.BatteryManager.ACTION_CHARGING,android.os.BatteryManager.ACTION_DISCHARGING,android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,android.os.PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED,android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
    static final Set<String> STICKY=Set.of(Intent.ACTION_BATTERY_CHANGED);
    static final Set<String> MEDIA=Set.of(Intent.ACTION_MEDIA_SHARED,Intent.ACTION_MEDIA_MOUNTED,Intent.ACTION_MEDIA_UNMOUNTED,Intent.ACTION_MEDIA_EJECT,Intent.ACTION_MEDIA_REMOVED,Intent.ACTION_MEDIA_BAD_REMOVAL,Intent.ACTION_MEDIA_CHECKING,Intent.ACTION_MEDIA_NOFS,Intent.ACTION_MEDIA_UNMOUNTABLE);
    static final Set<String> PACKAGE=Set.of(Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_CHANGED,Intent.ACTION_PACKAGE_FULLY_REMOVED,Intent.ACTION_PACKAGE_DATA_CLEARED);
    static final Set<String> ACTIONS;
    static{Set<String> actions=new HashSet<>(BASIC);actions.addAll(MEDIA);actions.addAll(STICKY);actions.addAll(PACKAGE);ACTIONS=Set.copyOf(actions);}
    static boolean action(String action){return action!=null&&ACTIONS.contains(action);}
    static boolean contains(IntentFilter filter){for(int i=0;i<filter.countActions();i++)if(ACTIONS.contains(filter.getAction(i)))return true;return false;}
    static boolean only(IntentFilter filter){for(int i=0;i<filter.countActions();i++)if(!ACTIONS.contains(filter.getAction(i)))return false;return filter.countActions()>0;}
    static void validate(IntentFilter filter){
        if(!contains(filter))return;
        if(filter.countCategories()!=0||filter.countDataTypes()!=0||filter.countDataAuthorities()!=0||filter.countDataPaths()!=0)throw new UnsupportedOperationException("SYSTEM_RECEIVER_DATA_FILTER_UNSUPPORTED");
        boolean media=false,pkg=false;for(int i=0;i<filter.countActions();i++){media|=MEDIA.contains(filter.getAction(i));pkg|=PACKAGE.contains(filter.getAction(i));}
        // Android permits mixed actions and a filter with no data scheme. Preserve
        // its matching semantics; absence of a scheme does not match file/package events.
        for(int i=0;i<filter.countDataSchemes();i++){String scheme=filter.getDataScheme(i);if(!(media&&"file".equals(scheme)||pkg&&"package".equals(scheme)))throw new UnsupportedOperationException(media?"MEDIA_RECEIVER_FILTER_UNSUPPORTED":"SYSTEM_RECEIVER_DATA_FILTER_UNSUPPORTED");}
    }
    /** PACKAGE_* events are observable only for the observing guest's own package, never other installs. */
    static boolean ownPackageEvent(Intent intent,String packageName){
        if(!PACKAGE.contains(intent.getAction()))return true;
        android.net.Uri data=intent.getData();return data!=null&&packageName.equals(data.getSchemeSpecificPart());
    }
    static void permission(PackageInfo info,String permission,boolean exported){
        if(permission==null)return;
        // Registering a sender restriction does not itself require the receiver to hold it.
        if(info.permissions!=null)for(PermissionInfo declared:info.permissions)if(permission.equals(declared.name)&&info.packageName.equals(declared.packageName)){
            int base=declared.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;
            if(base==PermissionInfo.PROTECTION_SIGNATURE||base==PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM)return;
        }
        throw new SecurityException("RECEIVER_PERMISSION_NOT_OWNED:"+permission);
    }
    static boolean holds(ApkStore.BundleInstall subject,ApkStore.BundleInstall owner,String permission){
        permission(owner.info,permission,true);
        return subject.info.requestedPermissions!=null&&Arrays.asList(subject.info.requestedPermissions).contains(permission)&&subject.artifacts.signerDigests.equals(owner.artifacts.signerDigests);
    }
    static Set<String> roots(Context host){
        android.os.storage.StorageManager manager=host.getSystemService(android.os.storage.StorageManager.class);if(manager==null)throw new IllegalStateException("PLATFORM_STORAGE_OBSERVATION_UNAVAILABLE");
        Set<String> roots=new HashSet<>();for(var item:manager.getStorageVolumes()){java.io.File directory=item.getDirectory();if(directory!=null)roots.add(directory.getAbsolutePath());}
        if(roots.size()>64)throw new IllegalStateException("PLATFORM_VOLUME_LIMIT");return Set.copyOf(roots);
    }
    private static Intent envelope(Intent original){
        if(original==null||!action(original.getAction())||original.getType()!=null||original.getClipData()!=null||original.getSelector()!=null||original.getComponent()!=null)throw new SecurityException("SYSTEM_BROADCAST_ENVELOPE_INVALID");
        android.net.Uri data=original.getData();
        if(MEDIA.contains(original.getAction())){
            if(data==null||!"file".equals(data.getScheme())||data.getAuthority()!=null&&!data.getAuthority().isEmpty()||data.getQuery()!=null||data.getFragment()!=null)throw new SecurityException("MEDIA_VOLUME_URI_INVALID");
        }else if(PACKAGE.contains(original.getAction())){
            String ssp=data==null?null:data.getSchemeSpecificPart();
            if(data==null||!"package".equals(data.getScheme())||ssp==null||ssp.isEmpty()||data.getQuery()!=null||data.getFragment()!=null)throw new SecurityException("PACKAGE_URI_INVALID");
        }else if(data!=null)throw new SecurityException("SYSTEM_BROADCAST_ENVELOPE_INVALID");
        Intent result=new Intent(original.getAction()).setFlags(original.getFlags()).setData(data);result.replaceExtras(RuntimeProviderFacade.primitive(original.getExtras()));return result;
    }
    static Intent visible(Intent original,Set<String> observedRoots){
        Intent result=envelope(original);
        // The authority retains roots from registration and later accepted mount events.
        // Workers submit observations; only this authority-side check grants delivery.
        if(MEDIA.contains(result.getAction())&&!observedRoots.contains(result.getData().getPath()))throw new SecurityException("MEDIA_URI_NOT_PLATFORM_VOLUME_ROOT");
        return result;
    }
    static byte[] observation(Intent original){return parcel(envelope(original));}
    private static byte[] parcel(Intent intent){Parcel parcel=Parcel.obtain();try{intent.writeToParcel(parcel,0);if(parcel.dataSize()>48000)throw new SecurityException("SYSTEM_BROADCAST_TOO_LARGE");return parcel.marshall();}finally{parcel.recycle();}}
    static byte[] encode(Intent original,Set<String> observedRoots){return parcel(visible(original,observedRoots));}
    private RuntimeSystemBroadcasts(){}
}
