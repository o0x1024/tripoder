package dev.tripoder.runtime;

import android.content.Context;
import android.os.Bundle;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Actual scratch SQLite rollback; no product space, data or UNKNOWN is changed. */
final class SpaceCreationRecoveryChecks {
    private final BiConsumer<String,Boolean> check;
    SpaceCreationRecoveryChecks(BiConsumer<String,Boolean> check){this.check=check;}
    void run(Context owned)throws Exception{
        RuntimeBroker broker=new RuntimeBroker(owned);
        String space="owned-space-creation",scope="space/"+space,operation=UUID.randomUUID().toString();
        try{
            synchronized(broker){
                var db=broker.store.getWritableDatabase();
                broker.catalog.createSpace(space);Bundle create=new Bundle();create.putString("command","createSpace");create.putString("space",space);create.putString("operation",operation);
                try(var guard=RuntimeMutationGuard.enter(broker,create)){
                    broker.contract.accept(operation,RuntimeControllerOperationIdentity.CALLER,ApkStore.textDigest("controller-create-space-v1\n"+space));broker.contract.start(operation);
                    boolean duplicate=false;
                    try{db.beginTransaction();try{broker.catalog.createSpace(space);broker.contract.succeed(operation,space);db.setTransactionSuccessful();}finally{db.endTransaction();}}
                    catch(android.database.sqlite.SQLiteConstraintException expected){duplicate=true;broker.contract.markUnknown(operation,"SPACE_CREATION_PERSISTENCE_UNCONFIRMED");}
                    check.accept("space-create-model-actual-duplicate-insert-rolls-back",duplicate&&broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN);
                }
                check.accept("space-create-model-ended-sql-effect-permits-existing-namespace",RuntimeSpaceCreationProof.permits(db,operation,scope));
                check.accept("space-create-model-ended-sql-effect-permits-package-work",RuntimeSpaceCreationProof.permits(db,operation,scope+"/package/dev.owned.peer"));
                check.accept("space-create-model-does-not-authorize-another-namespace-or-global",!RuntimeSpaceCreationProof.permits(db,operation,"space/another")&&!RuntimeSpaceCreationProof.permits(db,operation,"*"));
                for(String[] mutation:new String[][]{
                    {"operations","digest","b".repeat(64),ApkStore.textDigest("controller-create-space-v1\n"+space)},
                    {"operations","caller","other-controller",RuntimeControllerOperationIdentity.CALLER},
                    {"operations","detail","BROKER_RECOVERY","SPACE_CREATION_PERSISTENCE_UNCONFIRMED"},
                    {"operations","state","RUNNING","UNKNOWN"},
                    {"operation_scopes","command","deleteSpace","createSpace"},
                    {"operation_scopes","dispatch","NOT_DISPATCHED","MAY_HAVE_DISPATCHED"}}){
                    String key=mutation[0].equals("operations")?"id":"operation";
                    db.execSQL("UPDATE "+mutation[0]+" SET "+mutation[1]+"=? WHERE "+key+"=?",new Object[]{mutation[2],operation});
                    try{check.accept("space-create-model-requires-exact-recorded-"+mutation[1],!RuntimeSpaceCreationProof.permits(db,operation,scope));}
                    finally{db.execSQL("UPDATE "+mutation[0]+" SET "+mutation[1]+"=? WHERE "+key+"=?",new Object[]{mutation[3],operation});}
                }
                String absent=UUID.randomUUID().toString();Bundle missing=new Bundle(create);missing.putString("space","owned-never-created");missing.putString("operation",absent);
                try(var guard=RuntimeMutationGuard.enter(broker,missing)){broker.contract.accept(absent,RuntimeControllerOperationIdentity.CALLER,ApkStore.textDigest("controller-create-space-v1\nowned-never-created"));broker.contract.start(absent);broker.contract.markUnknown(absent,"SPACE_CREATION_PERSISTENCE_UNCONFIRMED");}
                check.accept("space-create-model-missing-namespace-stays-fenced",!RuntimeSpaceCreationProof.permits(db,absent,"space/owned-never-created/package/dev.owned.peer"));
                Bundle next=new Bundle();next.putString("command","prepareApplicationLaunch");next.putString("space",space);next.putString("package","dev.owned.peer");String admission=UUID.randomUUID().toString();next.putString("operation",admission);
                try(var guard=RuntimeMutationGuard.enter(broker,next)){broker.contract.accept(admission,RuntimeControllerOperationIdentity.CALLER,"a".repeat(64));broker.contract.start(admission);broker.contract.succeed(admission,"MODEL_ONLY_NO_LAUNCH");}
                check.accept("space-create-model-real-admission-uses-ended-sql-proof",broker.contract.query(admission).state==ExecutionContract.OperationState.SUCCEEDED);
                check.accept("space-create-model-original-unknown-and-dispatch-retained",broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN&&"SPACE_CREATION_PERSISTENCE_UNCONFIRMED".equals(broker.contract.query(operation).detail));
                String unproven=UUID.randomUUID().toString();next.putString("operation",unproven);
                try(var guard=RuntimeMutationGuard.enter(broker,next)){broker.contract.accept(unproven,RuntimeControllerOperationIdentity.CALLER,"c".repeat(64));broker.contract.start(unproven);broker.contract.markUnknown(unproven,"UNPROVEN_MODEL_EFFECT");}
                boolean fenced=false;next.putString("operation",UUID.randomUUID().toString());
                try(var guard=RuntimeMutationGuard.enter(broker,next)){try{broker.contract.accept(next.getString("operation"),RuntimeControllerOperationIdentity.CALLER,"d".repeat(64));}catch(ExecutionContract.ContractException expected){fenced="RECOVERY_SCOPE_UNKNOWN".equals(expected.code);}}
                check.accept("space-create-model-does-not-bypass-other-overlapping-unknown",fenced&&broker.contract.query(unproven).state==ExecutionContract.OperationState.UNKNOWN);
            }
        }finally{broker.store.close();}
        try(RuntimeStore reopened=new RuntimeStore(owned)){
            check.accept("space-create-model-proof-survives-independent-database-reopen",RuntimeSpaceCreationProof.permits(reopened.getReadableDatabase(),operation,scope+"/package/dev.owned.peer"));
            try(var rows=reopened.getReadableDatabase().rawQuery("SELECT o.state,o.detail,s.dispatch,(SELECT COUNT(*) FROM spaces WHERE id=?) FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=?",new String[]{space,operation})){
                check.accept("space-create-model-reopened-original-receipt-and-namespace-retained",rows.moveToFirst()&&rows.getString(0).equals("UNKNOWN")&&rows.getString(1).equals("SPACE_CREATION_PERSISTENCE_UNCONFIRMED")&&rows.getString(2).equals("MAY_HAVE_DISPATCHED")&&rows.getInt(3)==1);
            }
        }
    }
}
