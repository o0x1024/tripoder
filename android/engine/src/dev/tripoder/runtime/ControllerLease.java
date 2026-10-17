package dev.tripoder.runtime;

import android.content.Context;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.util.*;

/** The endpoint itself is generation-bound: an old Binder never becomes valid for a new owner. */
final class ControllerLease {
    interface Commands {Bundle execute(Bundle input,String lease,int uid)throws Exception;}
    private final Context host;private final Commands commands;
    private Session owner;
    ControllerLease(Context host,Commands commands){this.host=host;this.commands=commands;}
    synchronized void close(){if(owner!=null)owner.retire();}
    synchronized void require(String id,int uid){if(owner==null||!owner.live||!owner.id.equals(id)||owner.uid!=uid||!owner.life.isBinderAlive())throw new SecurityException("CONTROL_LEASE_REVOKED");}
    final IBinder entry=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){if(code!=ControlWire.CONNECT)throw new SecurityException("CONTROL_CONNECT_REQUIRED");ControllerTrust.require(host,pid,uid);}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if(!input.keySet().equals(Set.of("owner"))||input.getBinder("owner")==null)throw new IllegalArgumentException("CONTROL_OWNER_BINDER_REQUIRED");
            synchronized(ControllerLease.this){if(owner!=null&&owner.live)throw new IllegalStateException("CONTROL_OWNER_CONFLICT");}
            verifyOwner(input.getBinder("owner"),pid,uid);
            synchronized(ControllerLease.this){
                if(owner!=null&&owner.live)throw new IllegalStateException("CONTROL_OWNER_CONFLICT");
                Session session=new Session(pid,uid,input.getBinder("owner"));owner=session;
                try{session.life.linkToDeath(session.death,0);}catch(RemoteException error){session.died();throw error;}
                if(!session.life.isBinderAlive()){session.died();throw new DeadObjectException();}
                Bundle out=new Bundle();out.putBinder("session",session);out.putString("lease",session.id);out.putInt("engineUid",android.os.Process.myUid());out.putInt("enginePid",android.os.Process.myPid());out.putInt("protocol",1);return out;
            }
        }
    };
    private static void verifyOwner(IBinder owner,int pid,int uid)throws RemoteException{
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String nonce=HexFormat.of().formatHex(bytes);
        java.util.concurrent.atomic.AtomicBoolean proved=new java.util.concurrent.atomic.AtomicBoolean();
        IBinder verifier=new Binder(){@Override protected boolean onTransact(int code,Parcel input,Parcel output,int flags){
            if(code!=FIRST_CALL_TRANSACTION||Binder.getCallingPid()!=pid||Binder.getCallingUid()!=uid)throw new SecurityException("CONTROL_OWNER_PROCESS_MISMATCH");
            input.enforceInterface(ControlWire.PROOF_DESCRIPTOR);if(input.dataAvail()>1024||input.hasFileDescriptors())throw new SecurityException("CONTROL_OWNER_PROOF_INVALID");String value=input.readString();input.enforceNoDataAvail();
            if(!nonce.equals(value)||!proved.compareAndSet(false,true))throw new SecurityException("CONTROL_OWNER_PROOF_REPLAY");output.writeNoException();return true;
        }};
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(ControlWire.OWNER_DESCRIPTOR);data.writeStrongBinder(verifier);data.writeString(nonce);
            if(!owner.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))throw new SecurityException("CONTROL_OWNER_PROTOCOL_REJECTED");reply.readException();if(!proved.get())throw new SecurityException("CONTROL_OWNER_PROOF_REQUIRED");
        }finally{data.recycle();reply.recycle();}
    }
    private final class Session extends ControlWire.Endpoint {
        final int pid,uid;final IBinder life;final String id=UUID.randomUUID().toString();volatile boolean live=true;
        final IBinder.DeathRecipient death=this::died;
        Session(int pid,int uid,IBinder life){this.pid=pid;this.uid=uid;this.life=life;}
        void died(){synchronized(ControllerLease.this){live=false;if(owner==this)owner=null;}}
        void retire(){died();try{life.unlinkToDeath(death,0);}catch(NoSuchElementException alreadyDead){/* The death callback already removed this registration. */}}
        @Override protected void authorize(int code,int callingPid,int callingUid){
            // CONNECT already admitted this pid/uid; a session only answers its own owner.
            synchronized(ControllerLease.this){if(code!=ControlWire.EXECUTE||!live||owner!=this||callingPid!=pid||callingUid!=uid||!life.isBinderAlive())throw new SecurityException("CONTROL_LEASE_REVOKED");}
        }
        @Override protected boolean acceptsDescriptors(int code){return code==ControlWire.EXECUTE;}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if("disconnect".equals(input.getString("command"))){if(input.size()!=1)throw new IllegalArgumentException("CONTROL_FIELDS_UNSUPPORTED");retire();return new Bundle();}
            // Admission precedes dispatch. Owner death revokes subsequent requests, not accepted work.
            authorize(code,pid,uid);
            try{return commands.execute(input,id,uid);}
            catch(ExecutionContract.ContractException error){throw new IllegalStateException(error.code+": "+error.getMessage(),error);}
        }
    }
}
