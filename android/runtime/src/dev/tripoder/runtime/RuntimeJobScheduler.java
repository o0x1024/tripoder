package dev.tripoder.runtime;

import android.app.job.*;
import android.os.Bundle;
import dev.tripoder.selfhook.JobFrameworkAccess;
import java.util.*;

/** Owned concrete facades; all system mutations execute at the authenticated authority. */
final class RuntimeJobScheduler {
    private final RuntimeEngine engine;
    private final Map<JobScheduler,String> scopes=new WeakHashMap<>();
    private JobScheduler defaultFacade;
    RuntimeJobScheduler(RuntimeEngine engine){this.engine=engine;}
    synchronized JobScheduler facade(){if(defaultFacade==null)defaultFacade=facade(null);return defaultFacade;}
    synchronized JobScheduler facade(String namespace){String key=RuntimeJobNamespace.logical(namespace);JobScheduler value=JobFrameworkAccess.facade(engine.host);scopes.put(value,key);return value;}
    synchronized boolean owns(Object receiver){return scopes.containsKey(receiver);}
    Object dispatch(JobScheduler receiver,String signature,Object[] args)throws Exception{
        String namespace;synchronized(this){namespace=scopes.get(receiver);if(namespace==null)throw new SecurityException("JOB_FACADE_NOT_OWNED");}
        if(signature.equals("forNamespace(java.lang.String)"))return facade(Objects.requireNonNull((String)args[0]));
        if(signature.equals("getNamespace()"))return namespace.isEmpty()?null:namespace;
        Bundle request=engine.auth();request.putString("namespace",namespace.isEmpty()?null:namespace);request.putString("operation",UUID.randomUUID().toString());
        switch(signature){
            case "enqueue(android.app.job.JobInfo,android.app.job.JobWorkItem)":{
                JobInfo info=Objects.requireNonNull((JobInfo)args[0]);byte[] job=RuntimeJobInfo.encode(info),work=RuntimeJobWork.encode((JobWorkItem)args[1]);
                if(job.length+work.length>48*1024)throw new IllegalArgumentException("JOB_ENQUEUE_PAYLOAD_LIMIT");
                request.putString("kind","enqueue");request.putByteArray("job",job);request.putByteArray("work",work);request.putBoolean("early",JobFrameworkAccess.early(info));request.putBoolean("late",JobFrameworkAccess.late(info));return engine.brokerCall(RuntimeWire.JOB_ROUTE,request).getInt("result");
            }
            case "schedule(android.app.job.JobInfo)":{
                JobInfo info=Objects.requireNonNull((JobInfo)args[0]);request.putString("kind","schedule");request.putByteArray("job",RuntimeJobInfo.encode(info));request.putBoolean("early",JobFrameworkAccess.early(info));request.putBoolean("late",JobFrameworkAccess.late(info));return engine.brokerCall(RuntimeWire.JOB_ROUTE,request).getInt("result");
            }
            case "cancel(int)":request.putInt("id",(Integer)args[0]);
            case "cancelAll()":case "cancelInAllNamespaces()":request.putString("kind","cancel");request.putBoolean("allNamespaces",signature.equals("cancelInAllNamespaces()"));engine.brokerCall(RuntimeWire.JOB_ROUTE,request);return null;
            case "getAllPendingJobs()":case "getPendingJob(int)":case "getPendingJobsInAllNamespaces()":{
                request.putString("kind","query");Bundle result=engine.brokerCall(RuntimeWire.JOB_ROUTE,request);Map<String,List<JobInfo>> values=new LinkedHashMap<>();
                for(Bundle row:Objects.requireNonNull(result.<Bundle>getParcelableArrayList("jobs"))){String key=row.getString("namespace");values.computeIfAbsent(key,k->new ArrayList<>()).add(RuntimeJobInfo.decode(row.getByteArray("job")));}
                if(signature.equals("getPendingJobsInAllNamespaces()"))return values;
                List<JobInfo> current=values.getOrDefault(namespace.isEmpty()?null:namespace,List.of());
                if(signature.equals("getAllPendingJobs()"))return new ArrayList<>(current);
                for(JobInfo job:current)if(job.getId()==(Integer)args[0])return job;return null;
            }
            default:throw new UnsupportedOperationException("JOB_API_UNSUPPORTED:"+signature);
        }
    }
}
