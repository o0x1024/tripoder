package dev.tripoder.provideridentity;

import android.content.*;
import android.content.pm.*;
import org.json.*;

/** Reads native support without publishing; verifies guest discovery cannot grant host writes. */
final class ShortcutCapabilityChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        ShortcutManager manager=context.getSystemService(ShortcutManager.class);
        check(checks,"shortcut-manager-type-and-name-lookup-agree",manager!=null&&manager==context.getSystemService(Context.SHORTCUT_SERVICE));
        boolean supported=manager.isRequestPinShortcutSupported();JSONObject result=new JSONObject().put("pinSupported",supported).put("scope",baseline?"native-launcher-observation":"guest-entry-capability");
        if(baseline)return result;
        check(checks,"guest-shortcut-pin-reports-unavailable-entry-contract",!supported);
        rejected(checks,"dynamic-query",()->manager.getDynamicShortcuts());
        rejected(checks,"remove-all",()->{manager.removeAllDynamicShortcuts();return null;});
        ShortcutInfo item=new ShortcutInfo.Builder(context,"owned-unpublished").setShortLabel("Owned fixture").setIntent(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(context,IdentityActivity.class))).build();
        rejected(checks,"pin-request",()->manager.requestPinShortcut(item,null));
        return result;
    }
    private interface Action{Object run();}
    private static void rejected(JSONArray checks,String label,Action action)throws Exception{boolean rejected=false;try{action.run();}catch(UnsupportedOperationException expected){rejected=expected.getMessage().startsWith("SHORTCUT_CAPABILITY_UNSUPPORTED:");}check(checks,"unsupported-shortcut-"+label+"-has-no-host-side-effect",rejected);}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
}
