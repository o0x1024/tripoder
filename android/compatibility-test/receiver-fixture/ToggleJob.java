package dev.tripoder.closurefixture;

/** Disabled JobService is enabled through the same PackageManager writer. */
public final class ToggleJob extends android.app.job.JobService {
    @Override public boolean onStartJob(android.app.job.JobParameters parameters){throw new AssertionError("DELAYED_TOGGLE_JOB_ENTERED");}
    @Override public boolean onStopJob(android.app.job.JobParameters parameters){return false;}
}
