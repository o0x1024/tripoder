package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

final class EntryWriterChecks {
    private static void save(File directory,String name,byte[] data)throws Exception{
        ProbeFiles.require(data!=null&&data.length>0,"WRITER_IMAGE_REQUIRED");
        try(FileOutputStream stream=new FileOutputStream(new File(directory,name))){stream.write(data);stream.getFD().sync();}
    }
    static JSONObject run(File directory,String compilation)throws Exception{
        Method target=EntryWriterFixture.class.getDeclaredMethod("value",int.class),caller=EntryWriterCaller.class.getDeclaredMethod("call",int.class);
        JSONObject targetBefore=null,callerBefore=null;long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        do{
            for(int i=0;i<10000;i++){target.invoke(null,i);caller.invoke(null,i);}
            targetBefore=ArtProbe.sample(target);callerBefore=ArtProbe.sample(caller);
            if(ArtCompilation.kind(targetBefore).equals(compilation)&&ArtCompilation.kind(callerBefore).equals(compilation))break;
            ProbeFiles.require(android.os.SystemClock.elapsedRealtime()<deadline,"WRITER_COMPILED_PAIR_REQUIRED");Thread.sleep(10);
        }while(true);
        JSONObject policyBefore=new JSONObject(ManagedHook.processPolicy());ManagedHook.Handle handle=ManagedHook.prepare(target,new Method[0]);
        JSONObject prepared=new JSONObject(handle.report()),policyPrepared=new JSONObject(ManagedHook.processPolicy());
        ProbeFiles.require(!prepared.getBoolean("writerInterpositionInstalled")&&!policyPrepared.getBoolean("owned"),"PREPARE_MUST_NOT_PUBLISH_WRITER");
        File beforeMaps=new File(directory,"writer-before-maps.txt"),beforeSmaps=new File(directory,"writer-before-smaps.txt");
        ProbeFiles.write(beforeMaps,ProbeFiles.read("/proc/self/maps"));ProbeFiles.write(beforeSmaps,ProbeFiles.read("/proc/self/smaps"));
        AtomicBoolean stop=new AtomicBoolean();AtomicInteger errors=new AtomicInteger();AtomicLong calls=new AtomicLong();
        CountDownLatch started=new CountDownLatch(4);Thread[] workers=new Thread[4];int made=0;boolean active=false;
        JSONObject events,state,disabled,status;int initial;
        try{
            handle.enable(call->(Integer)call.original(call.arguments())+100);active=true;initial=EntryWriterFixture.bodies.get();
            for(;made<workers.length;made++){
                Thread worker=new Thread(()->{started.countDown();while(!stop.get()){
                    try{if(EntryWriterCaller.call(7)!=108)errors.incrementAndGet();}catch(Throwable error){errors.incrementAndGet();}calls.incrementAndGet();}},"owned-writer-caller-"+made);
                workers[made]=worker;worker.start();
            }
            ProbeFiles.require(started.await(5,TimeUnit.SECONDS),"WRITER_WORKERS_STARTED");deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(calls.get()<1000){ProbeFiles.require(android.os.SystemClock.elapsedRealtime()<deadline,"WRITER_CALL_PROGRESS");Thread.yield();}
            events=new JSONObject(EntryWriterProbe.exerciseNative(target));
            ProbeFiles.write(new File(directory,"writer-events.json"),events.toString(2)+"\n");
            ProbeFiles.require(events.getBoolean("passed"),"REAL_ENTRY_WRITER_EVENTS");
            stop.set(true);for(Thread worker:workers){worker.join(5000);ProbeFiles.require(!worker.isAlive(),"WRITER_CALLER_JOINED");}
            int postValue=EntryWriterCaller.call(9);calls.incrementAndGet();
            ProbeFiles.write(new File(directory,"writer-post-event.json"),new JSONObject().put("actual",postValue).put("expected",110)
                .put("errors",errors.get()).put("calls",calls.get()).put("bodyCalls",EntryWriterFixture.bodies.get()-initial)
                .put("active",new JSONObject(handle.report())).put("target",ArtProbe.sample(target)).put("caller",ArtProbe.sample(caller))
                .put("runMethod",ArtProbe.sample(EntryWriterChecks.class.getDeclaredMethod("run",File.class,String.class)))
                .put("targetBefore",targetBefore).put("callerBefore",callerBefore).toString(2)+"\n");
            ProbeFiles.require(postValue==110,"WRITER_POST_EVENT_CALL");
            state=new JSONObject(handle.report());status=new JSONObject(EntryWriterProbe.statusNative());
            ProbeFiles.require(errors.get()==0&&EntryWriterFixture.bodies.get()-initial==calls.get()
                &&state.getLong("originals")==calls.get(),"WRITER_CALLS_AND_ORIGINALS_EXACT");
            save(directory,"writer-original.bin",EntryWriterProbe.imageNative(0));save(directory,"writer-active.bin",EntryWriterProbe.imageNative(1));
            save(directory,"writer-leaf.so",EntryWriterProbe.imageNative(2));
            handle.disable();active=false;disabled=new JSONObject(handle.report());ProbeFiles.require(EntryWriterCaller.call(7)==8,"WRITER_DISABLED_ORIGINAL");
            return new JSONObject().put("targetBefore",targetBefore).put("callerBefore",callerBefore).put("requestedCompilation",compilation)
                .put("policyBefore",policyBefore).put("policyPrepared",policyPrepared).put("prepared",prepared).put("active",state).put("disabled",disabled)
                .put("events",events).put("status",status).put("calls",calls.get()).put("errors",errors.get()).put("threads",4)
                .put("beforeMapsSha256",ProbeFiles.sha(beforeMaps)).put("beforeSmapsSha256",ProbeFiles.sha(beforeSmaps))
                .put("bodyCalls",EntryWriterFixture.bodies.get()-initial).put("fullCallerCoverage",false).put("productAccepted",false);
        }finally{
            stop.set(true);for(int i=0;i<made;i++){workers[i].join(5000);if(workers[i].isAlive())throw new IllegalStateException("WRITER_WORKER_STILL_RUNNING");}
            if(active)handle.disable();
        }
    }
}
