package dev.tripoder.runtime;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;

/** Scratch SQLite ownership/replay models; no physical Activity or process is started. */
final class ContextStartReceiptChecks {
    private final BiConsumer<String,Boolean> check;
    ContextStartReceiptChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private void denied(String name,Runnable action,String code){try{action.run();throw new AssertionError(name);}catch(SecurityException failure){check.accept(name,code.equals(failure.getMessage()));}}
    private record Prepared(String ticket,String delivery,String operation,Bundle receipt){}
    private static long count(SQLiteDatabase db,String table){try(var rows=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){rows.moveToFirst();return rows.getLong(0);}}
    private static RuntimeBroker.Slot slot(RuntimeBroker broker,ApkStore.BundleInstall install,String process,int index){
        var allocation=broker.contract.allocate(install.space,install.info.packageName,install.generation,process,android.os.Process.myUid());
        broker.store.reserve(allocation.session,RuntimeSlot.activity(index).getName());return new RuntimeBroker.Slot(install,allocation,RuntimeSlot.activity(index).getName(),RuntimeMode.THICK,null);
    }
    private static Prepared prepare(RuntimeBroker broker,RuntimeBroker.Slot target,RuntimeBroker.Slot source,String owner,boolean context){
        String operation=UUID.randomUUID().toString();broker.contract.accept(operation,owner,"a".repeat(64));broker.contract.start(operation);
        String component=target.install.info.packageName+".ReceiptTarget";
        String ticket=context?broker.activities.prepareContext(target,component,operation,source.install.info.packageName,"b".repeat(64),Intent.FLAG_ACTIVITY_NEW_TASK):broker.activities.prepare(target,component,null,operation,source.install.info.packageName,"b".repeat(64),null,Intent.FLAG_ACTIVITY_NEW_TASK);
        String delivery=broker.activities.launches.forTicket(ticket);Bundle receipt=new Bundle();receipt.putString("delivery",delivery);receipt.putString("candidate",ticket);receipt.putString("stub",broker.activities.get(ticket).stub);receipt.putInt("startResult",0);return new Prepared(ticket,delivery,operation,receipt);
    }
    void run(Context owned,Context assets)throws Exception{
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            File stage=new File(owned.getFilesDir(),"apk");if(!stage.mkdir())throw new IllegalStateException("CONTEXT_RECEIPT_STAGE");try(var input=assets.getAssets().open("aliases.apk")){Files.copy(input,new File(stage,"base.apk").toPath());}
            ArtifactSet artifacts=ArtifactSet.materialize(stage);var info=owned.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),android.content.pm.PackageManager.GET_ACTIVITIES);
            var install=new ApkStore.BundleInstall("owned-context",UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(owned.getFilesDir(),"data"),artifacts);var db=broker.store.getWritableDatabase();
            db.execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{install.space});
            db.execSQL("INSERT INTO installs VALUES(?,?,?,?,?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{install.space,info.packageName,install.generation,artifacts.base.toString(),artifacts.parts.get(0).sha256,artifacts.descriptorSha,install.data.getName()});
            RuntimeBroker.Slot source=slot(broker,install,info.packageName,0),target=slot(broker,install,info.packageName+":contexttarget",1);
            var receipts=new ActivityStartReceipts(broker.store,owned,broker.activities,broker);
            synchronized(broker){
                Prepared good=prepare(broker,target,source,source.allocation.session.identity.key(),true);var launch=broker.activities.launches.get(good.delivery);
                check.accept("context-launch-records-explicit-kind-without-activity-capability",launch.callerKind.equals("CONTEXT")&&launch.sourceTicket==null&&launch.sourceInstance==null&&launch.callerComponent==null&&!launch.expectsResult);
                receipts.record(good.receipt,source);
                check.accept("context-receipt-authenticates-source-distinct-from-target-process",!source.allocation.session.processGeneration.equals(target.allocation.session.processGeneration)&&broker.activities.launches.get(good.delivery).startResult==0);
                check.accept("start-success-is-not-a-create-callback-ack",broker.contract.query(good.operation).state==ExecutionContract.OperationState.RUNNING&&broker.activities.launches.get(good.delivery).state.equals("PREPARED"));
                denied("context-start-result-cannot-replay",()->receipts.record(good.receipt,source),"ACTIVITY_START_RESULT_REPLAY");
                Prepared wrongStub=prepare(broker,target,source,source.allocation.session.identity.key(),true);wrongStub.receipt.putString("stub",RuntimeSlot.activity(7).getName());
                denied("context-result-rejects-another-physical-stub",()->receipts.record(wrongStub.receipt,source),"ACTIVITY_START_RESULT_TARGET_MISMATCH");
                Prepared other=prepare(broker,target,source,source.allocation.session.identity.key(),true);wrongStub.receipt.putString("stub",broker.activities.get(wrongStub.ticket).stub);wrongStub.receipt.putString("candidate",other.ticket);
                denied("context-result-rejects-another-candidate-ticket",()->receipts.record(wrongStub.receipt,source),"ACTIVITY_START_RESULT_TARGET_MISMATCH");
                Prepared wrongGeneration=prepare(broker,target,source,source.allocation.session.identity.key().replace(source.allocation.session.processGeneration,"f".repeat(64)),true);
                denied("context-result-cannot-borrow-same-package-journal-from-another-generation",()->receipts.record(wrongGeneration.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_OWNER_MISMATCH");
                Prepared controller=prepare(broker,target,source,source.allocation.session.identity.key(),false);
                denied("old-controller-kind-is-not-guessed-to-be-context",()->receipts.record(controller.receipt,source),"ACTIVITY_START_RESULT_SOURCE_KIND_UNSUPPORTED");
                db.execSQL("UPDATE activity_launches SET callerKind='UNRECORDED' WHERE id=?",new Object[]{controller.delivery});
                denied("unrecorded-kind-cannot-submit-worker-start-result",()->receipts.record(controller.receipt,source),"ACTIVITY_START_RESULT_SOURCE_KIND_UNSUPPORTED");
                for(String column:List.of("sourceTicket","sourceInstance","callerComponent")){
                    Prepared malformed=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_launches SET "+column+"='unowned' WHERE id=?",new Object[]{malformed.delivery});
                    denied("context-cannot-borrow-activity-field-"+column,()->receipts.record(malformed.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                }
                Prepared result=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_launches SET expectsResult=1 WHERE id=?",new Object[]{result.delivery});
                denied("context-cannot-request-an-activity-result-capability",()->receipts.record(result.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                Prepared flags=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_launches SET requestFlags=0 WHERE id=?",new Object[]{flags.delivery});
                denied("context-receipt-requires-recorded-new-task-flag",()->receipts.record(flags.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                Prepared pkg=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_launches SET callerPackage='dev.foreign' WHERE id=?",new Object[]{pkg.delivery});
                denied("context-receipt-caller-package-must-match-authenticated-process",()->receipts.record(pkg.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                Prepared scope=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_tickets SET space='foreign-space' WHERE ticket=?",new Object[]{scope.ticket});
                denied("context-receipt-cannot-borrow-a-target-from-another-space",()->receipts.record(scope.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                Prepared noDigest=prepare(broker,target,source,source.allocation.session.identity.key(),true);db.execSQL("UPDATE activity_launches SET payloadDigest=NULL WHERE id=?",new Object[]{noDigest.delivery});
                denied("context-start-result-requires-recorded-payload-identity",()->receipts.record(noDigest.receipt,source),"ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
                long tickets=count(db,"activity_tickets"),launches=count(db,"activity_launches");String failed=UUID.randomUUID().toString();broker.contract.accept(failed,source.allocation.session.identity.key(),"a".repeat(64));broker.contract.start(failed);
                denied("context-preparation-without-new-task-is-rejected",()->broker.activities.prepareContext(target,info.packageName+".ReceiptTarget",failed,info.packageName,"b".repeat(64),0),"ACTIVITY_CONTEXT_SOURCE_INVALID");
                check.accept("invalid-context-preparation-rolls-back-ticket-and-launch-together",count(db,"activity_tickets")==tickets&&count(db,"activity_launches")==launches);
                String sourceTicket=broker.activities.prepare(source,info.packageName+".Main",null,null,info.packageName,"b".repeat(64),null,0),instance=UUID.randomUUID().toString();
                db.execSQL("UPDATE activity_tickets SET state='LIVE',instance=?,taskId=1 WHERE ticket=?",new Object[]{instance,sourceTicket});var caller=broker.activities.get(sourceTicket);
                String activityOp=UUID.randomUUID().toString();broker.contract.accept(activityOp,source.allocation.session.identity.key(),"a".repeat(64));broker.contract.start(activityOp);
                String candidate=broker.activities.prepare(target,info.packageName+".ReceiptTarget",null,activityOp,info.packageName,"b".repeat(64),caller,Intent.FLAG_ACTIVITY_NEW_TASK);String delivery=broker.activities.launches.forTicket(candidate);
                Bundle activityReceipt=new Bundle();activityReceipt.putString("candidate",candidate);activityReceipt.putString("delivery",delivery);activityReceipt.putString("stub",broker.activities.get(candidate).stub);activityReceipt.putInt("startResult",0);
                db.execSQL("UPDATE activity_tickets SET state='FINISHED',destroyedConfirmed=1 WHERE ticket=?",new Object[]{sourceTicket});receipts.record(activityReceipt,source);
                check.accept("activity-source-keeps-immutable-instance-proof-after-destruction",broker.activities.launches.get(delivery).startResult==0&&broker.activities.launches.get(delivery).callerKind.equals("ACTIVITY"));
                check.accept("rejected-context-receipts-do-not-invent-callback-success",broker.contract.query(wrongGeneration.operation).state==ExecutionContract.OperationState.RUNNING&&broker.activities.launches.get(wrongGeneration.delivery).startResult==null);
                Prepared closable=prepare(broker,target,source,source.allocation.session.identity.key(),true);
                ActivityContextSource.requireRecorded(broker,broker.activities.launches.get(closable.delivery),broker.activities.get(closable.ticket));
                check.accept("context-entry-fence-uses-complete-recorded-session-identity",true);
                denied("context-entry-fence-rejects-unknown-source-generation",()->ActivityContextSource.requireRecorded(broker,broker.activities.launches.get(wrongGeneration.delivery),broker.activities.get(wrongGeneration.ticket)),"ACTIVITY_CONTEXT_SOURCE_JOURNAL_MISMATCH");
                // No real process exists in this scratch model. Simulate its
                // positive DEAD reservation fact; this is not device death evidence.
                db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{target.allocation.session.processGeneration});
                broker.contract.markUnknown(closable.operation,"OWNED_CONTEXT_ACK_LOST");db.execSQL("UPDATE activity_launches SET state='UNKNOWN' WHERE id=?",new Object[]{closable.delivery});
                String closure=UUID.randomUUID().toString();db.beginTransaction();try{ActivityEntryFences.closeRoute(broker,closable.ticket,closable.operation,closure);db.setTransactionSuccessful();}finally{db.endTransaction();}
                check.accept("context-route-can-fence-its-exact-unattached-entry",ActivityEntryFences.closed(broker.store,closable.ticket));
                check.accept("context-entry-fence-retains-original-unknown-and-resource-pin",broker.contract.query(closable.operation).state==ExecutionContract.OperationState.UNKNOWN&&broker.contract.query(closable.operation).detail.equals("OWNED_CONTEXT_ACK_LOST")&&broker.activities.get(closable.ticket).state.equals("PREPARED"));
            }
        }finally{broker.store.close();}
    }
}
