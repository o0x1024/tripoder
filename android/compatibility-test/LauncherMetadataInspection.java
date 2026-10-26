package dev.tripoder.hooklab;

import android.content.*;
import android.content.pm.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** Only public code declarations and this QA installation's component settings. */
final class LauncherMetadataInspection {
    private static JSONObject activity(ActivityInfo info)throws Exception{
        return new JSONObject().put("component",new ComponentName(info.packageName,info.name).flattenToShortString()).put("enabled",info.enabled).put("applicationEnabled",info.applicationInfo.enabled).put("exported",info.exported).put("permission",info.permission==null?JSONObject.NULL:info.permission).put("targetActivity",info.targetActivity==null?JSONObject.NULL:info.targetActivity);
    }
    static JSONObject inspect(Context host,String space,String pkg)throws Exception{
        if(!pkg.equals("com.mfashiongallery.emag"))throw new SecurityException("KNOWN_LAUNCHER_DECLARATION_REQUIRED");
        PackageManager pm=host.getPackageManager();JSONObject out=new JSONObject().put("space",space).put("package",pkg).put("readOnly",true),queries=new JSONObject();
        Intent launch=pm.getLaunchIntentForPackage(pkg);
        out.put("nativeLaunch",launch==null?JSONObject.NULL:new JSONObject().put("action",launch.getAction()).put("categories",new JSONArray(launch.getCategories()==null?java.util.Set.of():launch.getCategories())).put("flags",launch.getFlags()).put("component",launch.getComponent()==null?JSONObject.NULL:launch.getComponent().flattenToShortString()));
        for(String category:new String[]{Intent.CATEGORY_INFO,Intent.CATEGORY_LAUNCHER}){
            JSONObject modes=new JSONObject();
            for(boolean disabled:new boolean[]{false,true}){
                JSONArray rows=new JSONArray();Intent selector=new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
                for(ResolveInfo result:pm.queryIntentActivities(selector,PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER|(disabled?PackageManager.MATCH_DISABLED_COMPONENTS:0)))){
                    JSONObject item=activity(result.activityInfo).put("priority",result.priority).put("preferredOrder",result.preferredOrder).put("match",result.match);
                    if(result.filter!=null){JSONArray actions=new JSONArray(),categories=new JSONArray();for(int i=0;i<result.filter.countActions();i++)actions.put(result.filter.getAction(i));for(int i=0;i<result.filter.countCategories();i++)categories.put(result.filter.getCategory(i));item.put("actions",actions).put("categories",categories);}
                    item.put("componentSetting",pm.getComponentEnabledSetting(new ComponentName(pkg,result.activityInfo.name)));rows.put(item);
                }
                modes.put(disabled?"includingDisabled":"enabled",rows);
            }
            queries.put(category,modes);
        }
        out.put("nativeQueries",queries).put("nativeApplicationSetting",pm.getApplicationEnabledSetting(pkg));
        String apk,dataId,generation;JSONArray settings=new JSONArray();
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var c=db.rawQuery("SELECT apk,dataId,generation,sha256,descriptorSha,version FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){
                if(!c.moveToFirst())throw new IllegalStateException("QA_LAUNCHER_INSTALLATION_MISSING");apk=c.getString(0);dataId=c.getString(1);generation=c.getString(2);
                out.put("qaInstallation",new JSONObject().put("generation",generation).put("dataId",dataId).put("baseApkSha256",c.getString(3)).put("descriptorSha256",c.getString(4)).put("version",c.getLong(5)));
            }
            try(var c=db.rawQuery("SELECT component,state FROM component_settings WHERE space=? AND package=? AND dataId=? ORDER BY component",new String[]{space,pkg,dataId})){while(c.moveToNext())settings.put(new JSONObject().put("component",c.getString(0)).put("state",c.getInt(1)));}
        }
        PackageInfo archive=pm.getPackageArchiveInfo(apk,PackageManager.GET_ACTIVITIES|PackageManager.MATCH_DISABLED_COMPONENTS);
        if(archive==null||!pkg.equals(archive.packageName))throw new SecurityException("QA_LAUNCHER_ARCHIVE_IDENTITY");JSONArray declarations=new JSONArray();
        if(archive.activities!=null)for(ActivityInfo info:archive.activities)if(info.name.equals("com.mfashiongallery.emag.app.main.AppMainActivity")||info.targetActivity!=null)declarations.put(activity(info));
        return out.put("qaArchiveActivities",declarations).put("qaComponentSettings",settings);
    }
    private LauncherMetadataInspection(){}
}
