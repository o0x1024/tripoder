package dev.tripoder.runtime;

import java.nio.file.*;
import java.util.*;

public final class EngineRecoveryPolicyTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static final class Disk implements EngineRecoveryPolicy.Store {
        final Path path;boolean reject;
        Disk(Path path){this.path=path;}
        public byte[] read()throws Exception{return Files.exists(path)?Files.readAllBytes(path):null;}
        public void write(byte[] data)throws Exception{if(reject)throw new java.io.IOException("disk full");Files.write(path,data);}
    }
    public static void main(String[] args)throws Exception{
        Path path=Path.of(args[0]);Disk store=new Disk(path);EngineRecoveryPolicy p=new EngineRecoveryPolicy(store);long now=100;
        check(p.activate(now,"boot-a",false),"first launch");String initial=p.epoch;
        check(!p.activate(now,"boot-a",false)&&p.totalAttempts==1,"parallel activation deduplicates");
        p.ready(initial,++now);p.failed(initial,++now,"CRASH",false);
        check(p.state.equals("BACKOFF"),"death schedules backoff");
        for(int i=1;i<=3;i++){
            check(!p.retryDue(p.nextAt-1,"boot-a"),"no early retry");now=p.nextAt;
            check(p.retryDue(now,"boot-a"),"bounded automatic attempt");
            EngineRecoveryPolicy disk=new EngineRecoveryPolicy(new Disk(path));
            check(disk.totalAttempts==i+1&&disk.automaticAttempts==i&&disk.state.equals("STARTING"),"permit persisted before binding");
            String epoch=p.epoch;p.ready(epoch,++now);p.failed(epoch,++now,"CRASH",false);
            p.failed(epoch,now,"duplicate death",false);check(p.automaticAttempts==i,"duplicate callbacks do not spend budget");
        }
        check(p.state.equals("OPEN")&&p.totalAttempts==4,"three automatic attempts then circuit opens");
        p=new EngineRecoveryPolicy(new Disk(path));p.interrupted();
        check(p.state.equals("OPEN")&&!p.activate(now,"boot-a",false),"supervisor restart does not reset open circuit");
        check(!p.retryDue(now+10000000,"boot-a")&&!p.activate(now,"boot-b",false),"time passage and reboot do not reopen circuit");
        check(p.activate(now,"boot-a",true)&&p.automaticAttempts==3,"explicit retry once preserves budget");
        String retry=p.epoch;p.failed(retry,++now,"CRASH",false);check(p.state.equals("OPEN")&&!p.retryDue(now+100000,"boot-a"),"manual retry never chains retries");
        p.stop();check(!p.retryDue(now+100000,"boot-a"),"stop cancels pending retries");
        p=new EngineRecoveryPolicy(new Disk(path));check(p.state.equals("STOPPED")&&p.totalAttempts==5,"stop and counters survive recreation");
        check(p.activate(now,"boot-a",false),"explicit start after stop");p.interrupted();check(p.state.equals("BLOCKED"),"supervisor interruption needs explicit reconciliation");
        store=new Disk(path);store.reject=true;p=new EngineRecoveryPolicy(store);
        try{p.activate(now,"boot-a",true);throw new AssertionError("failed write admitted attempt");}catch(java.io.IOException expected){check(p.state.equals("BLOCKED"),"failed budget write closes admission");}
        Path corrupt=path.resolveSibling("corrupt");Files.write(corrupt,new byte[]{1,2});try{new EngineRecoveryPolicy(new Disk(corrupt));throw new AssertionError("corrupt accepted");}catch(java.io.IOException expected){checks++;}
        Disk rolling=new Disk(path.resolveSibling("rolling"));p=new EngineRecoveryPolicy(rolling);now=100;p.activate(now,"boot-a",false);p.ready(p.epoch,now);
        for(int i=0;i<5;i++){p.failed(p.epoch,++now,"CRASH",false);now=p.nextAt;p.retryDue(now,"boot-a");p.ready(p.epoch,++now);now+=EngineRecoveryPolicy.STABLE_MS;p.stable(p.epoch,now);}
        p.failed(p.epoch,++now,"CRASH",false);p.retryDue(p.nextAt,"boot-a");check(p.state.equals("OPEN"),"rolling budget survives stable episode resets");
        Disk race=new Disk(path.resolveSibling("race"));p=new EngineRecoveryPolicy(race);p.activate(100,"boot-a",false);String stale=p.epoch;p.stop();
        p.failed(stale,200,"late death",false);check(p.state.equals("STOPPED"),"late death cannot undo stop");
        try{p.ready(stale,300);throw new AssertionError("late readiness accepted");}catch(IllegalStateException expected){check(p.state.equals("STOPPED"),"late readiness cannot undo stop");}
        p.activate(400,"boot-a",false);p.ready(p.epoch,500);p.stable(p.epoch,500+EngineRecoveryPolicy.STABLE_MS);p.failed(p.epoch,501+EngineRecoveryPolicy.STABLE_MS,"CRASH",false);
        check(p.state.equals("BACKOFF"),"stable manual start re-arms bounded automatic recovery");
        p.retryDue(p.nextAt,"another-boot");check(p.state.equals("BLOCKED"),"boot change during backoff blocks automatic recovery");
        System.out.println("ENGINE_RECOVERY_POLICY_PASS checks="+checks);
    }
}
