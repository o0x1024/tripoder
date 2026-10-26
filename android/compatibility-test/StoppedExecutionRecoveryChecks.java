package dev.tripoder.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.tripoder.control.ControlWire;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Real SQLite/guard admission models; no package, process or platform job is changed. */
final class StoppedExecutionRecoveryChecks {
    private final BiConsumer<String,Boolean> check;
    private static final String SPACE="owned-stop-proof",PKG="dev.tripoder.owned.stopproof",INSTALL="owned-stop-install";
    private static final String OWNER="space/"+SPACE+"/package/"+PKG,OLD="old-outer-stop",LATER="later-owner-stop";
    StoppedExecutionRecoveryChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private static String scalar(SQLiteDatabase db,String sql,String...args){try(var c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    private void denied(SQLiteDatabase db,String name,String sql,Object...values){
        db.beginTransaction();try{db.execSQL(sql,values);check.accept(name,!RuntimeStoppedEffectProof.permits(db,OLD,OWNER,false));}finally{db.endTransaction();}
    }
    void run(Context context)throws Exception{
        try(var minimal=SQLiteDatabase.create(null)){
            minimal.execSQL("CREATE TABLE operation_scopes(operation TEXT,scope TEXT,command TEXT)");
            minimal.execSQL("INSERT INTO operation_scopes VALUES('other','*','legacy-or-internal')");
            check.accept("stop-proof-dispatches-by-role-before-stop-specific-columns",!RuntimeStoppedEffectProof.permits(minimal,"other","*",false));
        }
        RuntimeBroker broker=new RuntimeBroker(context);
        try{
            var db=broker.store.getWritableDatabase();
            db.execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{SPACE});
            db.execSQL("INSERT INTO installs VALUES(?,?,?,'owned.apk',?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{SPACE,PKG,INSTALL,"a".repeat(64),"b".repeat(64),"owned-stop-data"});
            db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,closeRequested,authorityGeneration) VALUES('old-closed',?,?,?,0,'DEAD',?,1,'old-authority')",new Object[]{SPACE,PKG,INSTALL,PKG});
            String digest=RuntimeControllerOperationIdentity.stopDigest(SPACE,PKG,INSTALL);
            try(var guard=RuntimeMutationGuard.enter(broker,ControlWire.request("stopApplication","operation",OLD,"space",SPACE,"package",PKG))){broker.contract.accept(OLD,RuntimeControllerOperationIdentity.CALLER,digest);broker.contract.start(OLD);broker.contract.markUnknown(OLD,"BROKER_RECOVERY");}
            try(var guard=RuntimeMutationGuard.enter(broker,ControlWire.request("stopApplication","operation",LATER,"space",SPACE,"package",PKG))){broker.contract.accept(LATER,RuntimeControllerOperationIdentity.CALLER,digest);broker.contract.start(LATER);broker.contract.succeed(LATER,"OWNED_RESOURCE_CLOSURE");}
            check.accept("recovered-outer-stop-with-later-owner-closure-is-inert",RuntimeStoppedEffectProof.permits(db,OLD,OWNER,false));
            db.beginTransaction();try{db.execSQL("UPDATE operations SET detail='STOP_UNCONFIRMED:PROCESS_STOP_UNCONFIRMED' WHERE id=?",new Object[]{OLD});check.accept("canonical-stop-deadline-phase-plus-death-and-closure-is-inert",RuntimeStoppedEffectProof.permits(db,OLD,OWNER,false));}finally{db.endTransaction();}
            db.beginTransaction();try{db.execSQL("UPDATE operations SET detail='STOP_UNCONFIRMED:ISOLATED_STOP_BEFORE_ATTACH_UNKNOWN' WHERE id=?",new Object[]{OLD});check.accept("unattached-isolated-stop-phase-plus-death-and-closure-is-inert",RuntimeStoppedEffectProof.permits(db,OLD,OWNER,false));
                db.execSQL("UPDATE sessions SET state='ATTACHED' WHERE generation='old-closed'");check.accept("unattached-isolated-stop-phase-never-admits-over-a-live-session",!RuntimeStoppedEffectProof.permits(db,OLD,OWNER,false));}finally{db.endTransaction();}
            denied(db,"outer-stop-rejects-foreign-caller","UPDATE operations SET caller='foreign' WHERE id=?",OLD);
            denied(db,"child-job-stop-command-is-not-an-outer-stop","UPDATE operations SET caller=? WHERE id=?","job-install:"+INSTALL,OLD);
            denied(db,"outer-stop-rejects-different-command","UPDATE operation_scopes SET command='prepareApplicationLaunch' WHERE operation=?",OLD);
            denied(db,"outer-stop-rejects-wrong-install-digest","UPDATE operations SET digest=? WHERE id=?","c".repeat(64),OLD);
            denied(db,"outer-stop-rejects-another-scope","UPDATE operation_scopes SET scope='space/foreign/package/dev.other' WHERE operation=?",OLD);
            denied(db,"outer-stop-never-retires-a-running-handler","UPDATE operations SET state='RUNNING' WHERE id=?",OLD);
            denied(db,"outer-stop-rejects-other-unknown-phase","UPDATE operations SET detail='STOP_UNCONFIRMED:OTHER' WHERE id=?",OLD);
            denied(db,"outer-stop-requires-committed-installation","UPDATE installs SET state='PREPARED' WHERE space=? AND package=?",SPACE,PKG);
            denied(db,"new-install-cannot-borrow-old-stop-digest","UPDATE installs SET generation='replacement-install' WHERE space=? AND package=?",SPACE,PKG);
            denied(db,"outer-stop-rejects-live-old-generation","UPDATE sessions SET state='RUNNING' WHERE generation='old-closed'");
            denied(db,"outer-stop-does-not-treat-failed-as-dead","UPDATE sessions SET state='FAILED' WHERE generation='old-closed'");
            denied(db,"outer-stop-requires-a-persisted-close-target","UPDATE sessions SET closeRequested=0 WHERE generation='old-closed'");
            denied(db,"outer-stop-does-not-infer-death-from-empty-history","DELETE FROM sessions");
            denied(db,"outer-stop-requires-later-successful-owner-stop","UPDATE operations SET state='UNKNOWN' WHERE id=?",LATER);
            denied(db,"later-stop-must-have-trusted-caller","UPDATE operations SET caller='job-install:owned-stop-install' WHERE id=?",LATER);
            denied(db,"later-stop-must-have-same-install-digest","UPDATE operations SET digest=? WHERE id=?","d".repeat(64),LATER);
            denied(db,"earlier-stop-is-not-fresh-owner-closure","UPDATE operations SET created=0 WHERE id=?",LATER);
            check.accept("global-scope-cannot-borrow-stop-proof",!RuntimeStoppedEffectProof.permits(db,OLD,"*",false));
            check.accept("no-continuation-without-active-fresh-start-ack",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,OLD,"NOT_DISPATCHED",OWNER));
            db.beginTransaction();try{
                String child="uncertain-child-job";db.execSQL("INSERT INTO operations(id,caller,digest,state,result,detail,created,updated) VALUES(?,?,?,'UNKNOWN','','BROKER_RECOVERY',1,1)",new Object[]{child,"job-install:"+INSTALL,digest});
                db.execSQL("INSERT INTO operation_scopes VALUES(?,?,?,'MAY_HAVE_DISPATCHED')",new Object[]{child,OWNER,"stopApplication"});
                boolean blocked=false;
                try(var guard=RuntimeMutationGuard.enter(broker,ControlWire.request("prepareApplicationLaunch","operation","blocked-fresh","space",SPACE,"package",PKG))){
                    try{RuntimeMutationGuard.accepting(db,new ExecutionContract.Operation("blocked-fresh",RuntimeControllerOperationIdentity.CALLER,"f".repeat(64),ExecutionContract.OperationState.ACCEPTED,"","",1,1));}
                    catch(ExecutionContract.AdmissionRejected expected){blocked=String.valueOf(expected.getMessage()).contains(child);}
                }
                check.accept("outer-proof-does-not-bypass-independent-child-unknown",blocked);
                check.accept("blocked-start-does-not-publish-partial-retirement-ack",!scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation=?",OLD).startsWith(RuntimeEffectRecoveryProof.ACK));
            }finally{db.endTransaction();}
            var before=broker.contract.query(OLD);String fresh="fresh-after-outer-stop";
            try(var guard=RuntimeMutationGuard.enter(broker,ControlWire.request("prepareApplicationLaunch","operation",fresh,"space",SPACE,"package",PKG))){broker.contract.accept(fresh,RuntimeControllerOperationIdentity.CALLER,"f".repeat(64));broker.contract.start(fresh);}
            String ack=scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation=?",OLD);
            check.accept("fresh-owner-admission-acknowledges-only-exact-old-stop",ack.equals(RuntimeEffectRecoveryProof.ACK+fresh));
            db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,closeRequested,authorityGeneration) VALUES('fresh-live',?,?,?,0,'RUNNING',?,0,'new-authority')",new Object[]{SPACE,PKG,INSTALL,PKG});
            check.accept("fresh-live-generation-does-not-revive-old-stop",RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,OLD,ack,OWNER));
            db.beginTransaction();try{db.execSQL("UPDATE sessions SET state='RUNNING' WHERE generation='old-closed'");check.accept("old-close-target-retirement-is-rechecked-after-ack",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,OLD,ack,OWNER));}finally{db.endTransaction();}
            db.beginTransaction();try{db.execSQL("UPDATE sessions SET closeRequested=1 WHERE generation='fresh-live'");check.accept("new-closing-generation-cannot-borrow-old-death-proof",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,OLD,ack,OWNER));}finally{db.endTransaction();}
            var after=broker.contract.query(OLD);
            check.accept("outer-stop-business-unknown-and-evidence-remain-unchanged",after.state==ExecutionContract.OperationState.UNKNOWN&&Objects.equals(before.result,after.result)&&Objects.equals(before.detail,after.detail)&&Objects.equals(before.evidence,after.evidence)&&before.updatedAtMs==after.updatedAtMs);
            db.execSQL("UPDATE operations SET state='UNKNOWN' WHERE id=?",new Object[]{fresh});
            check.accept("uncertain-fresh-start-does-not-authorize-continuation",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,OLD,ack,OWNER));
        }finally{broker.store.close();}
    }
}
