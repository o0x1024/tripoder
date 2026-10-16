package dev.tripoder.provideridentity;

import android.content.Context;
import android.os.BatteryManager;
import org.json.*;

final class BatteryChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        BatteryManager manager=context.getSystemService(BatteryManager.class);
        check(checks,"battery-manager-stable",manager!=null&&manager==context.getSystemService(Context.BATTERY_SERVICE));
        int capacity=manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),status=manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        check(checks,"battery-capacity-valid-platform-value",capacity==Integer.MIN_VALUE||capacity>=0&&capacity<=100);
        check(checks,"battery-status-valid-platform-value",status==Integer.MIN_VALUE||status>=BatteryManager.BATTERY_STATUS_UNKNOWN&&status<=BatteryManager.BATTERY_STATUS_FULL);
        JSONArray properties=new JSONArray();
        for(int id=BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER;id<=BatteryManager.BATTERY_PROPERTY_STATUS;id++)properties.put(new JSONObject().put("id",id).put("int",manager.getIntProperty(id)).put("long",manager.getLongProperty(id)));
        long remaining=manager.computeChargeTimeRemaining();check(checks,"battery-time-remaining-preserves-unavailable",remaining==-1||remaining>=0);
        if(!baseline){
            boolean number=false,text=false;
            try{manager.getLongProperty(9999);}catch(UnsupportedOperationException expected){number=expected.getMessage().contains("BATTERY_PROPERTY_NOT_GRANTED");}
            try{manager.getStringProperty(11);}catch(UnsupportedOperationException expected){text=expected.getMessage().contains("BATTERY_CAPABILITY_UNSUPPORTED");}
            check(checks,"battery-unknown-property-and-identifiers-denied",number&&text);
        }
        return new JSONObject().put("deviceWide",true).put("charging",manager.isCharging()).put("remainingMs",remaining).put("properties",properties);
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private BatteryChecks(){}
}
