package dev.tripoder.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Static declaration projection of the full authenticated descriptor, never the worker's lite view. */
public final class InstalledManifestAudit {
    private record Entry(JSONObject declaration,String path,String parent,String split){}
    public static JSONObject read(String descriptor,String digest,JSONObject target,int cursor,int limit)throws Exception{
        if(cursor<0||limit<1||limit>64)throw new IllegalArgumentException("MANIFEST_PAGE_RANGE");
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8)));
        if(!actual.equals(digest))throw new SecurityException("DESCRIPTOR_CORRUPT");
        JSONObject root=new JSONObject(descriptor);if(!target.getString("package").equals(root.getString("packageName")))throw new SecurityException("MANIFEST_PACKAGE_MISMATCH");
        JSONObject base=root.getJSONArray("parts").getJSONObject(0).getJSONObject("manifest");Integer targetSdk=targetSdk(base);
        List<Entry> entries=new ArrayList<>();JSONArray parts=root.getJSONArray("parts"),sources=new JSONArray();
        for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);String split=part.getString("path");
            sources.put(new JSONObject().put("path",split).put("sha256",part.getString("sha256")));
            collect(part.getJSONObject("manifest"),"/parts/"+i+"/manifest","",split,entries,0);
        }
        if(cursor>entries.size())throw new IllegalArgumentException("MANIFEST_CURSOR_INVALID");
        JSONObject out=new JSONObject().put("target",target).put("source","VERIFIED_INSTALL_DECLARATIONS").put("descriptorSha256",digest)
            .put("versionCode",String.valueOf(root.getLong("versionCode"))).put("parts",sources).put("total",entries.size()).put("cursor",cursor);
        JSONArray page=new JSONArray();int next=cursor,bytes=out.toString().getBytes(StandardCharsets.UTF_8).length;
        while(next<entries.size()&&page.length()<limit){Entry entry=entries.get(next);JSONObject item=new JSONObject().put("tag",entry.declaration.getString("name"))
            .put("namespace",entry.declaration.getString("namespace")).put("attributes",entry.declaration.getJSONObject("attributes"))
            .put("path",entry.path).put("parent",entry.parent).put("part",entry.split);
            JSONObject attrs=entry.declaration.getJSONObject("attributes"),booleans=new JSONObject();
            for(String key:List.of("exported","enabled","debuggable","allowBackup","usesCleartextTraffic","grantUriPermissions"))if(attrs.has("android:"+key)){
                String raw=attrs.getString("android:"+key);JSONObject value=new JSONObject().put("raw",raw).put("state",raw.equals("true")||raw.equals("false")?"DECLARED_LITERAL":"UNRESOLVED");
                if(raw.equals("true")||raw.equals("false"))value.put("value",Boolean.parseBoolean(raw));booleans.put(key,value);
            }
            if(booleans.length()>0)item.put("booleanDeclarations",booleans);
            String tag=entry.declaration.getString("name");JSONObject effective=new JSONObject();
            if(tag.equals("application")){
                effective.put("debuggable",booleanValue(attrs,"debuggable",false,"application default false"));effective.put("allowBackup",booleanValue(attrs,"allowBackup",true,"application default true"));
                effective.put("enabled",booleanValue(attrs,"enabled",true,"application default true"));effective.put("usesCleartextTraffic",booleanValue(attrs,"usesCleartextTraffic",targetSdk==null?null:targetSdk<28,"targetSdk < 28 default true; otherwise false"));
            }else if(Set.of("activity","activity-alias","service","receiver","provider").contains(tag)){
                boolean filter=false;JSONArray children=entry.declaration.getJSONArray("children");for(int n=0;n<children.length();n++)filter|=children.getJSONObject(n).getString("name").equals("intent-filter");
                Boolean defaultExported=tag.equals("provider")?(targetSdk==null?null:targetSdk<17):filter;
                effective.put("exported",booleanValue(attrs,"exported",defaultExported,tag.equals("provider")?"provider default depends on targetSdk < 17":"component default follows declared intent-filter; targetSdk >=31 requires explicit declaration"));
                effective.put("enabledDeclaration",booleanValue(attrs,"enabled",true,"component declaration default true; application enabled also applies"));
            }
            if(effective.length()>0)item.put("staticValues",effective);
            int size=item.toString().getBytes(StandardCharsets.UTF_8).length;if(size>24000)throw new IllegalArgumentException("MANIFEST_ENTRY_LIMIT:"+entry.path);
            if(bytes+size>48000)break;page.put(item);bytes+=size;next++;
        }
        if(next==cursor&&cursor<entries.size())throw new IllegalArgumentException("MANIFEST_RESPONSE_LIMIT");
        return out.put("declarations",page).put("nextCursor",next==entries.size()?-1:next).put("complete",next==entries.size())
            .put("semantics","Raw APK declarations only. Missing attributes are absent; resources remain UNRESOLVED. No runtime grants, reachability or component state is asserted.");
    }
    private static JSONObject booleanValue(JSONObject attrs,String name,Boolean initial,String rule)throws Exception{
        String key="android:"+name;if(attrs.has(key)){String raw=attrs.getString(key);JSONObject out=new JSONObject().put("raw",raw).put("state",raw.equals("true")||raw.equals("false")?"DECLARED_LITERAL":"UNRESOLVED");if(raw.equals("true")||raw.equals("false"))out.put("value",Boolean.parseBoolean(raw));return out;}
        JSONObject out=new JSONObject().put("state",initial==null?"UNRESOLVED":"PLATFORM_DEFAULT").put("rule",rule);if(initial!=null)out.put("value",initial);return out;
    }
    private static Integer targetSdk(JSONObject manifest)throws Exception{
        JSONArray children=manifest.getJSONArray("children");for(int i=0;i<children.length();i++){JSONObject child=children.getJSONObject(i);if(!child.getString("name").equals("uses-sdk"))continue;JSONObject attrs=child.getJSONObject("attributes");String value=attrs.optString("android:targetSdkVersion",attrs.optString("android:minSdkVersion","1"));try{return Integer.valueOf(value);}catch(NumberFormatException unresolved){return null;}}
        return 1;
    }
    private static void collect(JSONObject declaration,String path,String parent,String split,List<Entry> entries,int depth)throws Exception{
        if(depth>64||entries.size()>=20000)throw new IllegalArgumentException("MANIFEST_COMPLEXITY");
        entries.add(new Entry(declaration,path,parent,split));JSONArray children=declaration.getJSONArray("children");
        for(int i=0;i<children.length();i++)collect(children.getJSONObject(i),path+"/children/"+i,path,split,entries,depth+1);
    }
    private InstalledManifestAudit(){}
}
