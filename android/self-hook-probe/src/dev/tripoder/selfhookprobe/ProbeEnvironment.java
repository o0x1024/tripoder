package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.util.Iterator;
import org.json.JSONObject;

/** One platform/artifact gate shared by independent research entry activities. */
final class ProbeEnvironment {
    static File load(Context context,JSONObject report) throws Exception {
        ApplicationInfo info=context.getApplicationInfo();
        report.put("package",context.getPackageName()).put("uid",Process.myUid()).put("pid",Process.myPid());
        report.put("timestampMs",System.currentTimeMillis()).put("apkSha256",sha(new File(info.sourceDir)));
        JSONObject build=new JSONObject(asset(context,"build.json"));report.put("build",build);
        boolean debuggable=(info.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;report.put("debuggable",debuggable);
        JSONObject expected=new JSONObject(asset(context,"platform.json"));
        JSONObject actual=new JSONObject().put("fingerprint",Build.FINGERPRINT).put("sdk",Build.VERSION.SDK_INT)
            .put("abi",Build.SUPPORTED_ABIS[0]).put("process64Bit",Process.is64Bit()).put("pageSize",Os.sysconf(OsConstants._SC_PAGESIZE));
        report.put("platform",actual);
        require(!debuggable&&Process.is64Bit(),"PROCESS_REQUIREMENTS");
        for(String field:new String[]{"fingerprint","sdk","abi","pageSize"})
            require(expected.get(field).toString().equals(actual.get(field).toString()),"PLATFORM_"+field);
        JSONObject binaries=new JSONObject();actual.put("binaries",binaries);
        JSONObject hashes=expected.getJSONObject("binaries");
        Iterator<String> paths=hashes.keys();
        while(paths.hasNext()){String path=paths.next(),digest=sha(new File(path));binaries.put(path,digest);
            require(digest.equals(hashes.getString(path)),"BINARY_IDENTITY_"+path);}
        report.put("selinuxContext",read("/proc/self/attr/current").trim()).put("platformGatePassed",true);
        report.put("nativeSha256",sha(new File(info.nativeLibraryDir,"libtripoderselfhook.so")));
        require(report.getString("nativeSha256").equals(build.getString("nativeSha256")),"NATIVE_BUILD_IDENTITY");
        File fixture=new File(info.nativeLibraryDir,"libtripoderhookfixture.so");
        report.put("fixtureSha256",sha(fixture));
        require(report.getString("fixtureSha256").equals(build.getString("fixtureSha256")),"FIXTURE_BUILD_IDENTITY");
        System.loadLibrary("tripoderselfhook");
        return fixture;
    }
}
