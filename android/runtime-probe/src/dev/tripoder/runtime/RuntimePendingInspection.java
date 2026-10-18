package dev.tripoder.runtime;

import android.app.PendingIntent;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;

/** Owned fixture only. Observe durable receipts; explicit stimulus sends the real system token. */
public final class RuntimePendingInspection {
    public static Bundle call(RuntimeBroker broker,Bundle input)throws Exception{
        String space=RuntimeWire.required(input,"space"),action=RuntimeWire.required(input,"action");
        if(!space.matches("pending-[a-f0-9]{16}-pending(?:30|31)"))throw new SecurityException("OWNED_PENDING_FIXTURE_SPACE_REQUIRED");
        ApkStore.BundleInstall install;Bundle result=new Bundle();PendingIntentLedger.Version fire=null;
        synchronized(broker){
            install=broker.apks.get(space,"dev.tripoder.pendingfixture");
            var versions=broker.pendingIntents.versions(install.generation);result.putBoolean("tokensReserveNoProcess",!versions.isEmpty()&&versions.stream().allMatch(v->v.processStub.isEmpty()));
            if(action.equals("cleanup")){
                for(var slot:broker.processSlots())if(slot.install.generation.equals(install.generation))throw new SecurityException("PENDING_CLEANUP_REQUIRES_DEAD_FIXTURE");
            }
            RuntimeBroker.Slot receiver=broker.findProcess(space,"dev.tripoder.pendingfixture","dev.tripoder.pendingfixture:delivery");
            if(receiver!=null){var observed=broker.processEvidence(receiver);result.putString("receiverGeneration",observed.getString("generation"));result.putInt("receiverPid",observed.getInt("pid"));}
            if(action.equals("fireAfterDeath")){
                if(receiver!=null||broker.findProcess(space,"dev.tripoder.pendingfixture","dev.tripoder.pendingfixture")!=null)throw new SecurityException("PENDING_FIXTURE_DEATH_NOT_CONFIRMED");
                for(var value:broker.pendingIntents.versions(install.generation))if(value.requestCode==99&&value.current&&!value.fenced&&!value.consumed){if(fire!=null)throw new SecurityException("PENDING_FIXTURE_TOKEN_AMBIGUOUS");fire=value;}
                if(fire==null)throw new SecurityException("PENDING_FIXTURE_COLD_TOKEN_MISSING");
            }else if(!action.equals("read")&&!action.equals("cleanup"))throw new IllegalArgumentException("PENDING_FIXTURE_ACTION");
        }
        if(action.equals("cleanup")){
            java.util.List<PendingIntentLedger.Version> versions;
            synchronized(broker){versions=broker.pendingIntents.versions(install.generation);}
            for(var value:versions)broker.pendingIntentBroker.cancel(install,value,java.util.UUID.randomUUID().toString());
            java.util.List<String> dead=new java.util.ArrayList<>();synchronized(broker){try(var cursor=broker.store.getReadableDatabase().rawQuery("SELECT generation,state FROM sessions WHERE space=? AND package=? AND installGeneration=?",new String[]{space,"dev.tripoder.pendingfixture",install.generation})){while(cursor.moveToNext()){if(!"DEAD".equals(cursor.getString(1)))throw new SecurityException("PENDING_CLEANUP_DEATH_UNCONFIRMED");dead.add(cursor.getString(0));}}}
            for(String generation:dead)broker.processes.close(generation);
            result.putInt("canceledVersions",versions.size());result.putInt("closedDeadGenerations",dead.size());return result;
        }
        if(fire!=null){
            PendingIntent token=broker.pendingIntentBroker.lookup(fire);if(token==null)throw new IllegalStateException("PENDING_FIXTURE_SYSTEM_TOKEN_ABSENT");
            var complete=new java.util.concurrent.CountDownLatch(1);int[] code={Integer.MIN_VALUE};String[] data={null};
            token.send(broker.host,0,null,(p,i,c,d,e)->{code[0]=c;data[0]=d;complete.countDown();},new android.os.Handler(android.os.Looper.getMainLooper()));
            if(!complete.await(10000,java.util.concurrent.TimeUnit.MILLISECONDS))throw new IllegalStateException("PENDING_COLD_SYSTEM_COMPLETION_TIMEOUT");
            result.putString("firedVersion",fire.version);result.putInt("systemResultCode",code[0]);result.putString("systemResultData",data[0]);
        }
        for(String[] item:new String[][]{{"result","pending-contract.json"},{"cold","pending-after-death.json"}}){File file=new File(install.data,"files/"+item[1]);if(file.isFile()){if(file.length()>48000)throw new IllegalStateException("PENDING_FIXTURE_REPORT_SIZE");result.putString(item[0],Files.readString(file.toPath()));}}
        if(result.containsKey("cold")){
            org.json.JSONArray history=new org.json.JSONObject(result.getString("cold")).getJSONArray("history"),actual=new org.json.JSONArray();
            for(int i=0;i<history.length();i++){
                int expected=history.getJSONObject(i).getInt("pid");
                for(var exit:broker.host.getSystemService(android.app.ActivityManager.class).getHistoricalProcessExitReasons(broker.host.getPackageName(),expected,0))actual.put(new org.json.JSONObject().put("pid",exit.getPid()).put("uid",exit.getRealUid()).put("process",exit.getProcessName()).put("reason",exit.getReason()).put("status",exit.getStatus()).put("timestamp",exit.getTimestamp()).put("summaryBytes",exit.getProcessStateSummary()==null?0:exit.getProcessStateSummary().length));
            }result.putString("physicalExitHistory",actual.toString());
        }
        return result;
    }
    private RuntimePendingInspection(){}
}
