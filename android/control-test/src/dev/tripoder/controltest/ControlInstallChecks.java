package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.*;
import java.util.*;

/** Exercises the production FD and project APIs using original, self-owned signed APKs. */
final class ControlInstallChecks {
    private final ControlTestBase test;private final IBinder session;
    ControlInstallChecks(ControlTestBase test,IBinder session){this.test=test;this.session=session;}
    void run(String space)throws Exception{
        JSONObject fixtures;try(var in=test.getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        File base=copy("a.apk"),upgrade=copy("a-upgrade.apk");String sha=fixtures.getString("baseSha256"),upgradeSha=fixtures.getString("upgradeSha256");
        String operation=id();
        try{
            String rejected=id();
            test.check("writable-install-descriptor-rejected",test.rejected(()->install(base,sha,space,rejected,null,ParcelFileDescriptor.MODE_READ_WRITE),SecurityException.class,"SOURCE_READ_ONLY_REGULAR_FILE_REQUIRED"),true);
            test.check("invalid-fd-rejected-before-admission",test.rejected(()->test.execute(session,"query","operation",rejected),IllegalStateException.class,"OPERATION_NOT_FOUND"),true);
            Bundle imported=install(base,sha,space,operation,null,ParcelFileDescriptor.MODE_READ_ONLY);String generation=imported.getString("generation");
            test.check("original-apk-installed-across-distinct-uids","dev.tripoder.p0.a".equals(imported.getString("package"))&&sha.equals(imported.getString("sha256"))&&!imported.containsKey("apk")&&!imported.containsKey("data"),test.json(imported));
            Bundle receipt=test.execute(session,"query","operation",operation);
            test.check("install-operation-durable-receipt","SUCCEEDED".equals(receipt.getString("state"))&&generation.equals(receipt.getString("result")),test.json(receipt));
            test.check("duplicate-install-operation-reuses-receipt",generation.equals(install(base,sha,space,operation,null,ParcelFileDescriptor.MODE_READ_ONLY).getString("generation")),generation);
            String corrupt=id();
            test.check("incorrect-apk-digest-rejected",test.rejected(()->install(base,"0".repeat(64),space,corrupt,null,ParcelFileDescriptor.MODE_READ_ONLY),SecurityException.class,"APK_DIGEST_MISMATCH"),true);
            test.check("incorrect-digest-has-terminal-failed-receipt","FAILED".equals(test.execute(session,"query","operation",corrupt).getString("state")),corrupt);
            String project="project-"+test.run,binding=id();
            test.check("project-cannot-bind-invented-generation",test.rejected(()->test.execute(session,"bindProject","operation",id(),"project",project,"space",space,"package","dev.tripoder.p0.a","generation","not-current"),IllegalStateException.class,"PROJECT_INSTALL_GENERATION_CONFLICT"),true);
            Bundle bound=test.execute(session,"bindProject","operation",binding,"project",project,"space",space,"package","dev.tripoder.p0.a","generation",generation);
            test.check("project-bound-to-verified-install",generation.equals(bound.getString("generation"))&&binding.equals(bound.getString("bindingOperation")),test.json(bound));
            test.check("project-resolves-same-data-instance",bound.getString("dataId").equals(test.execute(session,"resolveProject","project",project).getString("dataId")),test.json(bound));
            Bundle upgraded=install(upgrade,upgradeSha,space,id(),generation,ParcelFileDescriptor.MODE_READ_ONLY);String current=upgraded.getString("generation");
            test.check("upgrade-creates-new-install-generation",!generation.equals(current),test.json(upgraded));
            test.check("upgrade-invalidates-project-binding",test.rejected(()->test.execute(session,"resolveProject","project",project),IllegalStateException.class,"PROJECT_BINDING_STALE"),true);
            String rebind=id();Bundle rebound=test.execute(session,"bindProject","operation",rebind,"project",project,"space",space,"package","dev.tripoder.p0.a","generation",current,"expectedBinding",binding);
            test.check("explicit-rebind-retains-app-data",bound.getString("dataId").equals(rebound.getString("dataId"))&&current.equals(rebound.getString("generation")),test.json(rebound));
            test.check("stale-binding-revision-cannot-overwrite-current",test.rejected(()->test.execute(session,"bindProject","operation",id(),"project",project,"space",space,"package","dev.tripoder.p0.a","generation",current,"expectedBinding",binding),IllegalStateException.class,"PROJECT_BINDING_CONFLICT"),true);
            test.report.put("project",project).put("bindingOperation",rebind).put("installGeneration",current).put("dataId",bound.getString("dataId")).put("installFixture",fixtures);
        }finally{if(!base.delete()||!upgrade.delete())throw new IOException("TEST_COPY_CLEANUP_FAILED");}
    }
    private File copy(String asset)throws Exception{
        File out=new File(test.getTargetContext().getCacheDir(),test.run+"-"+asset);
        try(var input=test.getContext().getAssets().open(asset);var output=new FileOutputStream(out)){input.transferTo(output);output.getFD().sync();}return out;
    }
    private Bundle install(File file,String sha,String space,String operation,String expected,int mode)throws Exception{
        try(var descriptor=ParcelFileDescriptor.open(file,mode)){
            Bundle request=ControlWire.request("install","space",space,"operation",operation);if(expected!=null)request.putString("expectedGeneration",expected);
            request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(sha)));request.putParcelable("fd0",descriptor);
            return ControlWire.call(session,ControlWire.EXECUTE,request);
        }
    }
    private static String id(){return UUID.randomUUID().toString();}
}
