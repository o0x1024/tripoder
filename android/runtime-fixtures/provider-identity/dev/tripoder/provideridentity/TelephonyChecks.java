package dev.tripoder.provideridentity;

import android.content.Context;
import android.telephony.TelephonyManager;
import org.json.*;

final class TelephonyChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        TelephonyManager manager=context.getSystemService(TelephonyManager.class);
        int type=manager.getPhoneType(),state=manager.getSimState(),active=manager.getActiveModemCount(),supported=manager.getSupportedModemCount(),phones=manager.getPhoneCount();
        check(checks,"telephony-real-device-capability-values",type>=0&&type<=3&&state>=0&&state<=11&&active>=0&&supported>=active&&phones>=0);
        for(int i=0;i<active;i++){int sim=manager.getSimState(i);check(checks,"telephony-real-slot-state-"+i,sim>=0&&sim<=11);}
        boolean voice=manager.isVoiceCapable(),sms=manager.isSmsCapable(),data=manager.isDataCapable();
        if(!baseline){
            check(checks,"telephony-contexts-share-owned-facade",manager==context.getApplicationContext().getSystemService(TelephonyManager.class));
            boolean identifier=false;try{manager.getDeviceId();}catch(UnsupportedOperationException expected){identifier=expected.getMessage().contains("TELEPHONY_CAPABILITY_UNSUPPORTED");}
            check(checks,"telephony-device-identifier-explicitly-rejected",identifier);
            boolean subscription=false;try{manager.createForSubscriptionId(0);}catch(UnsupportedOperationException expected){subscription=expected.getMessage().contains("TELEPHONY_CAPABILITY_UNSUPPORTED");}
            check(checks,"telephony-arbitrary-subscription-explicitly-rejected",subscription);
        }
        return new JSONObject().put("phoneType",type).put("simState",state).put("activeModems",active).put("supportedModems",supported).put("phones",phones).put("voice",voice).put("sms",sms).put("data",data).put("signal",SignalChecks.inspect(manager,baseline,checks));
    }
    private TelephonyChecks(){}
}
