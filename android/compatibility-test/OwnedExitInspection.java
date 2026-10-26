package dev.tripoder.hooklab;

import android.app.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Read the QA process's OS exit receipt, bound by its existing TPX1 stamp.
 * Trace contents, descriptions and memory/register data are never read. */
final class OwnedExitInspection {
    static JSONObject inspect(Context host,String space,String pkg,String generation)throws Exception{
        if(!host.getPackageName().startsWith("dev.tripoder.compat")||!generation.matches("[a-f0-9]{64}"))throw new SecurityException("OWNED_EXIT_QUERY_IDENTITY");
        int pid;String physical,dataId;
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY);
            var cursor=db.rawQuery("SELECT s.pid,s.activityStub,i.dataId FROM sessions s JOIN installs i ON s.space=i.space AND s.package=i.package AND s.installGeneration=i.generation WHERE s.generation=? AND s.space=? AND s.package=?",new String[]{generation,space,pkg})){
            if(!cursor.moveToFirst())throw new SecurityException("OWNED_EXIT_SESSION_REQUIRED");
            pid=cursor.getInt(0);String stub=cursor.getString(1);dataId=cursor.getString(2);
            String prefix="dev.tripoder.runtime.RuntimeSlot$";
            if(pid<=0||stub==null||!stub.startsWith(prefix)||stub.length()!=prefix.length()+1)throw new SecurityException("OWNED_EXIT_SLOT_REQUIRED");
            int slot=stub.charAt(prefix.length())-'A';if(slot<0||slot>=8)throw new SecurityException("OWNED_EXIT_SLOT_REQUIRED");
            physical=host.getPackageName()+":slot"+slot;
        }
        byte[] expected=new byte[68];System.arraycopy(new byte[]{'T','P','X','1'},0,expected,0,4);
        System.arraycopy(HexFormat.of().parseHex(generation),0,expected,4,32);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(dataId.getBytes(StandardCharsets.UTF_8)),0,expected,36,32);
        JSONArray matches=new JSONArray();
        var exits=host.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(host.getPackageName(),pid,0);
        if(exits.size()>256)throw new IllegalStateException("OWNED_EXIT_HISTORY_BUDGET");
        int missingSummary=0,wrongSummaryShape=0,differentGeneration=0,differentData=0;
        for(ApplicationExitInfo exit:exits){
            byte[] summary=exit.getProcessStateSummary();
            if(summary==null){missingSummary++;continue;}
            if(summary.length!=68||!Arrays.equals(Arrays.copyOf(summary,4),Arrays.copyOf(expected,4))){wrongSummaryShape++;continue;}
            if(!MessageDigest.isEqual(Arrays.copyOfRange(expected,4,36),Arrays.copyOfRange(summary,4,36))){differentGeneration++;continue;}
            if(!MessageDigest.isEqual(Arrays.copyOfRange(expected,36,68),Arrays.copyOfRange(summary,36,68))){differentData++;continue;}
            if(exit.getPid()!=pid)throw new SecurityException("OWNED_EXIT_PID_MISMATCH");
            if(exit.getRealUid()!=android.os.Process.myUid()||exit.getPackageUid()!=android.os.Process.myUid()||!physical.equals(exit.getProcessName()))throw new SecurityException("OWNED_EXIT_PLATFORM_IDENTITY_MISMATCH");
            JSONObject row=new JSONObject().put("pid",pid).put("timestamp",exit.getTimestamp()).put("reason",exit.getReason()).put("status",exit.getStatus()).put("importance",exit.getImportance()).put("generationStampMatches",true);
            // Availability only: do not consume any crash memory or ANR text.
            try(var trace=exit.getTraceInputStream()){row.put("traceAvailable",trace!=null).put("traceBytesRead",0);}
            catch(java.io.IOException error){row.put("traceErrorType",error.getClass().getName()).put("traceBytesRead",0);}
            matches.put(row);
        }
        return new JSONObject().put("readOnly",true).put("generation",generation).put("pid",pid).put("exits",matches)
            .put("observedPhysicalRows",exits.size()).put("missingSummaryRows",missingSummary).put("wrongSummaryShapeRows",wrongSummaryShape)
            .put("differentGenerationRows",differentGeneration).put("differentDataRows",differentData)
            .put("traceContentsRead",false).put("accountDataRead",false).put("dataCleared",false);
    }
    private OwnedExitInspection(){}
}
