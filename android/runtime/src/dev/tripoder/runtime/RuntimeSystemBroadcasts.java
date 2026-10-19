package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import android.os.Parcel;
import java.util.*;

/** Protected platform observations; media data is constrained to authority-observed volume roots. */
final class RuntimeSystemBroadcasts {
    private static final String PROXY="android.intent.action.PROXY_CHANGE",PROXY_INFO="android.intent.extra.PROXY_INFO";
    private static final Set<String> BASIC=Set.of(
        Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,
        Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED,PROXY,
        Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_DATE_CHANGED,
        Intent.ACTION_LOCALE_CHANGED,Intent.ACTION_CONFIGURATION_CHANGED,Intent.ACTION_HEADSET_PLUG,
        Intent.ACTION_SHUTDOWN,Intent.ACTION_CLOSE_SYSTEM_DIALOGS,Intent.ACTION_DREAMING_STARTED,Intent.ACTION_DREAMING_STOPPED,
        android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED,android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED,
        "android.hardware.usb.action.USB_ACCESSORY_ATTACHED","android.hardware.usb.action.USB_ACCESSORY_DETACHED","android.hardware.usb.action.USB_STATE",
        android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED,android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
        "android.bluetooth.device.action.ACL_CONNECTED","android.bluetooth.device.action.ACL_DISCONNECTED",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED","android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        Intent.ACTION_TIME_TICK,Intent.ACTION_BATTERY_LOW,Intent.ACTION_BATTERY_OKAY,
        Intent.ACTION_POWER_CONNECTED,Intent.ACTION_POWER_DISCONNECTED,
        android.os.BatteryManager.ACTION_CHARGING,android.os.BatteryManager.ACTION_DISCHARGING,
        android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,android.os.PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED,android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
        android.security.KeyChain.ACTION_KEYCHAIN_CHANGED,android.security.KeyChain.ACTION_TRUST_STORE_CHANGED,"android.security.action.KEY_ACCESS_CHANGED","android.security.STORAGE_CHANGED",
        android.net.ConnectivityManager.CONNECTIVITY_ACTION,"android.net.conn.INET_CONDITION_ACTION","android.net.conn.BACKGROUND_DATA_SETTING_CHANGED","android.net.conn.RESTRICT_BACKGROUND_CHANGED",
        android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION,android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION,android.net.wifi.WifiManager.RSSI_CHANGED_ACTION,android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION,android.net.wifi.WifiManager.SUPPLICANT_STATE_CHANGED_ACTION,android.net.wifi.WifiManager.NETWORK_IDS_CHANGED_ACTION,
        android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY,android.media.AudioManager.RINGER_MODE_CHANGED_ACTION,android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG,"android.media.VOLUME_CHANGED_ACTION","android.media.STREAM_MUTE_CHANGED_ACTION","android.media.STREAM_DEVICES_CHANGED_ACTION",
        "android.intent.action.SIM_STATE_CHANGED","android.intent.action.ANY_DATA_STATE","android.intent.action.SERVICE_STATE","android.intent.action.PHONE_STATE","android.telephony.action.CARRIER_CONFIG_CHANGED","android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED","android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED","android.intent.action.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED",
        "android.nfc.action.ADAPTER_STATE_CHANGED","android.location.PROVIDERS_CHANGED","android.location.MODE_CHANGED",
        "android.os.action.SETTING_RESTORED","android.intent.action.WALLPAPER_CHANGED","android.intent.action.INPUT_METHOD_CHANGED","android.intent.action.DOCK_EVENT"
    );
    static final Set<String> STICKY=Set.of(
        Intent.ACTION_BATTERY_CHANGED,PROXY,Intent.ACTION_HEADSET_PLUG,
        android.net.ConnectivityManager.CONNECTIVITY_ACTION,
        android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION,android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION,android.net.wifi.WifiManager.RSSI_CHANGED_ACTION,android.net.wifi.WifiManager.SUPPLICANT_STATE_CHANGED_ACTION,
        android.media.AudioManager.RINGER_MODE_CHANGED_ACTION,android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG,
        "android.hardware.usb.action.USB_STATE","android.intent.action.SIM_STATE_CHANGED","android.intent.action.NETWORK_SET_TIME","android.intent.action.NETWORK_SET_TIMEZONE"
    );
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
    /** True only for a custom permission the owner app itself declares at signature level;
     * such a permission couples delivery to a shared signer. A platform or normal permission
     * (e.g. android.permission.INTERNET, which apps routinely guard receivers with) is not
     * owned this way and imposes no signer coupling in the same-UID model. */
    static boolean ownsSignaturePermission(PackageInfo info,String permission){
        if(permission==null||info.permissions==null)return false;
        for(PermissionInfo declared:info.permissions)if(permission.equals(declared.name)&&info.packageName.equals(declared.packageName)){
            int base=declared.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;
            if(base==PermissionInfo.PROTECTION_SIGNATURE||base==PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM)return true;
        }
        return false;
    }
    /** Delivery is the single enforcement point for a broadcast permission: registration and
     * send no longer pre-reject a permission the app does not own. A custom signature permission
     * is held only by a same-signer app that requests it; any other permission is a best-effort
     * sender restriction with no signer coupling, held when the manifest requests it. Rejecting
     * an unowned permission at registration (real apps guard with INTERNET) only turned a benign
     * restriction into a crash, and same-UID guests are not a trust boundary to defend here. */
    static boolean holds(ApkStore.BundleInstall subject,ApkStore.BundleInstall owner,String permission){
        boolean requested=subject.info.requestedPermissions!=null&&Arrays.asList(subject.info.requestedPermissions).contains(permission);
        if(ownsSignaturePermission(owner.info,permission))return requested&&subject.artifacts.signerDigests.equals(owner.artifacts.signerDigests);
        return requested;
    }
    static Set<String> roots(Context host){
        android.os.storage.StorageManager manager=host.getSystemService(android.os.storage.StorageManager.class);if(manager==null)throw new IllegalStateException("PLATFORM_STORAGE_OBSERVATION_UNAVAILABLE");
        Set<String> roots=new HashSet<>();for(var item:manager.getStorageVolumes()){java.io.File directory=item.getDirectory();if(directory!=null)roots.add(directory.getAbsolutePath());}
        if(roots.size()>64)throw new IllegalStateException("PLATFORM_VOLUME_LIMIT");return Set.copyOf(roots);
    }
    /** System broadcasts may carry boot-classpath platform Parcelables (e.g., NetworkInfo, WifiInfo, ProxyInfo); never descriptors or binders. */
    private static Bundle extras(Intent original){
        Bundle input=original.getExtras();
        if(input==null)return null;
        if(PROXY.equals(original.getAction())&&input.containsKey(PROXY_INFO)){
            Object value=input.get(PROXY_INFO);if(value!=null&&!(value instanceof android.net.ProxyInfo))throw new SecurityException("PROXY_INFO_EXTRA_INVALID");
        }
        return systemBundle(input,0);
    }
    private static Bundle systemBundle(Bundle input,int depth){
        if(input==null)return null;if(depth>3||input.size()>64)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");
        Bundle copy=new Bundle();
        for(String key:input.keySet()){
            if(key.length()>200)throw new IllegalArgumentException("PROVIDER_KEY_TOO_LONG");
            Object v=input.get(key);
            if(v==null)copy.putString(key,null);
            else if(v instanceof String){if(((String)v).length()>8192)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putString(key,(String)v);}
            else if(v instanceof Integer)copy.putInt(key,(Integer)v);else if(v instanceof Long)copy.putLong(key,(Long)v);else if(v instanceof Boolean)copy.putBoolean(key,(Boolean)v);
            else if(v instanceof Double)copy.putDouble(key,(Double)v);else if(v instanceof Float)copy.putFloat(key,(Float)v);else if(v instanceof Short)copy.putShort(key,(Short)v);else if(v instanceof Byte)copy.putByte(key,(Byte)v);else if(v instanceof Character)copy.putChar(key,(Character)v);
            else if(v instanceof Bundle)copy.putBundle(key,systemBundle((Bundle)v,depth+1));
            else if(v instanceof byte[]){if(((byte[])v).length>16384)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putByteArray(key,((byte[])v).clone());}
            else if(v instanceof int[]){if(((int[])v).length>4096)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putIntArray(key,((int[])v).clone());}
            else if(v instanceof long[]){if(((long[])v).length>4096)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putLongArray(key,((long[])v).clone());}
            else if(v instanceof boolean[]){if(((boolean[])v).length>4096)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putBooleanArray(key,((boolean[])v).clone());}
            else if(v instanceof float[]){if(((float[])v).length>4096)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putFloatArray(key,((float[])v).clone());}
            else if(v instanceof double[]){if(((double[])v).length>4096)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");copy.putDoubleArray(key,((double[])v).clone());}
            else if(v instanceof String[]){String[] array=(String[])v;if(array.length>64)throw new IllegalArgumentException("PROVIDER_ARRAY_TOO_LONG");for(String value:array)if(value!=null&&value.length()>8192)throw new IllegalArgumentException("PROVIDER_STRING_TOO_LONG");copy.putStringArray(key,array.clone());}
            else if(v instanceof android.os.Parcelable&&platformType(v.getClass()))copy.putParcelable(key,(android.os.Parcelable)v);
            else if(v instanceof java.io.Serializable&&platformType(v.getClass()))copy.putSerializable(key,(java.io.Serializable)v);
            else throw new IllegalArgumentException("PROVIDER_EXTRA_TYPE_UNSUPPORTED:"+key+":"+v.getClass().getName());
        }
        Parcel p=Parcel.obtain();try{p.writeBundle(copy);if(p.hasFileDescriptors())throw new IllegalArgumentException("PROVIDER_EXTRA_DESCRIPTOR_UNSUPPORTED");if(p.dataSize()>32768)throw new IllegalArgumentException("PROVIDER_BUNDLE_TOO_LARGE");}finally{p.recycle();}
        return copy;
    }
    private static boolean platformType(Class<?> type){
        if(type.getClassLoader()==null)return true;
        RuntimeEngine guest=RuntimeEngine.current;
        if(guest!=null&&guest.ownsLoader(type.getClassLoader()))return false;
        String name=type.getName();
        return name.startsWith("android.")||name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("com.android.");
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
        Intent result=new Intent(original.getAction()).setFlags(original.getFlags()).setData(data);result.replaceExtras(extras(original));return result;
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
