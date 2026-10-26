package dev.tripoder.closurefixture;

/** Real delayed and expedited JobService callbacks, never a synthetic schedule ACK. */
public final class OwnedJob extends android.app.job.JobService {
    static final java.util.concurrent.CountDownLatch expedited=new java.util.concurrent.CountDownLatch(1);
    static volatile boolean exact;
    @Override public boolean onStartJob(android.app.job.JobParameters parameters){
        if(parameters.getJobId()!=902)throw new AssertionError("DELAYED_JOB_ENTERED_TOO_EARLY");
        exact=parameters.isExpeditedJob()&&"expedited-owned".equals(parameters.getExtras().getString("kind"));
        expedited.countDown();return false;
    }
    @Override public boolean onStopJob(android.app.job.JobParameters parameters){return false;}
}
