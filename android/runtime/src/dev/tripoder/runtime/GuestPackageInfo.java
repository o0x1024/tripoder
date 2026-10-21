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
        if((flags&~0xFFFFFFFFL)!=0)throw new UnsupportedOperationException("PACKAGE_INFO_FLAGS_UNSUPPORTED:0x"+Long.toHexString(flags));
        // Wall-clock adjustment may make an update timestamp earlier than first install.
        if(engine.firstInstallTime<=0||engine.lastUpdateTime<=0)throw new UnsupportedOperationException("INSTALL_HISTORY_UNAVAILABLE");
        PackageInfo result=new PackageInfo();
        result.packageName=engine.packageName;
        result.splitNames=engine.packageArchive.splitNames!=null?engine.packageArchive.splitNames.clone():null;
        result.versionCode=engine.packageArchive.versionCode;
        result.setLongVersionCode(engine.packageArchive.getLongVersionCode());
        result.versionName=engine.packageArchive.versionName;
        result.baseRevisionCode=engine.packageArchive.baseRevisionCode;
        result.splitRevisionCodes=engine.packageArchive.splitRevisionCodes!=null?engine.packageArchive.splitRevisionCodes.clone():null;
        result.sharedUserId=engine.packageArchive.sharedUserId;
        result.sharedUserLabel=engine.packageArchive.sharedUserLabel;
        result.firstInstallTime=engine.firstInstallTime;
        result.lastUpdateTime=engine.lastUpdateTime;
        result.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&PackageManager.GET_META_DATA)!=0);
        if((flags&PackageManager.GET_ACTIVITIES)!=0)result.activities=components(engine.packageArchive.activities,flags,engine,ActivityInfo[]::new,ActivityInfo::new);
        if((flags&PackageManager.GET_RECEIVERS)!=0)result.receivers=components(engine.packageArchive.receivers,flags,engine,ActivityInfo[]::new,ActivityInfo::new);
        if((flags&PackageManager.GET_SERVICES)!=0)result.services=components(engine.packageArchive.services,flags,engine,ServiceInfo[]::new,ServiceInfo::new);
        if((flags&PackageManager.GET_PROVIDERS)!=0){
            result.providers=components(engine.packageArchive.providers,flags,engine,ProviderInfo[]::new,ProviderInfo::new);
            if(result.providers!=null&&(flags&PackageManager.GET_URI_PERMISSION_PATTERNS)==0)for(ProviderInfo provider:result.providers)provider.uriPermissionPatterns=null;
        }
        if((flags&PackageManager.GET_CONFIGURATIONS)!=0){
            result.configPreferences=engine.packageArchive.configPreferences!=null?engine.packageArchive.configPreferences.clone():null;
            result.reqFeatures=engine.packageArchive.reqFeatures!=null?engine.packageArchive.reqFeatures.clone():null;
            result.featureGroups=engine.packageArchive.featureGroups!=null?engine.packageArchive.featureGroups.clone():null;
        }
        if((flags&PackageManager.GET_SIGNATURES)!=0)result.signatures=engine.packageArchive.signatures!=null?engine.packageArchive.signatures.clone():null;
        if((flags&PackageManager.GET_SIGNING_CERTIFICATES)!=0)result.signingInfo=engine.packageArchive.signingInfo;
        if((flags&PackageManager.GET_PERMISSIONS)!=0){
            result.permissions=engine.packageArchive.permissions!=null?engine.packageArchive.permissions.clone():null;
            result.requestedPermissions=engine.packageArchive.requestedPermissions!=null?engine.packageArchive.requestedPermissions.clone():null;
            result.requestedPermissionsFlags=engine.packageArchive.requestedPermissionsFlags!=null?engine.packageArchive.requestedPermissionsFlags.clone():null;
        }
        if((flags&PackageManager.GET_GIDS)!=0){
            result.gids=engine.packageArchive.gids!=null?engine.packageArchive.gids.clone():null;
        }
        return result;
    }
    private static <T extends ComponentInfo>T[] components(T[] input,long flags,RuntimeEngine engine,IntFunction<T[]> array,java.util.function.Function<T,T> copier){
        if(input==null)return null;List<T> output=new ArrayList<>();long direct=flags&(PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if(direct==0&&(flags&PackageManager.MATCH_DIRECT_BOOT_AUTO)!=0)direct=PackageManager.MATCH_DIRECT_BOOT_AWARE|(engine.host.getSystemService(android.os.UserManager.class).isUserUnlocked()?PackageManager.MATCH_DIRECT_BOOT_UNAWARE:0);
        for(T original:input){
            if((!original.enabled||!engine.info.enabled)&&(flags&PackageManager.MATCH_DISABLED_COMPONENTS)==0)continue;
            if(direct!=0&&(direct&(original.directBootAware?PackageManager.MATCH_DIRECT_BOOT_AWARE:PackageManager.MATCH_DIRECT_BOOT_UNAWARE))==0)continue;
            T copy=copier.apply(original);
            copy.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&PackageManager.GET_META_DATA)!=0);
            if((flags&PackageManager.GET_META_DATA)==0)copy.metaData=null;
            else if(original.metaData!=null)copy.metaData=original.metaData.deepCopy();
            output.add(copy);
        }return output.isEmpty()?null:output.toArray(array.apply(output.size()));
    }
    private GuestPackageInfo(){}
}
