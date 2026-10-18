package dev.tripoder.runtime;

import android.content.Context;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.util.*;

/** The endpoint itself is generation-bound: an old Binder never becomes valid for a new owner. */
final class ControllerLease {
    interface Commands {Bundle execute(Bundle input,String lease)throws Exception;}
    private final Context host;private final Commands commands;
    private Session owner;
    ControllerLease(Context host,Commands commands){this.host=host;this.commands=commands;}
    synchronized void close(){if(owner!=null)owner.retire();}
    synchronized void require(String id){if(owner==null||!owner.live||!owner.id.equals(id)||!owner.life.isBinderAlive())throw new SecurityException("CONTROL_LEASE_REVOKED");}
    final IBinder entry=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){if(code!=ControlWire.CONNECT)throw new SecurityException("CONTROL_CONNECT_REQUIRED");ControllerTrust.require(host,pid,uid);}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if(!input.keySet().equals(Set.of("owner"))||input.getBinder("owner")==null)throw new IllegalArgumentException("CONTROL_OWNER_BINDER_REQUIRED");
            // The owner Binder is only a lifetime token: CONNECT already admitted this pid, and same-UID callers cannot be told apart by any proof.
            synchronized(ControllerLease.this){
                if(owner!=null&&owner.live)throw new IllegalStateException("CONTROL_OWNER_CONFLICT");
                Session session=new Session(input.getBinder("owner"));owner=session;
                try{session.life.linkToDeath(session.death,0);}catch(RemoteException error){session.died();throw error;}
                if(!session.life.isBinderAlive()){session.died();throw new DeadObjectException();}
                Bundle out=new Bundle();out.putBinder("session",session);out.putString("lease",session.id);out.putInt("engineUid",android.os.Process.myUid());out.putInt("enginePid",android.os.Process.myPid());out.putInt("protocol",1);return out;
            }
        }
    };
    private final class Session extends ControlWire.Endpoint {
        final IBinder life;final String id=UUID.randomUUID().toString();volatile boolean live=true;
        final IBinder.DeathRecipient death=this::died;
        Session(IBinder life){this.life=life;}
        void died(){synchronized(ControllerLease.this){live=false;if(owner==this)owner=null;}}
        void retire(){died();try{life.unlinkToDeath(death,0);}catch(NoSuchElementException alreadyDead){/* The death callback already removed this registration. */}}
        @Override protected void authorize(int code,int callingPid,int callingUid){
            // CONNECT admitted the main process; the session Binder was handed only to that owner and dies with it.
            synchronized(ControllerLease.this){if(code!=ControlWire.EXECUTE||!live||owner!=this||!life.isBinderAlive())throw new SecurityException("CONTROL_LEASE_REVOKED");}
        }
        @Override protected boolean acceptsDescriptors(int code){return code==ControlWire.EXECUTE;}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if("disconnect".equals(input.getString("command"))){if(input.size()!=1)throw new IllegalArgumentException("CONTROL_FIELDS_UNSUPPORTED");retire();return new Bundle();}
            // Admission precedes dispatch. Owner death revokes subsequent requests, not accepted work.
            authorize(code,pid,uid);
            try{return commands.execute(input,id);}
            catch(ExecutionContract.ContractException error){throw new IllegalStateException(error.code+": "+error.getMessage(),error);}
        }
    }
}
