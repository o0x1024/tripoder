package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONObject;

/** Only the installed owned native probe's public code identity, never application contents. */
final class NativePublicationInspection {
    static JSONObject platform(Context host)throws Exception{
        if(!host.getPackageName().startsWith("dev.tripoder.compat"))throw new SecurityException("SEPARATE_QA_REQUIRED");
        var source=host.getPackageManager().getPackageInfo("dev.tripoder.isolatedfdprobe",android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES));
        return new JSONObject().put("readOnly",true).put("package",source.packageName).put("version",source.getLongVersionCode()).put("sourceUid",source.applicationInfo.uid).put("containerUid",android.os.Process.myUid()).put("packageSplitNames",source.splitNames==null?JSONObject.NULL:new org.json.JSONArray(source.splitNames)).put("applicationSplitNames",source.applicationInfo.splitNames==null?JSONObject.NULL:new org.json.JSONArray(source.applicationInfo.splitNames)).put("splitSourceDirsCount",source.applicationInfo.splitSourceDirs==null?JSONObject.NULL:source.applicationInfo.splitSourceDirs.length).put("sourceInDataApp",source.applicationInfo.sourceDir.startsWith("/data/app/")).put("nativeInDataApp",source.applicationInfo.nativeLibraryDir.startsWith("/data/app/")).put("nativeMode",(source.applicationInfo.flags&0x10000000)!=0?"DIRECTORY":"APK");
    }
    static JSONObject inspect(Context host,String space,String pkg,String generation)throws Exception{
        if(!host.getPackageName().startsWith("dev.tripoder.compat")||!space.equals("paired-dev-tripoder-isolatedfdprobe")||!pkg.equals("dev.tripoder.isolatedfdprobe"))throw new SecurityException("OWNED_NATIVE_PROBE_REQUIRED");
        var original=host.getPackageManager().getPackageInfo(pkg,android.content.pm.PackageManager.PackageInfoFlags.of(0));
        if(original.applicationInfo==null||original.applicationInfo.uid==android.os.Process.myUid()||original.splitNames!=null&&original.splitNames.length!=0)throw new SecurityException("OWNED_DIFFERENT_PHYSICAL_PACKAGE_REQUIRED");
        MessageDigest sha=MessageDigest.getInstance("SHA-256");try(var input=new FileInputStream(original.applicationInfo.sourceDir)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)sha.update(bytes,0,count);}
        String digest=HexFormat.of().formatHex(sha.digest());String data;
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY);var row=db.rawQuery("SELECT sha256,dataId FROM installs WHERE space=? AND package=? AND generation=? AND state='COMMITTED'",new String[]{space,pkg,generation})){
            if(!row.moveToFirst()||!digest.equals(row.getString(0)))throw new SecurityException("OWNED_NATIVE_PUBLIC_APK_CHANGED");data=row.getString(1);
        }
        return new JSONObject().put("sourceUid",original.applicationInfo.uid).put("containerUid",android.os.Process.myUid()).put("differentPhysicalPackage",true).put("publicApkEqualsGuestApk",true).put("publicApkSha256",digest).put("dataIncarnation",data).put("nativeMode",(original.applicationInfo.flags&0x10000000)!=0?"DIRECTORY":"APK").put("accountDataRead",false);
    }
    private NativePublicationInspection(){}
}
