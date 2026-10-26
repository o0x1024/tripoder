package dev.tripoder.closurefixture;

/** A real queued START can be fenced before its main-thread delivery. */
public final class ReplacedJob extends android.app.job.JobService {
    static final java.util.concurrent.CountDownLatch latest=new java.util.concurrent.CountDownLatch(1);
    static volatile boolean exact;
    @Override public boolean onStartJob(android.app.job.JobParameters parameters){exact=parameters.getExtras().getInt("sequence")==2;latest.countDown();return false;}
    @Override public boolean onStopJob(android.app.job.JobParameters parameters){return false;}
}
