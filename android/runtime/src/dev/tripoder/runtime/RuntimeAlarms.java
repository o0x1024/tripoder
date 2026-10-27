package dev.tripoder.runtime;

import android.app.*;
import android.os.Bundle;
import dev.tripoder.selfhook.PendingIntentFrameworkAccess;
import java.lang.reflect.Method;
import java.util.*;

/** Owned facade. The authority schedules only its authenticated broadcast tokens. */
final class RuntimeAlarms {
    private final RuntimeEngine engine;
    private AlarmManager facade;
    RuntimeAlarms(RuntimeEngine engine){this.engine=engine;}
    synchronized AlarmManager facade(){if(facade==null)facade=PendingIntentFrameworkAccess.alarm(new GuestContext(engine.host,engine));return facade;}
    Object invoke(Method method,Object receiver,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        if(receiver!=facade)return original.call(args);
        String name=method.getName();Bundle request=engine.auth();request.putString("kind","alarm");request.putString("action",name);
        if(name.equals("canScheduleExactAlarms"))return engine.brokerCall(RuntimeWire.PENDING_INTENT,request).getBoolean("allowed");
        if(name.equals("cancelAll")){request.putString("operation",UUID.randomUUID().toString());engine.brokerCall(RuntimeWire.PENDING_INTENT,request);return null;}
        if(!Set.of("set","setExact","setAndAllowWhileIdle","setExactAndAllowWhileIdle","setWindow","setRepeating","setInexactRepeating","cancel").contains(name)||args.length==0||!(args[args.length-1] instanceof PendingIntent))throw new UnsupportedOperationException("ALARM_SHAPE_UNSUPPORTED:"+method);
        PendingIntent token=(PendingIntent)args[args.length-1];Bundle value=engine.pendingIntents.view(token);
        if(value==null)throw new SecurityException("ALARM_PENDING_TOKEN_NOT_OWNED");request.putString("version",value.getString("version"));
        if(!name.equals("cancel")){
            boolean extended=Set.of("setWindow","setRepeating","setInexactRepeating").contains(name);
            if(args.length!=(extended?4:3))throw new UnsupportedOperationException("ALARM_SHAPE_UNSUPPORTED:"+method);
            int alarmType=(Integer)args[0];
            long trigger=RuntimeTimeWarp.adjustAlarmTrigger(alarmType,(Long)args[1]);
            request.putInt("type",alarmType);request.putLong("trigger",trigger);
            if(extended){
                long second=RuntimeTimeWarp.scaleDelayMillis((Long)args[2]);
                request.putLong(name.equals("setWindow")?"window":"interval",second);
            }
        }
        request.putString("operation",UUID.randomUUID().toString());engine.brokerCall(RuntimeWire.PENDING_INTENT,request);return null;
    }
}
