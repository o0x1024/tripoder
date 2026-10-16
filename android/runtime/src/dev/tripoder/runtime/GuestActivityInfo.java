package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.pm.*;
import android.os.Parcel;

/** Query a declared component, independently of which process happens to be running. */
final class GuestActivityInfo {
    static ActivityInfo get(RuntimeEngine engine,ComponentName component,long flags)throws PackageManager.NameNotFoundException{
        if(component==null)throw new IllegalArgumentException("ACTIVITY_COMPONENT_REQUIRED");
        if(!engine.packageName.equals(component.getPackageName()))throw new UnsupportedOperationException("OTHER_PACKAGE_QUERY_UNSUPPORTED:"+component.getPackageName());
        long supported=PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE|PackageManager.MATCH_DIRECT_BOOT_AUTO;
        if((flags&~supported)!=0)throw new UnsupportedOperationException("ACTIVITY_INFO_FLAGS_UNSUPPORTED:0x"+Long.toHexString(flags));
        ActivityInfo selected=null;if(engine.packageArchive.activities!=null)for(ActivityInfo value:engine.packageArchive.activities)if(value.name.equals(component.getClassName())){selected=value;break;}
        long direct=flags&(PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        // ApplicationPackageManager normally expands AUTO before IPC. A direct local
        // endpoint query still uses the actual user unlock state, never a fixed value.
        if(direct==0&&(flags&PackageManager.MATCH_DIRECT_BOOT_AUTO)!=0)direct=PackageManager.MATCH_DIRECT_BOOT_AWARE|(engine.host.getSystemService(android.os.UserManager.class).isUserUnlocked()?PackageManager.MATCH_DIRECT_BOOT_UNAWARE:0);
        if(selected==null||(!selected.enabled||!engine.info.enabled)&&(flags&PackageManager.MATCH_DISABLED_COMPONENTS)==0||direct!=0&&(direct&(selected.directBootAware?PackageManager.MATCH_DIRECT_BOOT_AWARE:PackageManager.MATCH_DIRECT_BOOT_UNAWARE))==0)throw new PackageManager.NameNotFoundException(component.flattenToString());
        Parcel parcel=Parcel.obtain();ActivityInfo result;try{selected.writeToParcel(parcel,0);parcel.setDataPosition(0);result=ActivityInfo.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
        result.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&PackageManager.GET_META_DATA)!=0);
        if((flags&PackageManager.GET_META_DATA)==0)result.metaData=null;
        return result;
    }
    private GuestActivityInfo(){}
}
