package dev.tripoder.runtime;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;
import java.util.function.BiConsumer;

/** Owned SQLite/contract models; synthetic identities never become real processes. */
final class IsolatedSessionChecks {
    private final BiConsumer<String,Boolean> check;
    IsolatedSessionChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private static String scalar(SQLiteDatabase db,String sql){try(var c=db.rawQuery(sql,null)){return c.moveToFirst()?c.getString(0):null;}}
    interface Work{void run()throws Exception;}
    private void denied(String name,Work work)throws Exception{boolean refused=false;try{work.run();}catch(IllegalStateException expected){refused=true;}check.accept(name,refused);}
    private void secure(String name,Work work)throws Exception{boolean refused=false;try{work.run();}catch(SecurityException expected){refused=true;}check.accept(name,refused);}
    void run(Context target,File scratch,Context assets)throws Exception{
        contract();recovery();
        File directory=new File(scratch,"isolated-models");if(!directory.mkdir())throw new IllegalStateException("ISOLATED_MODEL_DIRECTORY");
        Context owned=new ContextWrapper(target){
            public File getFilesDir(){return directory;}
            public File getDatabasePath(String name){return new File(directory,name);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).toString(),factory,handler);}
        };
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            var db=broker.store.getWritableDatabase();db.execSQL("INSERT INTO spaces VALUES('owned-isolated',1)");
            byte[] apk;try(var input=assets.getAssets().open("isolated-service.apk")){apk=input.readAllBytes();}
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(apk));
            var install=broker.apks.install("owned-isolated-import","owned-isolated",List.of(new ApkStore.Source("base.apk",hash,()->new java.io.ByteArrayInputStream(apk))),null,ApkStore.NO_FAULT);
            String process=install.info.packageName+":owned_isolated",component=install.info.packageName+".OwnedService";
            var a=broker.isolated.reserve(install,process,component,"NAMED:A");String gen=a.getString("generation"),lease=a.getString("lease");
            android.os.Bundle counterfeit=new android.os.Bundle();counterfeit.putString("generation",gen);counterfeit.putString("lease",lease);counterfeit.putBinder("peer",new android.os.Binder());
            secure("isolated-endpoint-rejects-real-host-uid-before-capability-consumption",()->RuntimeWire.call(a.getBinder("isolatedAuthority"),RuntimeWire.ISOLATED_BOOTSTRAP,counterfeit));
            android.os.Bundle wrong=new android.os.Bundle(counterfeit);wrong.putString("lease","wrong-lease");secure("isolated-endpoint-rejects-wrong-lease",()->RuntimeWire.call(a.getBinder("isolatedAuthority"),RuntimeWire.ISOLATED_BOOTSTRAP,wrong));
            secure("isolated-endpoint-rejects-unregistered-control",()->RuntimeWire.call(a.getBinder("isolatedAuthority"),RuntimeWire.ISOLATED_REGISTER,counterfeit));
            check.accept("isolated-model-normal-process-lookup-excludes-instance",broker.findProcess(install.space,install.info.packageName,process)==null&&broker.services.find(install.space,install.info.packageName,component)==null&&broker.services.retainedStub(install.space,install.info.packageName,install.generation,process)==null);
            var repeat=broker.isolated.reserve(install,process,component,"NAMED:A");check.accept("isolated-model-same-instance-reuses-one-capability",gen.equals(repeat.getString("generation"))&&a.getBinder("isolatedAuthority")==repeat.getBinder("isolatedAuthority"));
            var other=broker.isolated.reserve(install,process,component,"NAMED:B");check.accept("isolated-model-distinct-instance-has-distinct-generation",!gen.equals(other.getString("generation")));
            var otherSlot=broker.processSlots().stream().filter(slot->slot.allocation.session.processGeneration.equals(other.getString("generation"))).findFirst().orElseThrow();
            broker.store.session(broker.contract.attach(otherSlot.allocation.bootstrapToken,12345,99302));broker.services.claimIsolated(other.getString("lease"),other.getString("generation"));broker.services.lifecycle(other.getString("lease"),other.getString("generation"),other.getString("lease"),"onCreate");broker.services.lifecycle(other.getString("lease"),other.getString("generation"),other.getString("lease"),"onDestroy");
            // Simulate this scratch peer's death callback, not a live PID action.
            var death=other.getBinder("isolatedAuthority").getClass().getDeclaredMethod("dead");death.setAccessible(true);death.invoke(other.getBinder("isolatedAuthority"));
            check.accept("isolated-expected-destroy-does-not-pause-application",scalar(db,"SELECT COUNT(*) FROM recovery_paused WHERE installGeneration='"+install.generation+"'").equals("0"));
            check.accept("isolated-observed-death-retires-lease-and-capability",broker.store.isDead(other.getString("generation"))&&broker.services.get(other.getString("lease")).state.equals("RELEASED")&&!broker.isolated.owns(other.getString("generation")));
            var closing=broker.isolated.reserve(install,process,component,"NAMED:C");String closingGeneration=closing.getString("generation"),closingLease=closing.getString("lease");
            var closingSlot=broker.processSlots().stream().filter(slot->slot.allocation.session.processGeneration.equals(closingGeneration)).findFirst().orElseThrow();
            broker.store.session(broker.contract.attach(closingSlot.allocation.bootstrapToken,12346,99303));broker.services.claimIsolated(closingLease,closingGeneration);broker.services.lifecycle(closingLease,closingGeneration,closingLease,"onCreate");broker.store.session(broker.contract.markRunning(closingGeneration,12346,99303));
            var closingOwner=broker.services.get(closingLease);String firstConnection=UUID.randomUUID().toString(),secondConnection=UUID.randomUUID().toString();
            broker.services.operation("closing-bind-one",closingOwner,"closing-source","bind",firstConnection);broker.services.complete("closing-bind-one","closing-source",true);
            broker.services.operation("closing-bind-two",closingOwner,"closing-source","bind",secondConnection);
            broker.services.operation("closing-unbind-one",closingOwner,"closing-source","unbind",firstConnection);broker.isolated.unbinding(closingLease);
            check.accept("isolated-final-unbind-pending-bind-keeps-admission-open",!closingSlot.closing);
            broker.services.complete("closing-bind-two","closing-source",true);broker.isolated.unbinding(closingLease);
            check.accept("isolated-final-unbind-other-live-binding-keeps-admission-open",!closingSlot.closing);
            broker.services.operation("closing-unbind-foreign-source",closingOwner,"foreign-source","unbind",secondConnection);broker.isolated.unbinding(closingLease);
            check.accept("isolated-final-unbind-cannot-borrow-another-source-request",!closingSlot.closing);
            broker.services.operation("closing-unbind-two",closingOwner,"closing-source","unbind",secondConnection);broker.isolated.unbinding(closingLease);
            check.accept("isolated-final-unbind-closes-admission-before-android-ack",closingSlot.closing&&broker.contract.getSession(closingGeneration).state==ExecutionContract.ProcessState.RUNNING&&!broker.store.isDead(closingGeneration));
            denied("isolated-final-unbind-does-not-lend-closing-instance",()->broker.isolated.reserve(install,process,component,"NAMED:C"));
            var closingDeath=closing.getBinder("isolatedAuthority").getClass().getDeclaredMethod("dead");closingDeath.setAccessible(true);closingDeath.invoke(closing.getBinder("isolatedAuthority"));
            check.accept("isolated-ams-last-unbind-death-needs-no-destroy-ack",broker.store.isDead(closingGeneration)&&broker.services.get(closingLease).state.equals("RELEASED"));
            check.accept("isolated-ams-last-unbind-death-does-not-pause-application",!broker.store.recoveryPaused(closingGeneration));
            check.accept("isolated-final-unbind-death-keeps-unacknowledged-request-prepared",scalar(db,"SELECT COUNT(*) FROM service_calls WHERE lease='"+closingLease+"' AND source='closing-source' AND kind='unbind' AND state='PREPARED'").equals("2"));
            String source="owned-source",one="owned-bind-one",two="owned-bind-two";
            broker.services.operation(one,broker.services.get(lease),source,"bind",UUID.randomUUID().toString());broker.services.operation(two,broker.services.get(lease),source,"bind",UUID.randomUUID().toString());
            broker.services.complete(one,source,false);broker.isolated.rejectedBeforeBootstrap(one,source);
            check.accept("isolated-model-rejected-bind-keeps-other-pending-capability",!broker.store.isDead(gen)&&broker.isolated.owns(gen));
            broker.services.complete(two,source,true);broker.isolated.rejectedBeforeBootstrap(one,source);
            check.accept("isolated-model-rejected-bind-keeps-other-accepted-capability",!broker.store.isDead(gen)&&broker.isolated.owns(gen));
            String unbind="owned-unbind";broker.services.operation(unbind,broker.services.get(lease),source,"unbind",scalar(db,"SELECT connection FROM service_calls WHERE operation='owned-bind-two'"));broker.services.complete(unbind,source,true);broker.isolated.rejectedBeforeBootstrap(one,source);
            check.accept("isolated-model-all-rejected-bind-revokes-unconsumed-capability",broker.store.isDead(gen)&&!broker.isolated.owns(gen)&&broker.services.get(lease).state.equals("RELEASED"));
            var next=broker.isolated.reserve(install,process,component,"NAMED:A");check.accept("isolated-model-fresh-bind-never-reuses-retired-generation",!next.getString("generation").equals(gen)&&!next.getString("lease").equals(lease)&&!next.getString("isolatedPhysicalInstance").equals(a.getString("isolatedPhysicalInstance")));
            check.accept("isolated-model-retirement-keeps-original-rejected-call",scalar(db,"SELECT state FROM service_calls WHERE operation='owned-bind-one'").equals("REJECTED"));
            String freshAuthority=UUID.randomUUID().toString();
            check.accept("isolated-recovery-current-authority-cannot-retire-unstarted",RuntimeIsolatedSessions.retireUnstarted(db,broker.store.authorityGeneration)==0);
            db.beginTransaction();try{db.execSQL("UPDATE service_leases SET state='CLAIMED',instance=id WHERE id=?",new Object[]{next.getString("lease")});check.accept("isolated-recovery-partial-claim-not-unstarted",RuntimeIsolatedSessions.retireUnstarted(db,freshAuthority)==0);}finally{db.endTransaction();}
            db.beginTransaction();try{db.execSQL("UPDATE service_leases SET package='dev.owned.foreign' WHERE id=?",new Object[]{next.getString("lease")});check.accept("isolated-recovery-unstarted-requires-exact-owner",RuntimeIsolatedSessions.retireUnstarted(db,freshAuthority)==0);}finally{db.endTransaction();}
            String unknown="owned-unknown-before-retirement";broker.contract.accept(unknown,"pending-install:"+install.generation,"e".repeat(64));broker.contract.start(unknown);broker.contract.markUnknown(unknown,"BROKER_RECOVERY");var before=broker.contract.query(unknown);
            var nextSlot=broker.processSlots().stream().filter(slot->slot.allocation.session.processGeneration.equals(next.getString("generation"))).findFirst().orElseThrow();
            // An old callback computed ATTACHED before losing authority, but its
            // SQL write arrives only after the new authority retired the phase.
            var stale=broker.contract.attach(nextSlot.allocation.bootstrapToken,22345,99303);
            var multiple=broker.isolated.reserve(install,process,component,"NAMED:multi");
            check.accept("isolated-recovery-retire-multiple-exact-unstarted-capabilities",RuntimeIsolatedSessions.retireUnstarted(db,freshAuthority)==2);
            check.accept("isolated-recovery-unstarted-closes-lease-without-inventing-pid",broker.store.isDead(next.getString("generation"))&&broker.services.get(next.getString("lease")).state.equals("RELEASED")&&scalar(db,"SELECT pid FROM sessions WHERE generation='"+next.getString("generation")+"'").equals("-1"));
            denied("isolated-recovery-old-inflight-attach-cannot-revive-retirement",()->broker.store.session(stale));
            var after=broker.contract.query(unknown);check.accept("isolated-recovery-unstarted-keeps-original-business-unknown",after.state==ExecutionContract.OperationState.UNKNOWN&&before.detail.equals(after.detail)&&before.updatedAtMs==after.updatedAtMs);
            broker.isolatedDead(next.getString("generation"),false);
            var committed=broker.isolated.reserve(install,process,component,"NAMED:committed");var committedSlot=broker.processSlots().stream().filter(slot->slot.allocation.session.processGeneration.equals(committed.getString("generation"))).findFirst().orElseThrow();
            broker.store.session(broker.contract.attach(committedSlot.allocation.bootstrapToken,22346,99304));broker.services.claimIsolated(committed.getString("lease"),committed.getString("generation"));
            check.accept("isolated-recovery-committed-attach-lost-reply-is-not-unstarted",RuntimeIsolatedSessions.retireUnstarted(db,freshAuthority)==0&&!broker.store.isDead(committed.getString("generation"))&&broker.services.get(committed.getString("lease")).state.equals("CLAIMED"));
            broker.isolatedDead(committed.getString("generation"),false);broker.services.retireIsolatedDead(committed.getString("lease"));
            var unexpected=broker.isolated.reserve(install,process,component,"NAMED:unexpected");String unexpectedGeneration=unexpected.getString("generation"),unexpectedLease=unexpected.getString("lease");
            var unexpectedSlot=broker.processSlots().stream().filter(slot->slot.allocation.session.processGeneration.equals(unexpectedGeneration)).findFirst().orElseThrow();
            broker.store.session(broker.contract.attach(unexpectedSlot.allocation.bootstrapToken,22347,99305));broker.services.claimIsolated(unexpectedLease,unexpectedGeneration);broker.services.lifecycle(unexpectedLease,unexpectedGeneration,unexpectedLease,"onCreate");broker.store.session(broker.contract.markRunning(unexpectedGeneration,22347,99305));
            broker.services.operation("unexpected-live-bind",broker.services.get(unexpectedLease),"unexpected-source","bind",UUID.randomUUID().toString());broker.services.complete("unexpected-live-bind","unexpected-source",true);
            var unexpectedDeath=unexpected.getBinder("isolatedAuthority").getClass().getDeclaredMethod("dead");unexpectedDeath.setAccessible(true);unexpectedDeath.invoke(unexpected.getBinder("isolatedAuthority"));
            check.accept("isolated-death-with-live-binding-preserves-unexpected-pause",broker.store.isDead(unexpectedGeneration)&&broker.store.recoveryPaused(unexpectedGeneration));
            check.accept("isolated-unexpected-death-does-not-invent-unbind-success",scalar(db,"SELECT state FROM service_calls WHERE operation='unexpected-live-bind'").equals("APPLIED"));
        }finally{
            for(var slot:broker.processSlots())if(slot.allocation.session.kind==ExecutionContract.ProcessKind.ISOLATED_SERVICE&&broker.contract.getSession(slot.allocation.session.processGeneration).state==ExecutionContract.ProcessState.ALLOCATED)broker.retireIsolatedBeforeBootstrap(slot.allocation.session.processGeneration);
            broker.store.close();
        }
    }
    private void contract()throws Exception{
        Map<String,ExecutionContract.Operation> rows=new HashMap<>();var contract=new ExecutionContract(new ExecutionContract.Store(){public ExecutionContract.Operation read(String id){return rows.get(id);}public void write(ExecutionContract.Operation value){rows.put(value.id,value);}public List<ExecutionContract.Operation> incomplete(){return List.of();}});contract.onRecover();
        var ordinary=contract.allocate("owned","dev.owned.isolated","install","dev.owned.isolated",10460);
        var a=contract.allocateIsolated("owned","dev.owned.isolated","install","dev.owned.isolated","instanceA");var b=contract.allocateIsolated("owned","dev.owned.isolated","install","dev.owned.isolated","instanceB");
        check.accept("isolated-contract-ordinary-and-two-isolated-instances-coexist",ordinary.session.kind==ExecutionContract.ProcessKind.ORDINARY&&a.session.kind==ExecutionContract.ProcessKind.ISOLATED_SERVICE&&!a.session.processGeneration.equals(b.session.processGeneration));
        denied("isolated-contract-same-instance-cannot-double-allocate",()->contract.allocateIsolated("owned","dev.owned.isolated","install","dev.owned.isolated","instanceA"));
        denied("isolated-contract-host-uid-cannot-claim",()->contract.attach(a.bootstrapToken,201,10460));
        var attached=contract.attach(a.bootstrapToken,201,99300);check.accept("isolated-contract-real-uid-becomes-immutable-identity",attached.uid==99300&&attached.expectedUid==99300&&attached.kind==ExecutionContract.ProcessKind.ISOLATED_SERVICE&&attached.instance.equals("instanceA"));
        denied("isolated-contract-bootstrap-is-once",()->contract.attach(a.bootstrapToken,202,99301));denied("isolated-contract-other-pid-cannot-authenticate",()->contract.authenticate(a.session.processGeneration,202,99300));denied("isolated-contract-other-uid-cannot-authenticate",()->contract.authenticate(a.session.processGeneration,201,99301));
        contract.markFailed(a.session.processGeneration,"owned-uncertain");denied("isolated-contract-failed-is-not-confirmed-death",()->contract.allocateIsolated("owned","dev.owned.isolated","install","dev.owned.isolated","instanceA"));
        contract.markDead(a.session.processGeneration);check.accept("isolated-contract-confirmed-dead-can-retire-failed",contract.getSession(a.session.processGeneration).state==ExecutionContract.ProcessState.DEAD);contract.allocateIsolated("owned","dev.owned.isolated","install","dev.owned.isolated","instanceA");
    }
    private void recovery()throws Exception{
        check.accept("isolated-kernel-self-is-not-absent",!RuntimeIsolatedSessions.absent(android.os.Process.myPid()));check.accept("isolated-kernel-unknown-pid-not-probed",!RuntimeIsolatedSessions.absent(-1)&&!RuntimeIsolatedSessions.absent(0));
        try(var db=SQLiteDatabase.create(null)){
            db.execSQL("CREATE TABLE sessions(generation TEXT PRIMARY KEY,pid INTEGER,realUid INTEGER,processKind TEXT,processInstance TEXT,authorityGeneration TEXT,state TEXT)");
            db.execSQL("INSERT INTO sessions VALUES('a',101,99300,'ISOLATED_SERVICE','lease-a','old','RUNNING'),('b',102,99301,'ISOLATED_SERVICE','lease-b','old','FAILED')");
            denied("isolated-recovery-live-or-eperm-remains-fenced",()->RuntimeIsolatedSessions.recover(db,pid->false));check.accept("isolated-recovery-failed-and-running-both-retained",scalar(db,"SELECT COUNT(*) FROM sessions WHERE state!='DEAD'").equals("2"));
            denied("isolated-recovery-partial-absence-not-partial-retirement",()->RuntimeIsolatedSessions.recover(db,pid->pid==101));check.accept("isolated-recovery-mixed-snapshot-kept",scalar(db,"SELECT COUNT(*) FROM sessions WHERE state='DEAD'").equals("0"));
            db.execSQL("UPDATE sessions SET pid=-1 WHERE generation='a'");denied("isolated-recovery-unknown-kernel-identity-stays-unknown",()->RuntimeIsolatedSessions.recover(db,pid->true));db.execSQL("UPDATE sessions SET pid=101 WHERE generation='a'");
            boolean[] changed={false};denied("isolated-recovery-rechecks-observed-identity",()->RuntimeIsolatedSessions.recover(db,pid->{if(!changed[0]){changed[0]=true;db.execSQL("UPDATE sessions SET realUid=99302 WHERE generation='b'");}return true;}));check.accept("isolated-recovery-race-rolls-back-all-death-writes",scalar(db,"SELECT COUNT(*) FROM sessions WHERE state='DEAD'").equals("0"));
            RuntimeIsolatedSessions.recover(db,pid->true);check.accept("isolated-recovery-exact-absence-retires-both-states",scalar(db,"SELECT COUNT(*) FROM sessions WHERE state='DEAD'").equals("2"));check.accept("isolated-recovery-keeps-original-generation-and-authority",scalar(db,"SELECT processInstance||':'||authorityGeneration FROM sessions WHERE generation='a'").equals("lease-a:old"));
        }
    }
}
