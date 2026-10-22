package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;

/** Owned QA fixture. build-workbench refuses to package this in the production app id. */
public final class RecoveryFaultService extends Service {
    private final IBinder entry=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){ControllerTrust.require(RecoveryFaultService.this,pid,uid);}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            RuntimeBroker broker=RuntimeAuthorityOwner.get(RecoveryFaultService.this);
            String command=input.getString("command");Bundle out=new Bundle();
            if(command.equals("identity")){out.putInt("pid",android.os.Process.myPid());out.putString("authority",broker.store.authorityGeneration);return out;}
            if(command.equals("effect")){
                String operation=input.getString("operation");Bundle scoped=new Bundle();scoped.putString("command","owned-recovery-effect");scoped.putString("space",input.getString("space","recovery-qa"));
                try(RuntimeMutationGuard scope=RuntimeMutationGuard.enter(broker,scoped)){
                    try{synchronized(broker){if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,ApkStore.textDigest("owned-recovery-effect:"+scoped.getString("space"))).created)throw new IllegalStateException("ORIGINAL_OPERATION_QUERY_REQUIRED");broker.contract.start(operation);}}
                    catch(ExecutionContract.ContractException rejected){throw new IllegalStateException(rejected.code+":"+rejected.getMessage(),rejected);}
                    File effect=new File(getExternalFilesDir(null),"recovery-effect-"+operation);int count=effect.exists()?Integer.parseInt(java.nio.file.Files.readString(effect.toPath())):0;
                    try(var stream=new FileOutputStream(effect)){stream.write(Integer.toString(count+1).getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.getFD().sync();}
                    if(input.getBoolean("crash")){android.system.Os.kill(android.os.Process.myPid(),android.system.OsConstants.SIGABRT);new java.util.concurrent.CountDownLatch(1).await();throw new IllegalStateException("UNREACHABLE");}
                    synchronized(broker){broker.contract.succeed(operation,"EFFECT_APPLIED");}out.putInt("count",count+1);return out;
                }
            }
            throw new IllegalArgumentException("QA_COMMAND_UNSUPPORTED");
        }
    };
    @Override public IBinder onBind(Intent intent){return entry;}
}
