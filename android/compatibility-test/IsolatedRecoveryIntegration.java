package dev.tripoder.hooklab;

import android.app.Instrumentation;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.system.*;
import dev.tripoder.control.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real owned authority SIGABRT, held isolated service, durable reopen and explicit fresh execution. */
public final class IsolatedRecoveryIntegration extends Instrumentation {
    private static final String PKG="dev.tripoder.isolatedservicecontract",SPACE="paired-dev-tripoder-isolatedservicecontract";
    private Bundle args;private WorkbenchApplication app;private JSONObject report;private final JSONArray checks=new JSONArray();private File output;private String install,dataId;
    private final List<Link> links=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    private static String id(){return UUID.randomUUID().toString();}
    private void save(String phase)throws Exception{report.put("phase",phase).put("checks",checks);Files.writeString(output.toPath(),report.toString());}
    private void check(String name,boolean value)throws Exception{if(!value)throw new AssertionError(name);checks.put(name);save(report.optString("phase","running"));}
    private Bundle execute(String command,String...pairs)throws Exception{return app.engine.executeBlocking(ControlWire.request(command,pairs));}
    private final class Link implements ServiceConnection {
        final CountDownLatch ready=new CountDownLatch(1);volatile IBinder peer;boolean bound;
        public void onServiceConnected(ComponentName name,IBinder peer){this.peer=peer;ready.countDown();}
        public void onServiceDisconnected(ComponentName name){}
    }
    private Link bind(String service)throws Exception{
        Link link=new Link();links.add(link);link.bound=app.bindService(new Intent().setClassName(app,service),link,Context.BIND_AUTO_CREATE);
        if(!link.bound||!link.ready.await(8,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("OWNED_RECOVERY_BIND_FAILED:"+service);return link;
    }
    private void unbind(Link link){if(link!=null&&link.bound){link.bound=false;app.unbindService(link);}}
    private SQLiteDatabase database(){return SQLiteDatabase.openDatabase(app.getDatabasePath("tripoder-runtime-v2.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);}
    private JSONObject ownFaultRecord(String operation)throws Exception{
        if(!UUID.fromString(operation).toString().equals(operation))throw new SecurityException("OWNED_FAULT_OPERATION_REQUIRED");JSONObject out=new JSONObject();
        try(var db=database();var row=db.rawQuery("SELECT id,caller,digest,state,result,detail,created,updated,evidence FROM operations WHERE id=?",new String[]{operation})){
            if(!row.moveToFirst())throw new SecurityException("OWNED_FAULT_RECORD_REQUIRED");for(String name:new String[]{"id","caller","digest","state","result","detail","created","updated","evidence"})out.put(name,row.isNull(row.getColumnIndexOrThrow(name))?JSONObject.NULL:row.getString(row.getColumnIndexOrThrow(name)));
        }return out;
    }
    private File ownedFile(String name)throws Exception{
        if(dataId==null||!dataId.matches("[0-9a-f-]{36}")||!Set.of("owned-recovery-start-count","owned-recovery-ready.json").contains(name))throw new SecurityException("OWNED_RECOVERY_FILE_REQUIRED");
        File root=new File(app.getFilesDir().getCanonicalFile(),"runtime/data/"+SPACE+"/"+PKG+"/"+dataId+"/files");File file=new File(root,name);
        if(!file.getCanonicalPath().equals(file.getAbsolutePath()))throw new SecurityException("OWNED_RECOVERY_FILE_SYMLINK");return file;
    }
    private int starts()throws Exception{File file=ownedFile("owned-recovery-start-count");if(!file.exists())return 0;if(file.length()>12)throw new SecurityException("OWNED_RECOVERY_COUNTER_SHAPE");return Integer.parseInt(Files.readString(file.toPath()));}
    private static boolean absent(int pid)throws Exception{
        if(pid<=0)throw new SecurityException("OWNED_RECORDED_PID_REQUIRED");try{Os.kill(pid,0);return false;}catch(ErrnoException result){if(result.errno==OsConstants.ESRCH)return true;if(result.errno==OsConstants.EPERM||result.errno==OsConstants.EACCES)return false;throw result;}
    }
    private void awaitAbsence(int...pids)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(true){boolean all=true;for(int pid:pids)all&=absent(pid);if(all)return;if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("OWNED_ISOLATED_DEATH_UNCONFIRMED");SystemClock.sleep(50);}
    }
    private JSONObject ready(Bundle launch,int expected)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+35000;
        do{
            File file=ownedFile("owned-recovery-ready.json");JSONObject value=null;
            if(file.isFile()&&file.length()<2048)try{value=new JSONObject(Files.readString(file.toPath()));}catch(JSONException transientWrite){}
            if(value!=null&&value.optInt("starts")==expected&&value.optInt("parentPid")==launch.getInt("pid")){
                JSONObject metadata=IsolatedExecutionInspection.inspect(app,SPACE,PKG,install,launch.getString("generation"));String authority=metadata.getString("executionAuthorityGeneration");
                try(var db=database();var rows=db.rawQuery("SELECT s.generation,s.pid,s.realUid,l.id FROM sessions s JOIN service_leases l ON l.generation=s.generation AND l.id=s.processInstance WHERE s.space=? AND s.package=? AND s.installGeneration=? AND s.authorityGeneration=? AND s.processKind='ISOLATED_SERVICE' AND s.state='RUNNING' AND l.state='LIVE' AND l.isolatedInstanceName='NAMED:RECOVERY' AND EXISTS (SELECT 1 FROM service_calls c WHERE c.lease=l.id AND c.source=? AND c.kind='bind' AND c.state='APPLIED') AND NOT EXISTS (SELECT 1 FROM service_calls c WHERE c.lease=l.id AND c.state='PREPARED')",new String[]{SPACE,PKG,install,authority,launch.getString("generation")})){
                    if(rows.moveToFirst()&&rows.getInt(1)==value.getInt("isolatedPid")&&rows.getInt(2)==value.getInt("isolatedUid")){
                        value.put("processGeneration",rows.getString(0)).put("lease",rows.getString(3)).put("authority",authority);if(rows.moveToNext())throw new SecurityException("OWNED_RECOVERY_INSTANCE_AMBIGUOUS");return value;
                    }
                }
            }
            SystemClock.sleep(50);
        }while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("OWNED_RECOVERY_HOLD_NOT_READY");
    }
    private Bundle launch(Bundle target)throws Exception{
        Bundle value=execute("prepareLaunch","operation",id(),"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation"),"generation",install,"component",PKG+".RecoveryHold");Intent intent=value.getParcelable("intent",Intent.class);if(intent==null)throw new SecurityException("OWNED_RECOVERY_ENTRY_REQUIRED");runOnMainSync(()->app.startActivity(intent));return value;
    }
    private JSONObject recovery(Bundle value)throws Exception{JSONObject out=new JSONObject();for(String key:new String[]{"state","reason","epoch","automaticAttempts","totalAttempts","supervisorPid"})if(value.containsKey(key))out.put(key,value.get(key));return out;}
    private Bundle awaitRecovery(IBinder supervisor)throws Exception{
        boolean retried=false;long deadline=SystemClock.elapsedRealtime()+70000;
        do{Bundle value=ControlWire.call(supervisor,RecoveryWire.STATUS,new Bundle());String state=value.getString("state");if(state.equals("READY")&&value.getBinder("entry")!=null){report.put("explicitRetryOnceUsed",retried);return value;}
            if(state.equals("OPEN")&&!retried){ControlWire.call(supervisor,RecoveryWire.RETRY_ONCE,new Bundle());retried=true;}
            else if(state.equals("BLOCKED")||state.equals("STOPPED"))throw new IllegalStateException("OWNED_RECOVERY_BLOCKED:"+recovery(value));SystemClock.sleep(50);
        }while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("OWNED_RECOVERY_READY_TIMEOUT");
    }
    private void retired(JSONObject before)throws Exception{
        try(var db=database();var row=db.rawQuery("SELECT s.state,l.state FROM sessions s JOIN service_leases l ON l.id=s.processInstance WHERE s.generation=? AND s.pid=? AND s.realUid=? AND s.authorityGeneration=? AND l.id=?",new String[]{before.getString("processGeneration"),Integer.toString(before.getInt("isolatedPid")),Integer.toString(before.getInt("isolatedUid")),before.getString("authority"),before.getString("lease")})){
            check("exact-isolated-session-dead-and-lease-released",row.moveToFirst()&&row.getString(0).equals("DEAD")&&row.getString(1).equals("RELEASED"));
        }
    }
    private void run()throws Exception{
        long until=SystemClock.elapsedRealtime()+35000;while((app.engine==null||!app.engine.ready())&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);if(app.engine==null||!app.engine.ready())throw new IllegalStateException("QA_ENGINE_NOT_CONNECTED");
        JSONObject original=JobMutationInspection.installation(app,SPACE,PKG);install=original.getString("generation");dataId=original.getString("dataId");
        execute("stopApplication","operation",id(),"space",SPACE,"package",PKG,"generation",install);
        File fixture=new File(args.getString("fixture"));if(!fixture.getCanonicalPath().startsWith(app.getExternalFilesDir(null).getCanonicalPath()+"/"))throw new SecurityException("OWNED_RECOVERY_FIXTURE_PATH");
        try(var fd=ParcelFileDescriptor.open(fixture,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","operation",id(),"space",SPACE,"expectedGeneration",install);request.putParcelable("fd0",fd);request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(args.getString("fixtureSha"))));
            Bundle installed=app.engine.executeBlocking(request);if(!PKG.equals(installed.getString("package")))throw new SecurityException("OWNED_RECOVERY_FIXTURE_PACKAGE_CHANGED");install=installed.getString("generation");
        }
        JSONObject upgraded=JobMutationInspection.installation(app,SPACE,PKG);check("fixture-upgrade-keeps-existing-data-incarnation",dataId.equals(upgraded.getString("dataId")));report.put("installGeneration",install).put("dataIncarnation",dataId);
        Bundle target=execute("applicationTarget","space",SPACE,"package",PKG,"generation",install);
        int before=starts();save("launching-held-instance");Bundle first=launch(target);JSONObject held=ready(first,before+1);report.put("heldBeforeCrash",held).put("parentBeforeCrash",WorkbenchAgentLifecycle.json(first));check("real-isolated-instance-held-before-fault",android.os.Process.isIsolatedUid(held.getInt("isolatedUid"))&&held.getInt("isolatedUid")!=android.os.Process.myUid());
        try(var db=database();var row=db.rawQuery("SELECT COUNT(*) FROM sessions WHERE authorityGeneration=? AND state!='DEAD' AND NOT(space=? AND package=? AND installGeneration=?)",new String[]{held.getString("authority"),SPACE,PKG,install})){row.moveToFirst();check("authority-has-no-unrelated-live-qa-generation",row.getInt(0)==0);}
        check("held-launch-already-acknowledged",execute("query","operation",first.getString("operation")).getString("state").equals("SUCCEEDED"));
        Link supervisor=bind(RecoveryWire.SERVICE),fault=bind("dev.tripoder.runtime.RecoveryFaultService");Bundle status=ControlWire.call(supervisor.peer,RecoveryWire.STATUS,new Bundle());String oldLease=app.engine.lease;Bundle identity=ControlWire.call(fault.peer,ControlWire.EXECUTE,ControlWire.request("identity"));check("fault-target-is-exact-own-authority",identity.getString("authority").equals(held.getString("authority")));
        report.put("authorityBefore",identity.getInt("pid")).put("recoveryBefore",recovery(status));CountDownLatch dead=new CountDownLatch(1);fault.peer.linkToDeath(dead::countDown,0);
        String operation=id(),scope="owned-isolated-recovery-"+args.getString("run");report.put("faultOperation",operation).put("ownedCounterScope",scope);save("crashing-own-authority");
        Bundle effect=ControlWire.request("effect","operation",operation,"space",scope);effect.putBoolean("crash",true);boolean disconnected=false;
        try{ControlWire.call(fault.peer,ControlWire.EXECUTE,effect);}catch(DeadObjectException expected){disconnected=true;}
        check("real-authority-binder-death-observed",disconnected&&dead.await(15,TimeUnit.SECONDS));unbind(fault);awaitAbsence(identity.getInt("pid"),first.getInt("pid"),held.getInt("isolatedPid"));check("kernel-confirms-old-authority-parent-and-isolated-absence",true);save("reopening-authority");
        Bundle restored=awaitRecovery(supervisor.peer);report.put("recoveryAfter",recovery(restored));check("one-new-authority-attempt-only",restored.getInt("totalAttempts")==status.getInt("totalAttempts")+1);
        until=SystemClock.elapsedRealtime()+30000;while((!app.engine.ready()||oldLease.equals(app.engine.lease))&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);check("fresh-controller-lease-after-real-death",app.engine.ready()&&!oldLease.equals(app.engine.lease));
        Link secondFault=bind("dev.tripoder.runtime.RecoveryFaultService");Bundle newIdentity=ControlWire.call(secondFault.peer,ControlWire.EXECUTE,ControlWire.request("identity"));check("authority-generation-replaced",!newIdentity.getString("authority").equals(identity.getString("authority")));report.put("authorityAfter",newIdentity.getInt("pid"));
        Bundle unknown=execute("query","operation",operation);check("original-incomplete-effect-remains-unknown",unknown.getString("state").equals("UNKNOWN")&&unknown.getString("command").equals("owned-recovery-effect")&&unknown.getString("effectScope").equals("space/"+scope));JSONObject immutableFault=ownFaultRecord(operation);report.put("originalFaultReceipt",WorkbenchAgentLifecycle.json(unknown)).put("originalFaultRecord",immutableFault);
        File counter=new File(app.getExternalFilesDir(null),"recovery-effect-"+operation);check("fault-effect-dispatched-exactly-once",counter.length()==1&&Files.readString(counter.toPath()).equals("1"));check("guest-was-not-automatically-replayed",starts()==before+1);
        boolean rejected=false;try{ControlWire.call(secondFault.peer,ControlWire.EXECUTE,ControlWire.request("effect","operation",operation,"space",scope));}catch(IllegalStateException expected){rejected=String.valueOf(expected.getMessage()).contains("ORIGINAL_OPERATION_QUERY_REQUIRED");}check("same-effect-id-cannot-replay",rejected);
        rejected=false;try{ControlWire.call(secondFault.peer,ControlWire.EXECUTE,ControlWire.request("effect","operation",id(),"space",scope));}catch(IllegalStateException expected){rejected=String.valueOf(expected.getMessage()).contains("RECOVERY_SCOPE_UNKNOWN");}check("new-id-cannot-bypass-original-effect-scope",rejected);unbind(secondFault);retired(held);
        save("explicit-fresh-held-instance");Bundle fresh=launch(target);JSONObject again=ready(fresh,before+2);report.put("heldAfterExplicitStart",again);check("explicit-start-creates-fresh-isolated-generation",!again.getString("processGeneration").equals(held.getString("processGeneration"))&&!again.getString("lease").equals(held.getString("lease")));
        save("stopping-live-isolated-instance");String stopped=id();long start=SystemClock.elapsedRealtime();execute("stopApplication","operation",stopped,"space",SPACE,"package",PKG,"generation",install);report.put("liveStopElapsedMs",SystemClock.elapsedRealtime()-start).put("liveStopOperation",stopped);awaitAbsence(fresh.getInt("pid"),again.getInt("isolatedPid"));retired(again);
        check("live-isolated-controller-stop-is-confirmed",execute("query","operation",stopped).getString("state").equals("SUCCEEDED"));
        JSONObject finalRows=IsolatedExecutionInspection.inspect(app,SPACE,PKG,install,fresh.getString("generation"));report.put("executionAfterStop",finalRows);check("expected-live-isolated-stop-adds-no-child-pause",finalRows.getInt("currentAuthorityIsolatedPausedGenerations")==0);check("only-two-explicit-guest-starts",starts()==before+2);check("old-effect-counter-still-one",Files.readString(counter.toPath()).equals("1"));
        check("old-unknown-receipt-not-rewritten",immutableFault.toString().equals(ownFaultRecord(operation).toString()));report.put("passed",true);
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        app=(WorkbenchApplication)getTargetContext().getApplicationContext();String run=args.getString("run");if(!app.getPackageName().startsWith("dev.tripoder.compat")||run==null||!run.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("OWNED_QA_RECOVERY_REQUIRED");report=new JSONObject().put("run",run).put("package",PKG).put("space",SPACE).put("dataCleared",false).put("accountDataRead",false);output=new File(app.getExternalFilesDir(null),"isolated-recovery-"+run+".json");save("connecting");run();save("complete");
    }catch(Throwable failure){try{if(report==null)report=new JSONObject();report.put("passed",false).put("error",android.util.Log.getStackTraceString(failure));if(output!=null)save("failed");}catch(Exception ignored){}}
        finally{for(var link:links)try{unbind(link);}catch(Exception ignored){}}
        result.putString("report",report.toString());finish(-1,result);
    }
}
