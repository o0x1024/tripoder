package dev.tripoder.runtime;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Android owns this service, token, engine and all callback Binder traffic. */
public abstract class RuntimeJobGateway extends JobService {
    final String instance=java.util.UUID.randomUUID().toString();
    @Override public void onCreate(){super.onCreate();if(!RuntimeRecoveryGate.productEnabled()&&RuntimeEngine.current==null)try{long start=android.os.SystemClock.elapsedRealtime();System.loadLibrary("tripoderselfhook");RuntimeFixedPreparation.prewarm();android.util.Log.i("TripoderJobs","PHYSICAL_PREPARED:ms="+(android.os.SystemClock.elapsedRealtime()-start));}catch(Exception error){throw new IllegalStateException("JOB_PHYSICAL_PREPARATION_FAILED",error);}}
    @Override public final boolean onStartJob(JobParameters parameters){long start=android.os.SystemClock.elapsedRealtime();android.util.Log.i("TripoderJobs","START_ENTER:"+parameters.getJobId());try{if(!RuntimeJobBootstrap.load(this,parameters))return false;return RuntimeEngine.current.jobs.start(this,parameters);}catch(Exception error){throw new IllegalStateException("JOB_START_FAILED",error);}finally{android.util.Log.i("TripoderJobs","START_RETURN:ms="+(android.os.SystemClock.elapsedRealtime()-start));}}
    @Override public final boolean onStopJob(JobParameters parameters){if(RuntimeEngine.current==null)return false;try{return RuntimeEngine.current.jobs.stop(this,parameters);}catch(Exception error){throw new IllegalStateException("JOB_STOP_FAILED",error);}}
    @Override public final void onNetworkChanged(JobParameters parameters){if(RuntimeEngine.current==null)return;try{RuntimeEngine.current.jobs.network(this,parameters);}catch(Exception error){throw new IllegalStateException("JOB_NETWORK_CALLBACK_FAILED",error);}}
    @Override public void onDestroy(){try{if(RuntimeEngine.current!=null)RuntimeEngine.current.jobs.destroyed(this);}catch(Exception error){throw new IllegalStateException("JOB_DESTROY_FAILED",error);}finally{super.onDestroy();}}
}
