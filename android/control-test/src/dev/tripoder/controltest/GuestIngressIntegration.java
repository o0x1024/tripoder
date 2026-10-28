package dev.tripoder.controltest;

import android.content.Intent;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.util.*;
import org.json.*;

/** End-to-end owner/ledger, framework routing and WebView check using an owned APK. */
public final class GuestIngressIntegration extends WorkbenchUiTestBase {
    @Override void test()throws Exception{
        report.put("scope","guest-ingress-storage-broadcast-parcel-webview");isolateOperationJournal();startThroughWorkbench(false);
        Object app=getTargetContext().getApplicationContext();IBinder session=(IBinder)field(field(app,"engine"),"session");Object projects=field(app,"projects");
        String space=run+"-ingress";execute(session,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-ingress.apk");String install;
        try(var in=getContext().getAssets().open("ingress-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","space",space,"operation",id());request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("ingressSha256"))));request.putParcelable("fd0",fd);
            install=ControlWire.call(session,ControlWire.EXECUTE,request).getString("generation");
        }finally{if(!apk.delete())throw new IOException("FIXTURE_COPY_NOT_REMOVED");}
        Bundle target=applicationProject(session,projects,space,"dev.tripoder.ingressguest",install);String project=target.getString("project"),binding=target.getString("bindingOperation");
        String operation=id();Bundle prepared=execute(session,"prepareLaunch","operation",operation,"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.ingressguest.Main");String process=prepared.getString("generation");
        report.put("space",space).put("project",project).put("installGeneration",install).put("processGeneration",process).put("launchOperation",operation).put("guestSha256",fixtures.getString("ingressSha256"));
        Intent entry=prepared.getParcelable("intent",Intent.class);getTargetContext().startActivity(entry);
        long until=SystemClock.elapsedRealtime()+25000;boolean passed=false;
        do{var root=getUiAutomation().getRootInActiveWindow();if(find(root,"INGRESS_GUEST_PASS",false)!=null){passed=true;break;}var failure=findPrefix(root,"INGRESS_GUEST_FAIL");if(failure!=null)throw new IllegalStateException(failure.getText().toString());SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);
        var status=findPrefix(getUiAutomation().getRootInActiveWindow(),"INGRESS_GUEST_");
        check("guest-routes-storage-broadcast-lazy-payload-saved-state-and-webview",passed,status==null?"no fixture window":String.valueOf(status.getText()));
        JSONObject request=new JSONObject().put("command","status").put("processGeneration",process);
        Bundle response=execute(session,"hook","operation",id(),"project",project,"bindingOperation",binding,"generation",install,"processGeneration",process,"request",request.toString());
        JSONObject runtime=new JSONObject(response.getString("result")).getJSONObject("result");report.put("runtimeStatus",runtime);
        JSONObject publication=runtime.getJSONObject("managedPublication");
        check("receiver-and-webview-use-no-runtime-main-thread-art-publication",publication.getLong("runtimeMainThreadToggleCalls")==0,publication);
        JSONObject syscalls=runtime.getJSONObject("nativeSyscalls");
        check("debuggerd-native-dump-not-suppressed",!syscalls.getBoolean("nativeDumpSuppressed")&&syscalls.getInt("addedSeccompFilters")==0,syscalls);
        click("Finish ingress fixture");
    }
}
