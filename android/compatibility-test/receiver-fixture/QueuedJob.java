package dev.tripoder.closurefixture;

import android.app.job.*;
import android.content.Intent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real JobScheduler work queue and actual completion capabilities. */
public final class QueuedJob extends JobService {
    static final CountDownLatch first=new CountDownLatch(1),all=new CountDownLatch(1);
    static final Set<Integer> sequences=Collections.synchronizedSet(new HashSet<>());
    static final AtomicInteger starts=new AtomicInteger();
    static volatile String error;
    static volatile boolean deliveryCounts=true,duplicateDenied=true,forgedDenied=true,finishedDenied=true;
    @Override public boolean onStartJob(JobParameters parameters){
        int invocation=starts.incrementAndGet();new Thread(()->{
            try{
                int expected=invocation==1?2:1;
                for(int i=0;i<expected;i++){
                    JobWorkItem item=parameters.dequeueWork();if(item==null)throw new AssertionError("REAL_WORK_MISSING");
                    if(!"owned-queue".equals(item.getIntent().getAction())||!sequences.add(item.getIntent().getIntExtra("sequence",-1)))throw new AssertionError("REAL_WORK_PAYLOAD_OR_DUPLICATION");
                    deliveryCounts&=item.getDeliveryCount()>0;
                    try{parameters.completeWork(new JobWorkItem(new Intent("forged-owned-item")));forgedDenied=false;}catch(SecurityException expectedDenial){}
                    parameters.completeWork(item);
                    try{parameters.completeWork(item);duplicateDenied=false;}catch(SecurityException expectedDenial){}
                }
                jobFinished(parameters,false);
                try{parameters.dequeueWork();finishedDenied=false;}catch(SecurityException expectedDenial){}
            }catch(Throwable failure){error=failure.getClass().getName()+":"+failure.getMessage();}
            finally{if(invocation==1)first.countDown();else all.countDown();}
        },"owned-job-work-queue").start();return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){return false;}
}
