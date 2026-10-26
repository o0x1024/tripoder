package dev.tripoder.runtime;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class InstalledManifestAuditTest {
    private static JSONObject element(String tag,JSONObject attributes,JSONObject...children)throws Exception{return new JSONObject().put("name",tag).put("namespace","").put("attributes",attributes).put("children",new JSONArray(Arrays.asList(children)));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        JSONObject permission=element("uses-permission",new JSONObject().put("android:name","owned.permission.BASE"));
        JSONObject filter=element("intent-filter",new JSONObject(),element("action",new JSONObject().put("android:name","owned.VIEW")),element("data",new JSONObject().put("android:scheme","owned").put("android:host","host").put("android:pathPrefix","/deep").put("android:mimeType","text/plain")));
        JSONObject base=element("manifest",new JSONObject().put("package","owned.fixture"),element("uses-sdk",new JSONObject().put("android:targetSdkVersion","37")),permission,element("application",new JSONObject().put("android:debuggable","@0x7f010001"),element("activity",new JSONObject().put("android:name","owned.Activity").put("android:exported","true"),filter)));
        JSONObject split=element("manifest",new JSONObject(),element("uses-permission",new JSONObject().put("android:name","owned.permission.SPLIT")),element("application",new JSONObject(),element("service",new JSONObject().put("android:name","owned.SplitService"))));
        String descriptor=new JSONObject().put("packageName","owned.fixture").put("versionCode",63).put("parts",new JSONArray().put(new JSONObject().put("path","base.apk").put("sha256","a".repeat(64)).put("manifest",base)).put(new JSONObject().put("path","split-1.apk").put("sha256","b".repeat(64)).put("manifest",split))).toString();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8)));JSONObject target=new JSONObject().put("space","a").put("package","owned.fixture").put("installGeneration","installation");
        JSONArray all=new JSONArray();int cursor=0,pages=0;do{JSONObject result=InstalledManifestAudit.read(descriptor,digest,target,cursor,2);JSONArray records=result.getJSONArray("declarations");for(int i=0;i<records.length();i++)all.put(records.get(i));cursor=result.getInt("nextCursor");pages++;}while(cursor!=-1);
        check(pages>1,"pagination");String json=all.toString();check(json.contains("owned.permission.BASE")&&json.contains("owned.permission.SPLIT")&&json.contains("owned.SplitService"),"full descriptor retains permissions and split declarations");check(json.contains("android:pathPrefix")&&json.contains("android:mimeType"),"full filter data");
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(row.getString("tag").equals("application")&&row.getString("part").equals("base.apk")){JSONObject flags=row.getJSONObject("staticValues");check(flags.getJSONObject("debuggable").getString("state").equals("UNRESOLVED")&&!flags.getJSONObject("debuggable").has("value"),"unknown bool never false");check(flags.getJSONObject("allowBackup").getBoolean("value"),"documented platform default");check(!flags.getJSONObject("usesCleartextTraffic").getBoolean("value"),"target SDK default");}}
        try{InstalledManifestAudit.read(descriptor,"c".repeat(64),target,0,2);throw new AssertionError("bad descriptor accepted");}catch(SecurityException expected){}
        try{InstalledManifestAudit.read(descriptor,digest,target,999,2);throw new AssertionError("bad cursor accepted");}catch(IllegalArgumentException expected){}
        System.out.println("InstalledManifestAudit: permissions/splits, full filters, unknown values, defaults, hash and pagination passed");
    }
}
