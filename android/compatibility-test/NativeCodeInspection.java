package dev.tripoder.hooklab;

import android.content.*;
import android.content.pm.*;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Public APK bytes/declarations and exact QA code receipt; no target application data. */
final class NativeCodeInspection {
    private static String sha(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static JSONObject inspect(Context host,String space,String pkg)throws Exception{
        JSONObject out=new JSONObject().put("package",pkg).put("space",space).put("readOnly",true);
        String apk,descriptorSha,generation;
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY);
            var cursor=db.rawQuery("SELECT apk,descriptorSha,generation,version,sha256,signer,state FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){
            if(!cursor.moveToFirst())throw new IllegalStateException("QA_CODE_INSTALLATION_MISSING");apk=cursor.getString(0);descriptorSha=cursor.getString(1);generation=cursor.getString(2);
            if(!cursor.getString(6).equals("COMMITTED"))throw new IllegalStateException("QA_CODE_COMMITTED_INSTALLATION_REQUIRED");
            out.put("installation",new JSONObject().put("generation",generation).put("version",cursor.getLong(3)).put("baseApkSha256",cursor.getString(4)).put("signer",cursor.getString(5)).put("state",cursor.getString(6)));
        }
        File directory=new File(apk).getCanonicalFile().getParentFile();
        if(!directory.getPath().startsWith(host.getFilesDir().getCanonicalPath()+"/")||!directory.getName().equals(generation))throw new SecurityException("QA_CODE_DIRECTORY_IDENTITY");
        File descriptor=new File(directory,"descriptor.json");
        if(Files.size(descriptor.toPath())>16*1024*1024||!sha(descriptor).equals(descriptorSha))throw new SecurityException("QA_DESCRIPTOR_IDENTITY");
        JSONObject manifest=new JSONObject(Files.readString(descriptor.toPath()));
        if(!pkg.equals(manifest.getString("packageName")))throw new SecurityException("QA_CODE_PACKAGE_IDENTITY");
        JSONArray qaParts=new JSONArray(),priorities=new JSONArray(),parts=manifest.getJSONArray("parts");
        for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);String path=part.getString("path");
            if(!path.matches("[A-Za-z0-9_.-]+\\.apk"))throw new SecurityException("QA_CODE_PART_PATH");
            File file=new File(directory,path).getCanonicalFile();
            if(!file.getParentFile().equals(directory)||file.length()!=part.getLong("size")||!sha(file).equals(part.getString("sha256")))throw new SecurityException("QA_CODE_PART_IDENTITY");
            qaParts.put(new JSONObject().put("path",path).put("sha256",part.getString("sha256")).put("size",file.length()));
            JSONArray children=part.getJSONObject("manifest").getJSONArray("children");
            for(int a=0;a<children.length();a++){
                JSONObject app=children.getJSONObject(a);if(!app.getString("name").equals("application"))continue;
                JSONArray components=app.getJSONArray("children");
                for(int c=0;c<components.length();c++){
                    JSONObject component=components.getJSONObject(c);if(!Set.of("activity","activity-alias","service","receiver").contains(component.getString("name")))continue;
                    JSONObject attributes=component.getJSONObject("attributes");JSONArray filters=component.getJSONArray("children");int ordinal=0;
                    for(int f=0;f<filters.length();f++){
                        JSONObject filter=filters.getJSONObject(f);if(!filter.getString("name").equals("intent-filter"))continue;
                        String raw=filter.getJSONObject("attributes").optString("android:priority","0");
                        if(!raw.matches("-?[0-9]+"))priorities.put(new JSONObject().put("part",path).put("partSha256",part.getString("sha256")).put("componentType",component.getString("name")).put("component",attributes.optString("android:name")).put("filterOrdinal",ordinal).put("rawPriority",raw.length()>96?"VALUE_TOO_LONG":raw));
                        ordinal++;
                    }
                }
            }
        }
        out.put("qaParts",qaParts).put("descriptorSha256",descriptorSha).put("nonNumericFilterPriorities",priorities);
        PackageManager pm=host.getPackageManager();PackageInfo nativeInfo=pm.getPackageInfo(pkg,0);ApplicationInfo ai=nativeInfo.applicationInfo;
        List<String> nativePaths=new ArrayList<>();nativePaths.add(ai.sourceDir);if(ai.splitSourceDirs!=null)nativePaths.addAll(Arrays.asList(ai.splitSourceDirs));JSONArray nativeParts=new JSONArray();
        for(String path:nativePaths){
            if(!path.matches("/[A-Za-z0-9_./~+=-]+\\.apk")||!List.of("/data/app/","/system/","/system_ext/","/product/","/vendor/","/apex/").stream().anyMatch(path::startsWith))throw new SecurityException("PUBLIC_NATIVE_APK_PATH_REQUIRED");
            File file=new File(path);nativeParts.put(new JSONObject().put("path",path).put("size",file.length()).put("sha256",sha(file)));
        }
        out.put("nativeParts",nativeParts).put("nativeVersion",nativeInfo.getLongVersionCode()).put("nativeUid",ai.uid).put("nativeTargetSdk",ai.targetSdkVersion);
        // Read-only platform oracle: unknown class names in an existing package.
        // Never set or repair a component state, and never dispatch these names.
        JSONArray enabledQueries=new JSONArray();
        for(ComponentName component:List.of(new ComponentName(host.getPackageName(),host.getPackageName()+".OwnedUndeclaredComponentProbe"),new ComponentName(pkg,"org.chromium.android_webview.SafeModeState"))){
            JSONObject row=new JSONObject().put("component",component.flattenToShortString());
            try{row.put("state",pm.getComponentEnabledSetting(component));}
            catch(RuntimeException error){row.put("errorType",error.getClass().getName());}
            enabledQueries.put(row);
        }
        out.put("nativeComponentEnabledQueries",enabledQueries);
        Intent entry=pm.getLaunchIntentForPackage(pkg);
        if(entry!=null&&entry.getComponent()!=null)out.put("nativeEntry",new JSONObject().put("component",entry.getComponent().flattenToShortString()).put("action",entry.getAction()).put("categories",new JSONArray(entry.getCategories()==null?Set.of():entry.getCategories())).put("flags",entry.getFlags()));
        else out.put("nativeEntry",JSONObject.NULL);
        if(priorities.length()>0){
            android.content.res.Resources nativeResources=pm.getResourcesForApplication(ai);
            dev.tripoder.runtime.ArtifactSet sealed=dev.tripoder.runtime.ArtifactSet.loadVerified(directory,descriptorSha);
            PackageInfo sealedInfo=pm.getPackageArchiveInfo(apk,PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS);
            if(sealedInfo==null||!pkg.equals(sealedInfo.packageName))throw new SecurityException("QA_SEALED_RESOURCE_PACKAGE_IDENTITY");
            Class<?> index=Class.forName("dev.tripoder.runtime.RuntimeManifestIndex",false,host.getClassLoader());
            java.lang.reflect.Method include=index.getDeclaredMethod("includeSplits",dev.tripoder.runtime.ArtifactSet.class,PackageInfo.class);include.setAccessible(true);include.invoke(null,sealed,sealedInfo);
            sealedInfo.applicationInfo.sourceDir=sealed.base.getPath();sealedInfo.applicationInfo.publicSourceDir=sealed.base.getPath();
            android.content.res.Resources sealedResources=pm.getResourcesForApplication(sealedInfo.applicationInfo);
            for(int i=0;i<priorities.length();i++){
                JSONObject row=priorities.getJSONObject(i);String raw=row.getString("rawPriority");
                if(raw.matches("@0x[0-9a-fA-F]{1,8}"))try{int id=Integer.parseUnsignedInt(raw.substring(3),16);row.put("nativeResourceInteger",nativeResources.getInteger(id)).put("sealedSdkResourceInteger",sealedResources.getInteger(id));}
                catch(android.content.res.Resources.NotFoundException error){row.put("nativeResourceErrorType",error.getClass().getName());}
            }
        }
        List<String> qaHashes=new ArrayList<>(),nativeHashes=new ArrayList<>();for(int i=0;i<qaParts.length();i++)qaHashes.add(qaParts.getJSONObject(i).getString("sha256"));for(int i=0;i<nativeParts.length();i++)nativeHashes.add(nativeParts.getJSONObject(i).getString("sha256"));
        Collections.sort(qaHashes);Collections.sort(nativeHashes);
        PackageInfo current=pm.getPackageInfo(pkg,0);
        if(current.getLongVersionCode()!=nativeInfo.getLongVersionCode()||current.applicationInfo.uid!=ai.uid||!Objects.equals(current.applicationInfo.sourceDir,ai.sourceDir)||!Arrays.equals(current.applicationInfo.splitSourceDirs,ai.splitSourceDirs))throw new IllegalStateException("NATIVE_INSTALL_CHANGED_DURING_CODE_INSPECTION");
        return out.put("sameCompleteApkBytes",qaHashes.equals(nativeHashes)).put("accountDataRead",false).put("activityStarted",false);
    }
    private NativeCodeInspection(){}
}
