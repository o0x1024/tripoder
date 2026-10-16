package dev.tripoder.selfhookprobe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ProbeResultPublicationTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("publication check "+checks);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        String content="{\"complete\":true,\"payload\":\""+"x".repeat(2*1024*1024)+"\"}\n";
        Path concurrent=Files.createDirectory(root.resolve("concurrent"));CountDownLatch go=new CountDownLatch(1);
        AtomicInteger success=new AtomicInteger(),rejected=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread[] writers=new Thread[6];
        for(int i=0;i<writers.length;i++) {
            writers[i]=new Thread(()->{try {go.await();ProbeFiles.publishResult(concurrent.toFile(),content);success.incrementAndGet();}
                catch(IllegalStateException expected){rejected.incrementAndGet();}catch(Throwable error){failure.set(error);}});writers[i].start();
        }
        go.countDown();boolean running;
        do {
            if(Files.exists(concurrent.resolve("result.json")))check(Files.readString(concurrent.resolve("result.json")).equals(content));
            running=false;for(Thread writer:writers)running|=writer.isAlive();if(running)Thread.sleep(1);
        }while(running);
        for(Thread writer:writers)writer.join();check(failure.get()==null);check(success.get()==1&&rejected.get()==5);
        check(Files.readString(concurrent.resolve("result.json")).equals(content));check(!Files.exists(concurrent.resolve(".result.part")));
        check(Files.exists(concurrent.resolve(".result.claim")));
        try{ProbeFiles.publishResult(concurrent.toFile(),"overwrite");throw new AssertionError("republished");}catch(IllegalStateException expected){check(true);}
        check(Files.readString(concurrent.resolve("result.json")).equals(content));
        Path partial=Files.createDirectory(root.resolve("partial"));Files.writeString(partial.resolve(".result.part"),"incomplete");
        try{ProbeFiles.publishResult(partial.toFile(),content);throw new AssertionError("staging reused");}catch(IllegalStateException expected){check(true);}
        check(!Files.exists(partial.resolve("result.json")));check(Files.readString(partial.resolve(".result.part")).equals("incomplete"));
        try{ProbeFiles.publishResult(partial.toFile(),content);throw new AssertionError("failed claim retried");}catch(IllegalStateException expected){check(true);}
        System.out.println("{\"passed\":true,\"checks\":"+checks+",\"publishers\":6,\"commits\":1,\"partialResultObserved\":false}");
    }
}
