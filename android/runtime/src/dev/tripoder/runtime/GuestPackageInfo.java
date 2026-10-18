package dev.tripoder.runtime;

import android.content.pm.*;
import android.os.Parcel;
import java.util.*;
import java.util.function.IntFunction;

/** A defensive projection of one verified artifact and its logical install receipt. */
final class GuestPackageInfo {
    /** Only this guest package is visible; any other name is absent, exactly as Android reports a package the caller cannot see. */
    static PackageInfo get(RuntimeEngine engine,String name,long flags)throws PackageManager.NameNotFoundException{
        if(!engine.packageName.equals(name))throw new PackageManager.NameNotFoundException(String.valueOf(name));
        long supported=PackageManager.GET_CONFIGURATIONS|PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_META_DATA|PackageManager.GET_SIGNATURES|PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_URI_PERMISSION_PATTERNS|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        if((flags&~supported)!=0)throw new UnsupportedOperationException("PACKAGE_INFO_FLAGS_UNSUPPORTED:0x"+Long.toHexString(flags));
        // Wall-clock adjustment may make an update timestamp earlier than first install.
        if(engine.firstInstallTime<=0||engine.lastUpdateTime<=0)throw new UnsupportedOperationException("INSTALL_HISTORY_UNAVAILABLE");
        Parcel parcel=Parcel.obtain();PackageInfo result;
        try{engine.packageArchive.writeToParcel(parcel,0);parcel.setDataPosition(0);result=PackageInfo.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
        result.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&PackageManager.GET_META_DATA)!=0);
        result.firstInstallTime=engine.firstInstallTime;result.lastUpdateTime=engine.lastUpdateTime;
        result.activities=(flags&PackageManager.GET_ACTIVITIES)==0?null:components(result.activities,flags,engine,ActivityInfo[]::new);
        result.receivers=(flags&PackageManager.GET_RECEIVERS)==0?null:components(result.receivers,flags,engine,ActivityInfo[]::new);
        result.services=(flags&PackageManager.GET_SERVICES)==0?null:components(result.services,flags,engine,ServiceInfo[]::new);
        result.providers=(flags&PackageManager.GET_PROVIDERS)==0?null:components(result.providers,flags,engine,ProviderInfo[]::new);
        if(result.providers!=null&&(flags&PackageManager.GET_URI_PERMISSION_PATTERNS)==0)for(ProviderInfo provider:result.providers)provider.uriPermissionPatterns=null;
        if((flags&PackageManager.GET_CONFIGURATIONS)==0){result.configPreferences=null;result.reqFeatures=null;}
        result.permissions=null;result.requestedPermissions=null;result.requestedPermissionsFlags=null;
        if((flags&PackageManager.GET_SIGNATURES)==0)result.signatures=null;
        if((flags&PackageManager.GET_SIGNING_CERTIFICATES)==0)result.signingInfo=null;
        return result;
    }
    private static <T extends ComponentInfo>T[] components(T[] input,long flags,RuntimeEngine engine,IntFunction<T[]> array){
        if(input==null)return null;List<T> output=new ArrayList<>();long direct=flags&(PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        for(T value:input){
            if((!value.enabled||!engine.info.enabled)&&(flags&PackageManager.MATCH_DISABLED_COMPONENTS)==0)continue;
            if(direct!=0&&(direct&(value.directBootAware?PackageManager.MATCH_DIRECT_BOOT_AWARE:PackageManager.MATCH_DIRECT_BOOT_UNAWARE))==0)continue;
            value.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&PackageManager.GET_META_DATA)!=0);
            if((flags&PackageManager.GET_META_DATA)==0)value.metaData=null;output.add(value);
        }return output.isEmpty()?null:output.toArray(array.apply(output.size()));
    }
    private GuestPackageInfo(){}
}
