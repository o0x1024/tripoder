package dev.tripoder.runtimeprobe;

import android.os.*;
import android.system.Os;
import dev.tripoder.runtime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Fault injection exists only in the acceptance APK, never the production control protocol. */
public final class P1Authority extends RuntimeAuthorityService {
    private final InstallGate installGate=new InstallGate();
    private java.util.concurrent.CountDownLatch mainGate;
    private volatile boolean mainGateExpired;
    private ApkStore.FaultPoint fault(Bundle request){return point->{installGate.at(request,point);if(point.equals(request.getString("crashAt"))){
        File receipt=new File(getFilesDir(),"fault-"+safe(request.getString("operation"))+".json");
        write(receipt,new JSONObject().put("point",point).put("pid",android.os.Process.myPid()).put("time",System.currentTimeMillis()).toString());
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("PROCESS_DID_NOT_DIE");
    }};}
    @Override protected Bundle dispatch(Bundle input)throws Exception{
        String command=RuntimeWire.required(input,"command");Bundle out=new Bundle();
        switch(command){
            case "probeProviderIntegrityStats":return broker.apks.integrityStats();
            case "probeInstallProviderIndex":out.putString("result",RuntimeInstallProviderIndexProbe.run(this).toString());return out;
            case "probeUnrelatedProviderInstall":return RuntimeInstallProviderIndexProbe.isolateUnrelated(broker,RuntimeWire.required(input,"space"));
            case "probePackageResourcesReport":{
                var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));
                if(!session.identity.packageName.equals("dev.tripoder.provideridentity")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("PACKAGE_RESOURCES_FIXTURE_REQUIRED");
                String nonce=RuntimeWire.required(input,"reportNonce"),expected=RuntimeWire.required(input,"reportSha256");long size=input.getLong("reportBytes",-1);
                if(!UUID.fromString(nonce).toString().equals(nonce)||!expected.matches("[0-9a-f]{64}")||size<0||size>512*1024)throw new SecurityException("PACKAGE_RESOURCES_RECEIPT_INVALID");
                var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
                if(!installed.generation.equals(session.identity.installGeneration))throw new SecurityException("PACKAGE_RESOURCES_INSTALL_CHANGED");
                // Android may expose its own files directory through a platform alias.
                // Canonicalize that trusted anchor, never a guest-controlled suffix.
                var anchor=getFilesDir().getAbsoluteFile().toPath();var data=installed.data.getAbsoluteFile().toPath();
                if(!data.startsWith(anchor)||!data.normalize().equals(data))throw new SecurityException("PACKAGE_RESOURCES_PATH_INVALID:INSTALL_ROOT");
                File directory=getFilesDir().getCanonicalFile();
                var relative=anchor.relativize(data.resolve("files"));
                for(var part:relative){
                    directory=new File(directory,part.toString());
                    var stat=Os.lstat(directory.toString());
                    if(!android.system.OsConstants.S_ISDIR(stat.st_mode)||stat.st_uid!=session.uid)
                        throw new SecurityException("PACKAGE_RESOURCES_PATH_INVALID:OWNED_DIRECTORY:"+directory);
                }
                File file=new File(directory,"resources-"+nonce+".json");
                // lstat never follows the report leaf; every owned parent was checked above.
                var before=Os.lstat(file.toString());
                if(!android.system.OsConstants.S_ISREG(before.st_mode)||before.st_uid!=session.uid)
                    throw new SecurityException("PACKAGE_RESOURCES_PATH_INVALID:REPORT_LEAF:"+file);
                byte[] bytes;var descriptor=Os.open(file.toString(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW|android.system.OsConstants.O_CLOEXEC,0);
                try(var stream=new FileInputStream(descriptor)){
                    var opened=Os.fstat(descriptor);
                    if(!android.system.OsConstants.S_ISREG(opened.st_mode)||opened.st_uid!=session.uid||opened.st_dev!=before.st_dev||opened.st_ino!=before.st_ino||opened.st_size!=size)
                        throw new SecurityException("PACKAGE_RESOURCES_PATH_INVALID:OPENED_REPORT:"+file);
                    bytes=stream.readNBytes(512*1024+1);
                }
                if(bytes.length!=size||!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new SecurityException("PACKAGE_RESOURCES_INTEGRITY_FAILED");
                String value=new String(bytes,StandardCharsets.UTF_8);JSONObject parsed=new JSONObject(value);
                if(parsed.getInt("pid")!=session.pid||parsed.getInt("uid")!=session.uid)throw new SecurityException("PACKAGE_RESOURCES_IDENTITY_MISMATCH");
                return ProbeReports.handoff(this,value);
            }
            case "probeActivityFlagsReport":{
                var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));
                if(!session.identity.packageName.equals("dev.tripoder.provideridentity")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("ACTIVITY_FLAGS_FIXTURE_REQUIRED");
                var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
                if(!installed.info.applicationInfo.className.equals("dev.tripoder.provideridentity.MetadataApplication"))throw new SecurityException("ACTIVITY_FLAGS_APPLICATION_REQUIRED");
                File file=new File(installed.data,"files/activity-flags-contract.json");if(!file.exists())return out;if(file.length()>32768)throw new IllegalStateException("ACTIVITY_FLAGS_REPORT_TOO_LARGE");
                String report=Files.readString(file.toPath());JSONObject parsed=new JSONObject(report);if(parsed.getInt("pid")!=session.pid||parsed.getInt("uid")!=session.uid)throw new SecurityException("ACTIVITY_FLAGS_IDENTITY_MISMATCH");out.putString("result",report);
                JSONArray deliveries=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT l.kind,l.state,l.requestFlags,l.startResult,op.state AS execution FROM activity_launches l LEFT JOIN operations op ON op.id=l.operation WHERE l.generation=?",new String[]{session.processGeneration})){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));deliveries.put(row);}}out.putString("deliveries",deliveries.toString());
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(CASE WHEN state='COMPLETED' THEN 1 ELSE 0 END) FROM broadcast_deliveries WHERE source=?",new String[]{session.processGeneration})){if(!c.moveToFirst())throw new AssertionError("BROADCAST_RECEIPTS_MISSING");out.putInt("broadcasts",c.getInt(0));out.putInt("completedBroadcasts",c.getInt(1));}
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM broadcast_deliveries WHERE target=? AND source='' AND sourceIdentity=? AND state='COMPLETED'",new String[]{session.processGeneration,"PLATFORM_PROTECTED:android.intent.action.BATTERY_CHANGED"})){if(!c.moveToFirst())throw new AssertionError("STICKY_RECEIPT_MISSING");out.putInt("completedStickyCallbacks",c.getInt(0));}return out;
            }
            case "probeJobsReport":return RuntimeJobInspection.read(broker,input);
            case "probePending":return RuntimePendingInspection.call(broker,input);
            case "probePassiveGate":case "probePassiveReport":return PassiveBindingProbe.command(this,broker,input);
            case "probeServiceStartupReport":{
                var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));
                if(!session.identity.packageName.equals("dev.tripoder.provideridentity")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("SERVICE_STARTUP_FIXTURE_REQUIRED");
                var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
                if(!installed.info.applicationInfo.className.equals("dev.tripoder.servicestartup.StartupApplication"))throw new SecurityException("SERVICE_STARTUP_APPLICATION_REQUIRED");
                File file=new File(installed.data,"files/service-startup-contract.json");if(!file.exists())return out;if(file.length()>32768)throw new IllegalStateException("SERVICE_STARTUP_REPORT_TOO_LARGE");
                String report=Files.readString(file.toPath());JSONObject parsed=new JSONObject(report);if(parsed.getInt("pid")!=session.pid||parsed.getInt("uid")!=session.uid)throw new SecurityException("SERVICE_STARTUP_REPORT_IDENTITY_MISMATCH");out.putString("result",report);return out;
            }
            case "probePackageWorldReport":{
                var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));
                if(!session.identity.packageName.equals("dev.tripoder.provideridentity")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("PACKAGE_WORLD_FIXTURE_REQUIRED");
                var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
                if(!installed.info.applicationInfo.className.equals("dev.tripoder.provideridentity.PackageWorldApplication"))throw new SecurityException("PACKAGE_WORLD_APPLICATION_REQUIRED");
                File file=new File(installed.data,"files/package-world-contract.json");if(file.length()>32768)throw new IllegalStateException("PACKAGE_WORLD_REPORT_TOO_LARGE");
                String report=Files.readString(file.toPath());JSONObject parsed=new JSONObject(report);if(parsed.getInt("pid")!=session.pid||parsed.getInt("uid")!=session.uid)throw new SecurityException("PACKAGE_WORLD_REPORT_IDENTITY_MISMATCH");out.putString("result",report);return out;
            }
            case "probeEntryFence":return RuntimeEntryFenceProbe.command(broker,input);
            case "probeClosedFixture":return RuntimeClosedFixtureProbe.command(broker,input);
            case "probeActivityPayload":out.putString("result",RuntimeActivityPayloadProbe.run().toString());return out;
            case "probeReceiverGateArm":{
                String generation=RuntimeWire.required(input,"generation");var session=broker.contract.getSession(generation);
                if(!session.identity.packageName.equals("dev.tripoder.p2")||session.state!=ExecutionContract.ProcessState.RUNNING)throw new SecurityException("RECEIVER_FIXTURE_REQUIRED");
                File directory=receiverGateDirectory(input);
                for(String name:new String[]{"receiver-gate-held","receiver-gate-release"}){File file=new File(directory,name);if(file.exists()&&!file.delete())throw new IOException("RECEIVER_GATE_CLEANUP_FAILED");}
                write(new File(directory,"receiver-gate-arm"),generation);return out;
            }
            case "probeReceiverGateHeld":{
                File file=new File(receiverGateDirectory(input),"receiver-gate-held");if(file.exists())out.putString("held",Files.readString(file.toPath()));return out;
            }
            case "probeReceiverGateRelease":write(new File(receiverGateDirectory(input),"receiver-gate-release"),"release");return out;
            case "probeReceiverDelivery":{
                String ticket=RuntimeWire.required(input,"ticket");
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT b.ticket,b.operation,b.source,b.target,b.state,b.returned,b.finished,b.systemDone,b.resourceRetired,b.resourceProof,s.state AS processState,s.activityStub,op.state AS execution FROM broadcast_deliveries b JOIN sessions s ON s.generation=b.target JOIN operations op ON op.id=b.operation WHERE b.ticket=?",new String[]{ticket})){
                    if(!c.moveToFirst())throw new IllegalStateException("PROBE_TICKET_MISSING");JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));out.putString("delivery",row.toString());
                }return out;
            }
            case "probeCompletedReceiverTicket":{
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT b.ticket FROM broadcast_deliveries b JOIN sessions s ON b.target=s.generation WHERE s.space=? AND b.state='COMPLETED' AND b.component!='' LIMIT 1",new String[]{RuntimeWire.required(input,"space")})){
                    if(!c.moveToFirst())throw new IllegalStateException("PROBE_COMPLETED_RECEIVER_MISSING");out.putString("ticket",c.getString(0));
                }return out;
            }
            case "probeInstallGateArm":case "probeInstallGateState":case "probeInstallGateRelease":return installGate.command(input);
            case "probeRecoverArtifacts":broker.apks.recover();return out;
            case "previewSnapshot":case "previewCreateSpace":return PreviewCatalog.dispatch(broker,command);
            case "probeTaskVisibility":return broker.inspectTasks();
            case "probeProcessCapacity":return broker.processes.capacity();
            case "probeJobInfoContract":out.putString("result",RuntimeJobInfoContractProbe.run().toString());return out;
            case "probeNotificationSeed":return RuntimeNotificationContractProbe.seed(this,broker.apks.get(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package")).data.getName());
            case "probeNotificationInspect":return RuntimeNotificationContractProbe.inspect(this,RuntimeWire.required(input,"id"),RuntimeWire.required(input,"group"));
            case "probeNotificationCancellation":out.putString("result",RuntimeNotificationCancellationProbe.run(this).toString());return out;
            case "probeTrafficSnapshot":out.putInt("uid",android.os.Process.myUid());out.putLong("rx",android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()));out.putLong("tx",android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()));return out;
            case "probeAllocate":{
                int slot=input.getInt("slot",-1);if(slot<0||slot>=RuntimeSlot.CAPACITY)throw new IllegalArgumentException("PROBE_SLOT_INVALID");
                IBinder peer=input.getBinder("worker");if(peer==null)throw new IllegalArgumentException("PROBE_WORKER_REQUIRED");
                var install=broker.apks.get(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"));
                return broker.allocate(install,install.artifacts.baseManifest.applicationProcess,RuntimeSlot.activity(slot).getName(),peer);
            }
            case "probeCloseActivityGate":{
                String generation=RuntimeWire.required(input,"generation");
                synchronized(broker){
                    var session=broker.contract.getSession(generation);
                    try(var row=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM activity_tickets WHERE generation=? AND component='dev.tripoder.p2.ClosingActivity' AND state='LIVE' AND destroyedConfirmed=0",new String[]{generation})){
                        row.moveToFirst();if(row.getInt(0)!=1||session.state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("CLOSING_FIXTURE_NOT_READY");
                    }
                    var lookup=RuntimeBroker.class.getDeclaredMethod("requiredSlot",String.class);lookup.setAccessible(true);Object slot=lookup.invoke(broker,generation);
                    var closing=RuntimeBroker.Slot.class.getDeclaredField("closing");closing.setAccessible(true);closing.setBoolean(slot,true);
                    if("true".equals(input.getString("permanent"))){var close=RuntimeStore.class.getDeclaredMethod("requestClose",String.class);close.setAccessible(true);close.invoke(broker.store,generation);}
                    var installed=broker.apks.get(session.identity.spaceId,session.identity.packageName);
                    write(new File(installed.data,"files/closing-gate"),generation);
                    out.putInt("pid",session.pid);
                }return out;
            }
            case "probeProcessState":var process=broker.contract.getSession(RuntimeWire.required(input,"generation"));out.putString("state",process.state.name());out.putInt("pid",process.pid);return out;
            case "probeProcessExit":{
                var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));
                if(session.state!=ExecutionContract.ProcessState.DEAD||session.pid<=0||session.uid!=android.os.Process.myUid())throw new SecurityException("EXIT_OBSERVATION_REQUIRES_OWN_DEAD_PROCESS");
                long since=Long.parseLong(RuntimeWire.required(input,"since"));
                var history=getSystemService(android.app.ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),session.pid,16);
                for(var exit:history)if(exit.getPid()==session.pid&&exit.getRealUid()==session.uid&&exit.getTimestamp()>=since&&exit.getProcessName().matches(java.util.regex.Pattern.quote(getPackageName())+":slot[0-7]")){
                    out.putString("result",new JSONObject().put("pid",exit.getPid()).put("uid",exit.getRealUid()).put("process",exit.getProcessName()).put("timestamp",exit.getTimestamp()).put("reason",exit.getReason()).put("status",exit.getStatus()).toString());return out;
                }return out;
            }
            case "probeIntegrityCache":return broker.apks.integrityStats();
            case "probeManifestFlags":{
                var installed=broker.apks.get(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"));
                boolean unresolved=false;for(var c:installed.artifacts.components)unresolved|=c.enabled==null||c.exported==null;
                out.putBoolean("rawResourceReference",unresolved);
                int enabled=0,exported=0;StringBuilder flags=new StringBuilder();
                for(var c:installed.components()){if(c.enabled)enabled++;if(c.exported)exported++;flags.append(c.type).append(':').append(c.name).append(':').append(c.enabled).append(':').append(c.exported).append(':').append(c.authorities).append('\n');}
                out.putInt("componentCount",installed.components().size());out.putInt("enabledCount",enabled);out.putInt("disabledCount",installed.components().size()-enabled);out.putInt("exportedCount",exported);out.putString("flagsSha256",ApkStore.textDigest(flags.toString()));return out;
            }
            case "probeActivityInventory":{
                JSONObject inventory=new JSONObject();
                for(String table:new String[]{"activity_components","activity_tickets"}){
                    JSONArray rows=new JSONArray();String where=table.equals("activity_tickets")?" WHERE state NOT IN ('FINISHED','REVOKED') OR componentLease IN (SELECT lease FROM activity_components)":"";
                    try(var c=broker.store.getReadableDatabase().rawQuery("SELECT * FROM "+table+where,null)){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));rows.put(row);}}inventory.put(table,rows);
                }
                JSONArray tasks=new JSONArray();for(var task:((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getAppTasks()){var info=task.getTaskInfo();tasks.put(new JSONObject().put("taskId",info.taskId).put("baseIntent",String.valueOf(info.baseIntent)).put("base",String.valueOf(info.baseActivity)).put("top",String.valueOf(info.topActivity)).put("original",String.valueOf(info.origActivity)));}inventory.put("tasks",tasks);
                return ProbeReports.handoff(this,inventory.toString());
            }
            case "probeActivityFaultData":{
                var install=broker.apks.get(RuntimeWire.required(input,"space"),"dev.tripoder.p2");File file=new File(install.data,"files/new-intent-effect.txt");if(file.length()>64)throw new IllegalStateException("PROBE_EFFECT_TOO_LARGE");out.putString("effect",Files.readString(file.toPath()));return out;
            }
            case "probeActivityLaunches":{
                JSONArray launches=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT l.*,op.state AS execution FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate LEFT JOIN operations op ON op.id=l.operation WHERE t.space=?",new String[]{RuntimeWire.required(input,"space")})){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));launches.put(row);}}
                out.putString("launches",launches.toString());return out;
            }
            case "probeDescriptorCount":out.putInt("count",new File("/proc/self/fd").list().length);return out;
            case "probeHoldMain":{
                if(mainGate!=null)throw new IllegalStateException("PROBE_GATE_ALREADY_HELD");
                var entered=new java.util.concurrent.CountDownLatch(1);var gate=new java.util.concurrent.CountDownLatch(1);mainGate=gate;mainGateExpired=false;
                new Handler(Looper.getMainLooper()).post(()->{entered.countDown();try{if(!gate.await(8,java.util.concurrent.TimeUnit.SECONDS))mainGateExpired=true;}catch(InterruptedException error){Thread.currentThread().interrupt();mainGateExpired=true;}});
                if(!entered.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("PROBE_GATE_NOT_ENTERED");return out;
            }
            case "probeReleaseMain":{
                if(mainGate==null)throw new IllegalStateException("PROBE_GATE_NOT_HELD");out.putBoolean("held",mainGate.getCount()==1&&!mainGateExpired);mainGate.countDown();mainGate=null;return out;
            }
            case "probeBroadcastState":{
                String space=RuntimeWire.required(input,"space");
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM broadcast_deliveries b JOIN sessions s ON b.source=s.generation WHERE s.space=? AND s.package='dev.tripoder.peer'",new String[]{space})){c.moveToFirst();out.putInt("peerDeliveries",c.getInt(0));}
                for(String state:new String[]{"COMPLETED","unresolved"})try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM broadcast_deliveries b JOIN sessions s ON b.target=s.generation WHERE s.space=? AND b.state"+(state.equals("COMPLETED")?"='COMPLETED'":"!='COMPLETED'"),new String[]{space})){c.moveToFirst();out.putInt(state.equals("COMPLETED")?"completed":state,c.getInt(0));}
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM broadcast_registrations WHERE space=? AND state!='CLOSED'",new String[]{space})){c.moveToFirst();out.putInt("registrations",c.getInt(0));}
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM broadcast_deliveries b JOIN sessions s ON b.target=s.generation JOIN operations op ON op.id=b.operation WHERE s.space=? AND b.state='COMPLETED' AND op.state!='SUCCEEDED'",new String[]{space})){c.moveToFirst();out.putInt("journalMismatch",c.getInt(0));}return out;
            }
            case "probeProviderState":{
                String space=RuntimeWire.required(input,"space");
                JSONArray unknown=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT pc.operation,pc.kind,pc.uri,pc.delivery,op.state FROM provider_calls pc JOIN provider_acquisitions pa ON pc.cap=pa.cap JOIN sessions s ON pa.source=s.generation JOIN operations op ON pc.operation=op.id WHERE s.space=? AND pc.state='UNKNOWN'",new String[]{space})){while(c.moveToNext())unknown.put(new JSONObject().put("operation",c.getString(0)).put("kind",c.getString(1)).put("uri",c.getString(2)).put("delivery",c.getString(3)).put("execution",c.getString(4)));}out.putString("unknown",unknown.toString());
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM provider_calls pc JOIN provider_acquisitions pa ON pc.cap=pa.cap JOIN sessions s ON pa.source=s.generation JOIN operations op ON pc.operation=op.id WHERE s.space=? AND op.state='SUCCEEDED' AND pc.delivery!='RECEIVED'",new String[]{space})){c.moveToFirst();out.putInt("deliveryUnconfirmed",c.getInt(0));}
                for(String state:new String[]{"OPEN","CLOSED"})try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM provider_acquisitions pa JOIN provider_leases pl ON pa.lease=pl.id JOIN sessions s ON s.generation=pl.generation WHERE s.space=? AND pa.state=?",new String[]{space,state})){c.moveToFirst();out.putInt(state,c.getInt(0));}
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM provider_calls pc JOIN provider_acquisitions pa ON pc.cap=pa.cap JOIN provider_leases pl ON pl.id=pa.lease JOIN sessions s ON s.generation=pl.generation WHERE s.space=? AND pc.state IN ('PREPARED','RUNNING','UNKNOWN')",new String[]{space})){c.moveToFirst();out.putInt("unresolved",c.getInt(0));}
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM provider_calls pc JOIN provider_acquisitions pa ON pc.cap=pa.cap JOIN provider_leases pl ON pl.id=pa.lease JOIN sessions s ON s.generation=pl.generation JOIN operations op ON pc.operation=op.id WHERE s.space=? AND pc.state='DONE' AND op.state NOT IN ('SUCCEEDED','FAILED')",new String[]{space})){c.moveToFirst();out.putInt("journalMismatch",c.getInt(0));}
                JSONArray open=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT pa.authority,pa.stable,s.processName,source.processName FROM provider_acquisitions pa JOIN provider_leases pl ON pl.id=pa.lease JOIN sessions s ON s.generation=pl.generation JOIN sessions source ON source.generation=pa.source WHERE s.space=? AND pa.state='OPEN'",new String[]{space})){while(c.moveToNext())open.put(new JSONObject().put("authority",c.getString(0)).put("stable",c.getInt(1)).put("target",c.getString(2)).put("source",c.getString(3)));}out.putString("openReferences",open.toString());
                break;
            }
            case "probeProviderInstallUnused":broker.catalog.requireUnused(RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"));break;
            case "probeActivityTickets":{
                JSONArray tickets=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT * FROM activity_tickets WHERE space=?",new String[]{RuntimeWire.required(input,"space")})){
                    while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));tickets.put(row);}}
                out.putString("tickets",tickets.toString());break;
            }
            case "probeSessions":{
                String run=RuntimeWire.required(input,"runId");if(!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("PROBE_RUN_INVALID");
                JSONArray sessions=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT generation,space,package,installGeneration,pid,state,processName,activityStub,authorityGeneration FROM sessions WHERE space LIKE ?",new String[]{run+"-%"})){
                    while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));sessions.put(row);}}
                out.putString("sessions",sessions.toString());break;
            }
            case "probeStartHistory":{
                JSONArray rows=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT generation,pid,processName,startedNs,onCreateNs,readyNs,failedNs,state FROM application_starts WHERE space=? ORDER BY sequence",new String[]{RuntimeWire.required(input,"space")})){
                    while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));rows.put(row);}}
                out.putString("starts",rows.toString());break;
            }
            case "probeInstall":{
                JSONArray files=new JSONArray(RuntimeWire.required(input,"files"));List<ApkStore.Source> sources=new ArrayList<>();
                for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);sources.add(broker.apks.asset(file.getString("asset"),file.getString("sha256")));}
                var installed=broker.apks.install(RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"space"),sources,input.getString("expectedGeneration"),fault(input));
                out.putString("generation",installed.generation);out.putString("descriptorSha",installed.artifacts.descriptorSha);break;
            }
            case "probeUninstall":broker.apks.uninstall(RuntimeWire.required(input,"operation"),RuntimeWire.required(input,"space"),RuntimeWire.required(input,"package"),RuntimeWire.required(input,"expectedGeneration"),fault(input));break;
            case "probeCrash":fault(input).at(input.getString("crashAt"));throw new IllegalStateException("CRASH_POINT_REQUIRED");
            case "probeInventory":{
                Set<String> referenced=new HashSet<>();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT generation FROM installs",null)){while(c.moveToNext())referenced.add(c.getString(0));}
                File artifacts=new File(getFilesDir(),"runtime/artifacts");File[] files=artifacts.listFiles();int orphans=0;if(files!=null)for(File file:files)if(!referenced.contains(file.getName()))orphans++;
                out.putInt("orphans",orphans);File[] staged=new File(getFilesDir(),"runtime/staging").listFiles();out.putInt("staged",staged==null?0:staged.length);break;
            }
            case "probeArtifactEntries":{
                var installed=broker.apks.get(input.getString("space"),input.getString("package"));out.putString("entries",Arrays.toString(installed.artifacts.directory.list()));break;
            }
            case "probeSecondWriter":{
                boolean denied=false;try(var duplicate=new RuntimeStore(getApplicationContext())){duplicate.getWritableDatabase();}catch(IllegalStateException expected){denied=expected.getMessage().equals("AUTHORITY_ACQUIRE_FAILED");}
                out.putBoolean("denied",denied);break;
            }
            case "probeClosedWriter":{
                broker.store.close();boolean denied=false;
                try(var successor=new RuntimeStore(getApplicationContext())){successor.getWritableDatabase();try{broker.store.getWritableDatabase();}catch(IllegalStateException expected){denied="AUTHORITY_CLOSED".equals(expected.getMessage());}}
                out.putBoolean("denied",denied);break;
            }
            case "probeCorruptNative":{
                var installed=broker.apks.get(input.getString("space"),input.getString("package"));File library=new File(installed.artifacts.directory,installed.artifacts.nativeFiles.get(0).path);
                long size=library.length();var time=Files.getLastModifiedTime(library.toPath());Os.chmod(library.toString(),0600);
                if(input.getBoolean("sameSize")){try(var stream=new RandomAccessFile(library,"rw")){stream.seek(size-1);int prior=stream.read();stream.seek(size-1);stream.write(prior^1);stream.getFD().sync();}Files.setLastModifiedTime(library.toPath(),time);}
                else try(var stream=new FileOutputStream(library,true)){stream.write(9);stream.getFD().sync();}
                Os.chmod(library.toString(),0444);out.putBoolean("sizeUnchanged",library.length()==size);out.putBoolean("mtimeUnchanged",Files.getLastModifiedTime(library.toPath()).equals(time));out.putString("corruptSha",ApkStore.digest(library));break;
            }
            case "probeEffect":{
                String op=safe(RuntimeWire.required(input,"operation"));var admitted=broker.contract.accept(op,"p1-controller",ApkStore.textDigest("one-effect:"+op));
                if(admitted.created){broker.contract.start(op);try(var stream=new FileOutputStream(effect(op),true)){stream.write("effect\n".getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}fault(input).at("AFTER_EFFECT");broker.contract.succeed(op,"one-effect");}
                out.putBoolean("created",admitted.created);out.putString("state",broker.contract.query(op).state.name());out.putString("content",Files.readString(effect(op).toPath()));break;
            }
            case "probeReconcileEffect":{
                String op=safe(RuntimeWire.required(input,"operation"));if(!Files.readString(effect(op).toPath()).equals("effect\n"))throw new IllegalStateException("EFFECT_EVIDENCE_MISMATCH");
                broker.contract.reconcile(op,ExecutionContract.OperationState.SUCCEEDED,"one-effect","file-sha256="+ApkStore.digest(effect(op)));break;
            }
            case "probeMigrationSource":{
                File source=source(input);if(source.exists())throw new IllegalStateException("SOURCE_ALREADY_EXISTS");if(!new File(source,"runtime").mkdirs()||!new File(source,"scripts").mkdir())throw new IOException("SOURCE_CREATE_FAILED");
                write(new File(source,"project.json"),new JSONObject().put("schema",1).put("id",input.getString("projectId")).put("app",new JSONObject().put("packageName",input.getString("package")).put("runnable",true).put("source","installed")).toString());
                write(new File(source,"scripts/main.ts"),"// self-owned migration source\n");write(new File(source,"runtime/value.txt"),input.getString("value"));
                out.putString("sourceSha",ProjectBindings.inspectSource(source).digest);break;
            }
            case "probeMigrate":{
                var ref=projects().migrate(input.getString("operation"),input.getString("projectId"),source(input),input.getString("space"),input.getString("package"),input.getString("expectedGeneration"),input.getString("sourceSha"),fault(input));
                out.putString("projectId",ref.projectId);out.putString("dataId",ref.dataId);break;
            }
            case "probeResolveProject":{
                var install=projects().resolve(input.getString("projectId"));out.putString("data",install.data.toString());out.putString("value",Files.readString(new File(install.data,"value.txt").toPath()));out.putString("generation",install.generation);break;
            }
            case "probeSourceChanged":write(new File(source(input),"scripts/main.ts"),"changed after snapshot");break;
            case "probeSourceIdentityChanged":{
                File metadata=new File(source(input),"project.json");JSONObject value=new JSONObject(Files.readString(metadata.toPath()));value.put("id","different-project");write(metadata,value.toString());out.putString("sourceSha",ProjectBindings.inspectSource(source(input)).digest);break;
            }
            default:return super.dispatch(input);
        }
        return out;
    }
    private File receiverGateDirectory(Bundle input)throws Exception{var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));if(!session.identity.packageName.equals("dev.tripoder.p2"))throw new SecurityException("RECEIVER_FIXTURE_REQUIRED");return new File(broker.apks.get(session.identity.spaceId,session.identity.packageName).data,"files");}
    private ProjectBindings projects(){return broker.projects;}
    private File source(Bundle input){return new File(getFilesDir(),"migration-sources/"+safe(input.getString("projectId")));}
    private File effect(String op){return new File(getFilesDir(),"p1-effect-"+op);}
    private static String safe(String value){if(value==null||!value.matches("[a-zA-Z0-9_-]{1,150}"))throw new IllegalArgumentException("INVALID_TEST_ID");return value;}
    private static void write(File file,String data)throws Exception{try(var out=new FileOutputStream(file)){out.write(data.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
}
