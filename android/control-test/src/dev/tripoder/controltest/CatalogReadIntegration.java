package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Seed owned fixtures, then rerun after force-stop to check the cold catalog and lazy APK loading. */
public final class CatalogReadIntegration extends ControlTestBase {
    private static final int COUNT=32;
    private static final String PREFIX="catalog-read-";
    @Override void test()throws Exception{
        report.put("scope","cold-catalog-metadata-and-lazy-apk-resolution");
        startThroughWorkbench(false);
        Object app=getTargetContext().getApplicationContext();IBinder session=(IBinder)field(field(app,"engine"),"session");
        if("catalog-seed".equals(args.getString("phase"))){
            JSONObject fixtures;
            try(var input=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
            File apk=new File(getTargetContext().getCacheDir(),run+"-catalog.apk");
            try(var in=getContext().getAssets().open("a.apk");var out=new FileOutputStream(apk)){in.transferTo(out);}
            try{
                for(int i=0;i<COUNT;i++){
                    String space=PREFIX+String.format(java.util.Locale.ROOT,"%02d",i);
                    execute(session,"createSpace","operation",id(),"space",space);
                    try(var descriptor=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
                        Bundle input=ControlWire.request("install","operation",id(),"space",space);
                        input.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));
                        input.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("baseSha256"))));input.putParcelable("fd0",descriptor);
                        ControlWire.call(session,ControlWire.EXECUTE,input);
                    }
                }
            }finally{apk.delete();}
        }
        long begin=SystemClock.elapsedRealtime();Bundle catalog=execute(session,"catalog");long ms=SystemClock.elapsedRealtime()-begin;
        JSONArray installs=new JSONArray(catalog.getString("installs"));int found=0;JSONObject first=null;
        for(int i=0;i<installs.length();i++){JSONObject row=installs.getJSONObject(i);if(row.getString("space").startsWith(PREFIX)){found++;if(first==null)first=row;}}
        check("all-owned-installations-remain-listed",found==COUNT,found);
        check("catalog-request-under-one-second",ms<1000,ms);report.put("catalogMs",ms).put("installationCount",found);
        if(!"catalog-seed".equals(args.getString("phase"))){
            Bundle target=execute(session,"applicationTarget","space",first.getString("space"),"package",first.getString("package"),"generation",first.getString("generation"));
            Bundle integrity=target.getBundle("integrity");
            check("cold-catalog-does-not-preload-all-apks",integrity.getInt("sets")==1,json(integrity));
            check("explicit-target-resolves-current-install",first.getString("generation").equals(target.getString("generation")),true);
            JSONObject second=installs.getJSONObject(1);
            Bundle next=execute(session,"applicationTarget","space",second.getString("space"),"package",second.getString("package"),"generation",second.getString("generation"));
            check("second-target-loads-only-its-own-artifacts",next.getBundle("integrity").getInt("sets")==2,json(next.getBundle("integrity")));
        }
    }
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static String id(){return UUID.randomUUID().toString();}
}
