package dev.tripoder.runtime;

import java.io.*;
import java.util.*;

/** Durable, single-writer recovery decisions. Persist BEFORE binding; never stores business requests. */
public final class EngineRecoveryPolicy {
    public interface Store { byte[] read() throws Exception; void write(byte[] value) throws Exception; }
    public static final long START_MS=30000, STABLE_MS=300000, WINDOW_MS=1800000;
    private static final long[] DELAYS={2000,10000,30000};
    final Store store;
    public String state="STOPPED",reason="",epoch="",boot="";
    public int automaticAttempts,totalAttempts;
    public long nextAt,startedAt,readyAt;
    private boolean manual;
    private final List<Long> history=new ArrayList<>();
    public EngineRecoveryPolicy(Store store)throws Exception{
        this.store=store;byte[] bytes=store.read();if(bytes==null)return;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=1)throw new IOException("RECOVERY_SCHEMA");
            state=in.readUTF();reason=in.readUTF();epoch=in.readUTF();boot=in.readUTF();
            automaticAttempts=in.readInt();totalAttempts=in.readInt();nextAt=in.readLong();startedAt=in.readLong();readyAt=in.readLong();manual=in.readBoolean();
            int size=in.readInt();if(size<0||size>1000)throw new IOException("RECOVERY_HISTORY");
            for(int i=0;i<size;i++)history.add(in.readLong());
            if(in.available()!=0||!Set.of("STOPPED","STARTING","READY","BACKOFF","OPEN","BLOCKED").contains(state)||automaticAttempts<0||automaticAttempts>3||totalAttempts<0)throw new IOException("RECOVERY_RECORD");
        }
    }
    private void save()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(1);out.writeUTF(state);out.writeUTF(reason);out.writeUTF(epoch);out.writeUTF(boot);
            out.writeInt(automaticAttempts);out.writeInt(totalAttempts);out.writeLong(nextAt);out.writeLong(startedAt);out.writeLong(readyAt);out.writeBoolean(manual);
            out.writeInt(history.size());for(long value:history)out.writeLong(value);
        }
        try{store.write(bytes.toByteArray());}catch(Exception failure){state="BLOCKED";reason="RECOVERY_STORAGE_UNAVAILABLE";throw failure;}
    }
    /** A new supervisor cannot prove ownership of the previous process. No blind rebind. */
    public synchronized void interrupted()throws Exception{
        if(Set.of("READY","STARTING","BACKOFF").contains(state))block("SUPERVISOR_INTERRUPTED");
    }
    public synchronized boolean activate(long now,String currentBoot,boolean retry)throws Exception{
        if(state.equals("READY")||state.equals("STARTING")||state.equals("BACKOFF"))return false;
        if(!state.equals("STOPPED")&&!retry)return false;
        boolean first=totalAttempts==0;
        if(!boot.isEmpty()&&!boot.equals(currentBoot)&&!first&&!retry){block("BOOT_CHANGED_EXPLICIT_RETRY_REQUIRED");return false;}
        if(!boot.equals(currentBoot)){boot=currentBoot;history.clear();}
        // Explicit retry grants ONE attempt, without reopening the automatic circuit.
        return begin(now,!first);
    }
    private boolean begin(long now,boolean oneShot)throws Exception{
        state="STARTING";reason="";epoch=UUID.randomUUID().toString();startedAt=now;readyAt=0;nextAt=0;manual=oneShot;totalAttempts++;save();return true;
    }
    public synchronized boolean retryDue(long now,String currentBoot)throws Exception{
        if(!state.equals("BACKOFF")||now<nextAt)return false;
        if(!boot.equals(currentBoot)||now<startedAt){block("RECOVERY_CLOCK_CHANGED");return false;}
        history.removeIf(value->now>=value&&now-value>=WINDOW_MS);
        if(automaticAttempts>=DELAYS.length||history.size()>=5){open("RECOVERY_BUDGET_EXHAUSTED");return false;}
        automaticAttempts++;history.add(now);return begin(now,false);
    }
    public synchronized void ready(String attempt,long now)throws Exception{
        if(!state.equals("STARTING")||!epoch.equals(attempt))throw new IllegalStateException("RECOVERY_ATTEMPT_STALE");
        state="READY";readyAt=now;save();
    }
    public synchronized void failed(String attempt,long now,String detail,boolean permanent)throws Exception{
        if(!epoch.equals(attempt)||!Set.of("STARTING","READY").contains(state))return;
        if(permanent){block(detail);return;}
        reason=detail;
        if(manual||automaticAttempts>=DELAYS.length){open(detail);return;}
        state="BACKOFF";nextAt=now+DELAYS[automaticAttempts]+(epoch.hashCode()&255);save();
    }
    public synchronized void stable(String attempt,long now)throws Exception{
        if(state.equals("READY")&&epoch.equals(attempt)&&now>=readyAt&&now-readyAt>=STABLE_MS&&(automaticAttempts!=0||manual)){automaticAttempts=0;manual=false;save();}
    }
    public synchronized void stop()throws Exception{state="STOPPED";reason="USER_STOPPED";nextAt=0;save();}
    public synchronized void block(String detail)throws Exception{state="BLOCKED";reason=detail;nextAt=0;save();}
    private void open(String detail)throws Exception{state="OPEN";reason=detail;nextAt=0;save();}
}
