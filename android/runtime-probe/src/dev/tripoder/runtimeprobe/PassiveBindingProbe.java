package dev.tripoder.runtimeprobe;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import dev.tripoder.runtime.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

/** Acceptance-only gate; it never schedules the target Service itself. */
final class PassiveBindingProbe {
    static Bundle command(Context host,RuntimeBroker broker,Bundle request)throws Exception{
        var session=broker.contract.getSession(RuntimeWire.required(request,"generation"));
        if(!session.identity.packageName.equals("dev.tripoder.provideridentity")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("PASSIVE_FIXTURE_REQUIRED");
        var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
        if(!installed.info.applicationInfo.className.equals("dev.tripoder.servicestartup.PassiveApplication"))throw new SecurityException("PASSIVE_APPLICATION_REQUIRED");
        boolean gate=RuntimeWire.required(request,"command").equals("probePassiveGate");
        File file=new File(installed.data,"files/"+(gate?"passive-bound.json":"passive-contract.json"));Bundle out=new Bundle();if(!file.exists())return out;if(file.length()>32768)throw new IllegalStateException("PASSIVE_REPORT_TOO_LARGE");
        String content=Files.readString(file.toPath());JSONObject report=new JSONObject(content);
        if(report.getInt("pid")!=session.pid||report.getInt("uid")!=session.uid)throw new SecurityException("PASSIVE_REPORT_OWNER_MISMATCH");
        if(!gate){out.putString("result",content);return out;}
        if(SystemClock.elapsedRealtime()-report.getLong("at")<1200)return out;
        broker.processes.capacity(); // Runs production reconciliation, including canceled PASSIVE owners.
        String stub;
        synchronized(broker){
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT processStub,state,generation FROM service_leases WHERE space=? AND package=? AND component=? AND state!='RELEASED'",new String[]{session.identity.spaceId,session.identity.packageName,"dev.tripoder.servicestartup.PassiveService"})){
                if(!c.moveToFirst()||!c.getString(1).equals("PASSIVE")||!c.getString(2).isEmpty())throw new AssertionError("PASSIVE_LEASE_MUST_NOT_ALLOCATE_SESSION");stub=c.getString(0);if(c.moveToNext())throw new AssertionError("PASSIVE_DUPLICATE_LEASE");
            }
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions WHERE space=? AND package=? AND processName=?",new String[]{session.identity.spaceId,session.identity.packageName,session.identity.packageName+":passive"})){
                if(!c.moveToFirst()||c.getInt(0)!=0)throw new AssertionError("PASSIVE_BIND_ALLOCATED_PROCESS");
            }
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT state FROM service_leases WHERE space=? AND package=? AND component=?",new String[]{session.identity.spaceId,session.identity.packageName,"dev.tripoder.servicestartup.PassiveService$NeverCreated"})){
                if(!c.moveToFirst()||!c.getString(0).equals("RELEASED")||c.moveToNext())throw new AssertionError("CANCELED_PASSIVE_OWNER_NOT_RETIRED");
            }
        }
        int index=-1;for(int i=0;i<RuntimeSlot.CAPACITY;i++)if(RuntimeSlot.activity(i).getName().equals(stub))index=i;
        if(index<0)throw new AssertionError("PASSIVE_PROCESS_STUB_INVALID");String physical=host.getPackageName()+":slot"+index;
        var processes=((ActivityManager)host.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();if(processes==null)throw new IllegalStateException("PROCESS_OBSERVATION_UNAVAILABLE");
        for(var process:processes)if(process.processName.equals(physical))throw new AssertionError("PASSIVE_BIND_STARTED_PHYSICAL_PROCESS");
        try(var stream=new FileOutputStream(new File(installed.data,"files/passive-start-gate"))){stream.write("authority-observed-passive-owner-and-no-process".getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.getFD().sync();}
        out.putBoolean("approved",true);out.putBoolean("canceledOwnerRetired",true);out.putString("physicalProcess",physical);return out;
    }
}
