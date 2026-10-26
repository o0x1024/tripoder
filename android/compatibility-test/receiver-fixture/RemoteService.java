package dev.tripoder.closurefixture;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.ArrayList;

/** A real second guest process reads the committed file, then replies over its real Binder. */
public final class RemoteService extends Service {
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),message->{
        Bundle data=new Bundle();
        try{
            var preferences=getSharedPreferences("platform-string-values",MODE_PRIVATE);
            data.putString("value",preferences.getString("nul\u0000key",null));
            data.putStringArrayList("set",new ArrayList<>(preferences.getStringSet("set",java.util.Set.of())));
            android.content.ComponentName component=new android.content.ComponentName(this,ToggleActivity.class);
            data.putBoolean("componentExact",getPackageManager().getComponentEnabledSetting(component)==1&&getPackageManager().getActivityInfo(component,0).enabled);
            data.putBoolean("namesExact","null-value".equals(getSharedPreferences(null,MODE_PRIVATE).getString("name-contract",null))&&"empty-value".equals(getSharedPreferences("",MODE_PRIVATE).getString("name-contract",null)));
            data.putBoolean("largeExact",Main.LONG_STRING.equals(preferences.getString("large",null)));
            var device=createDeviceProtectedStorageContext().getSharedPreferences("owned-device-storage",MODE_PRIVATE);
            String round=device.getString("round",null);boolean deviceExact=round!=null&&!round.isEmpty()&&"device".equals(device.getString("scope",null));for(int i=0;i<8;i++)deviceExact&=(round+":"+i).equals(device.getString("entry-"+i,null));
            data.putBoolean("deviceStorageExact",deviceExact);
            data.putInt("pid",android.os.Process.myPid());
        }catch(Throwable error){data.putString("error",error.getClass().getName()+":"+error.getMessage());}
        Message response=Message.obtain();response.setData(data);
        try{message.replyTo.send(response);}catch(RemoteException error){throw new IllegalStateException("OWNED_REPLY_FAILED",error);}
        return true;
    }));
    @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
}
