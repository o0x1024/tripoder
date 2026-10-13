package dev.tripoder.hooklab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.zip.*;

/** Read-only package inventory. Discovery is never reported as successful execution. */
final class CompatibilityProbe {
    static JSONObject inspect(Context context,String packageName) throws Exception {
        int flags=PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo info=context.getPackageManager().getPackageInfo(packageName,flags);ApplicationInfo application=info.applicationInfo;
        JSONObject report=new JSONObject().put("packageName",packageName).put("versionName",info.versionName).put("versionCode",info.getLongVersionCode())
            .put("applicationClass",application.className==null?"android.app.Application":application.className).put("targetSdk",application.targetSdkVersion).put("minSdk",application.minSdkVersion)
            .put("hostSdk",Build.VERSION.SDK_INT).put("hostAbis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("generatedAt",System.currentTimeMillis())
            .put("launchAttempted",false).put("hookVerified",false).put("compatible",false);
        Intent launcher=context.getPackageManager().getLaunchIntentForPackage(packageName);report.put("launcherActivity",launcher==null||launcher.getComponent()==null?JSONObject.NULL:launcher.getComponent().getClassName());
        JSONArray apks=new JSONArray(),libraries=new JSONArray(),dex=new JSONArray();List<String> sources=new ArrayList<>();sources.add(application.sourceDir);if(application.splitSourceDirs!=null)sources.addAll(Arrays.asList(application.splitSourceDirs));
        Set<String> abis=new TreeSet<>();boolean unity=false;
        for(String source:sources){File file=new File(source);apks.put(new JSONObject().put("file",file.getName()).put("bytes",file.length()).put("sha256",GuestStore.sha256(file)));
            try(ZipFile zip=new ZipFile(file)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(name.matches("classes[0-9]*\\.dex"))dex.put(new JSONObject().put("apk",file.getName()).put("file",name).put("bytes",entry.getSize()));
                if(name.startsWith("lib/")&&name.endsWith(".so")){String[] parts=name.split("/");if(parts.length==3){abis.add(parts[1]);libraries.put(new JSONObject().put("apk",file.getName()).put("file",name).put("bytes",entry.getSize()));if(parts[2].equals("libunity.so")||parts[2].equals("libil2cpp.so"))unity=true;}}
            }}
        }
        report.put("apks",apks).put("dex",dex).put("nativeLibraries",libraries).put("abis",new JSONArray(abis)).put("unityLibrariesObserved",unity);
        JSONObject components=new JSONObject();components.put("activities",components(info.activities)).put("services",components(info.services)).put("providers",components(info.providers)).put("receivers",components(info.receivers));report.put("components",components);
        JSONArray permissions=new JSONArray();if(info.requestedPermissions!=null)for(String p:info.requestedPermissions)permissions.put(p);report.put("requestedPermissions",permissions);
        if(info.signingInfo!=null){JSONArray signers=new JSONArray();for(Signature signature:info.signingInfo.getApkContentsSigners())signers.put(GuestStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));report.put("signerSha256",signers);}
        report.put("blockers",new JSONArray().put("当前引擎只加载固定示例 Application / Activity，目标入口尚未接入")
            .put("尚未实现该包的系统身份、组件路由、权限和数据路径语义")
            .put("目标 Native 库依赖、初始化与图形运行尚未验证")
            .put("目标 Java / Native Hook 点尚未发现和验证"));
        return report;
    }
    private static JSONArray components(ComponentInfo[] source) throws Exception {JSONArray result=new JSONArray();if(source!=null)for(ComponentInfo component:source)result.put(new JSONObject().put("name",component.name).put("process",component.processName).put("exported",component.exported));return result;}
    static String summary(JSONObject report){JSONObject components=report.optJSONObject("components"),preflight=report.optJSONObject("preflight");String stage=preflight==null?"未执行类加载预检":preflight.optBoolean("passed")?"入口类与资源加载通过（尚未启动）":"入口类或资源加载未通过";return "已完成包结构检查，尚未通过运行验收\n\n版本："+report.optString("versionName")+"\nAPK："+report.optJSONArray("apks").length()+" 个 · DEX："+report.optJSONArray("dex").length()+" 个\nNative 库："+report.optJSONArray("nativeLibraries").length()+" 个\nABI："+report.optJSONArray("abis")+"\nActivity："+(components==null?0:components.optJSONArray("activities").length())+" 个\n\n"+stage+"\n完整报告保存在 compatibility/latest.json。";}
}
