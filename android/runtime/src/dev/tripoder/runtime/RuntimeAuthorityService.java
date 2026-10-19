package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.*;

/** Sole persistent authority in its own manifest process. Device fault injection is probe-owned. */
public class RuntimeAuthorityService extends Service {
    protected RuntimeBroker broker;
    private final String authorityGeneration=UUID.randomUUID().toString();
    private int controllerPid;
    private volatile boolean destroyed;
    @Override public void onCreate(){super.onCreate();if(!android.app.Application.getProcessName().equals(getPackageName()+":authority"))throw new IllegalStateException("AUTHORITY_PROCESS_REQUIRED");}
    private final IBinder endpoint=new RuntimeWire.Endpoint(){
        @Override protected synchronized boolean acceptsFileDescriptors(int code,int pid,int uid){return !destroyed&&code==RuntimeWire.CONTROL&&uid==android.os.Process.myUid()&&controllerPid!=0&&pid==controllerPid;}
        @Override protected void validateFileDescriptors(Bundle input){RuntimeInstallRequest.validate(input);}
        @Override protected void closeFileDescriptors(Bundle input){RuntimeInstallRequest.close(input);}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            synchronized(this){
            if(destroyed)throw new IllegalStateException("AUTHORITY_CLOSED");
            if(code!=RuntimeWire.CONTROL||uid!=android.os.Process.myUid())throw new SecurityException("CONTROL_CALLER_REJECTED");
            if(controllerPid==0)controllerPid=pid;
            if(pid!=controllerPid)throw new SecurityException("CONTROL_OWNER_CONFLICT");
            if(broker==null)broker=RuntimeAuthorityOwner.get(getApplicationContext());
            }
            String command=RuntimeWire.required(input,"command");
            if(command.equals("install")||command.equals("probeInstall")||command.equals("probeJobsReport")||command.equals("probeClosedFixture")||command.equals("probePending"))return dispatch(input);
            if(command.equals("hook"))return RuntimeHookOperations.execute(broker,RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"generation"),input.getString("request"),"runtime-probe");
            if(command.equals("ensureProcess")){
                var slot=broker.processes.ensure(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"),RuntimeWire.required(input,"processName"));return broker.processEvidence(slot);
            }
            if(command.equals("stopProcess")){broker.processes.stop(RuntimeWire.required(input,"generation"));return new Bundle();}
            if(command.equals("prepareActivity")){
                String space=RuntimeWire.required(input,"space"),pkg=RuntimeWire.required(input,"package"),component=RuntimeWire.required(input,"component"),process;
                synchronized(broker){process=RuntimeComponents.activity(broker.apks.get(space,pkg),component).processName;}
                var slot=broker.processes.ensure(space,pkg,process);Bundle output=broker.processEvidence(slot);Intent prepared=broker.launch(slot.allocation.session.processGeneration,component);
                android.util.Log.i("TripoderLaunch","prepared component="+prepared.getComponent().flattenToShortString()+" ticket="+prepared.getStringExtra("tripoder.ticket"));output.putParcelable("intent",prepared);return output;
            }
            synchronized(broker){return dispatch(input);}
        }
    };
    @Override public IBinder onBind(Intent intent){return endpoint;}
    protected Bundle dispatch(Bundle input)throws Exception{
        String command=RuntimeWire.required(input,"command");Bundle out=new Bundle();
        switch(command){
            case "completeActivity":broker.completeActivity(RuntimeWire.required(input,"ticket"));break;
            case "status":out.putString("authority",authorityGeneration);out.putInt("pid",android.os.Process.myPid());break;
            case "createSpace":broker.catalog.createSpace(RuntimeWire.required(input,"space"));break;
            case "removeEmptySpace":broker.catalog.removeEmptySpace(RuntimeWire.required(input,"space"));break;
            case "inspectProjectSource":{
                var source=ProjectBindings.inspectSource(new java.io.File(RuntimeWire.required(input,"sourcePath")));
                out.putString("sourceSha",source.digest);out.putString("projectId",source.projectId);out.putString("package",source.packageName);out.putLong("bytes",source.totalBytes);break;
            }
            case "migrateProject":{
                var reference=broker.projects.migrate(RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"projectId"),new java.io.File(RuntimeWire.required(input,"sourcePath")),RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"),RuntimeWire.required(input,"expectedGeneration"),RuntimeWire.required(input,"sourceSha"),ApkStore.NO_FAULT);
                out.putString("generation",reference.generation);out.putString("sourceSha",reference.sourceDigest);out.putString("snapshot",reference.snapshot.toString());break;
            }
            case "resolveProject":{
                var install=broker.projects.resolve(RuntimeWire.required(input,"projectId"));out.putString("generation",install.generation);out.putString("space",install.space);out.putString("package",install.info.packageName);out.putString("data",install.data.toString());break;
            }
            case "inspect":{
                var install=broker.apks.get(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"));
                out.putLong("firstInstallTime",install.info.firstInstallTime);out.putLong("lastUpdateTime",install.info.lastUpdateTime);
                out.putString("generation",install.generation);out.putString("descriptorSha",install.artifacts.descriptorSha);out.putString("data",install.data.toString());out.putLong("version",install.artifacts.versionCode);out.putInt("parts",install.artifacts.parts.size());out.putString("apk",install.apk.toString());break;
            }
            case "query":{
                var operation=broker.contract.query(RuntimeWire.required(input,"operation"));if(operation==null)throw new IllegalStateException("OPERATION_NOT_FOUND");
                out.putString("state",operation.state.name());out.putString("result",operation.result);out.putString("evidence",operation.evidence);out.putString("detail",operation.detail);break;
            }
            case "install":{
                RuntimeInstallRequest.validate(input);
                var names=input.getStringArrayList("names");var hashes=input.getStringArrayList("sha256");
                if(names==null||hashes==null||names.size()!=hashes.size()||names.size()>64)throw new IllegalArgumentException("SOURCE_LIST_INVALID");
                List<ApkStore.Source> sources=new ArrayList<>();List<ParcelFileDescriptor> descriptors=new ArrayList<>();
                try{
                    for(int i=0;i<names.size();i++){ParcelFileDescriptor fd=input.getParcelable("fd"+i,ParcelFileDescriptor.class);if(fd==null)throw new IllegalArgumentException("SOURCE_FD_REQUIRED");descriptors.add(fd);sources.add(new ApkStore.Source(names.get(i),hashes.get(i),()->new ParcelFileDescriptor.AutoCloseInputStream(fd)));}
                    var installed=broker.apks.install(RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"space"),sources,input.getString("expectedGeneration"),ApkStore.NO_FAULT);out.putString("generation",installed.generation);
                }finally{for(var fd:descriptors)fd.close();}break;
            }
            case "uninstall":broker.apks.uninstall(RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"),RuntimeWire.required(input,"expectedGeneration"),ApkStore.NO_FAULT);break;
            case "allocate":{
                var install=broker.apks.get(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"));int slot=input.getInt("slot",-1);if(slot<0||slot>1)throw new IllegalArgumentException("PROCESS_SLOT_UNSUPPORTED");
                IBinder worker=input.getBinder("worker");if(worker==null)throw new IllegalArgumentException("WORKER_REQUIRED");
                return broker.allocate(install,install.artifacts.baseManifest.applicationProcess,(slot==0?RuntimeSlot.A.class:RuntimeSlot.B.class).getName(),worker);
            }
            default:throw new UnsupportedOperationException("CONTROL_COMMAND_UNSUPPORTED:"+command);
        }
        return out;
    }
    @Override public void onDestroy(){synchronized(endpoint){destroyed=true;try{if(broker!=null)synchronized(broker){broker.store.close();}}finally{super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}}}
}
