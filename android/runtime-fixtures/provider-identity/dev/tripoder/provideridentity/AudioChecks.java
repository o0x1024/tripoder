package dev.tripoder.provideridentity;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class AudioChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        AudioManager manager=context.getSystemService(AudioManager.class);
        check(checks,"audio-manager-scoped-instance",manager!=null&&manager==context.getSystemService(Context.AUDIO_SERVICE));
        int min=manager.getStreamMinVolume(AudioManager.STREAM_MUSIC),max=manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        check(checks,"audio-real-stream-query-range",min>=0&&max>=min&&current>=min&&current<=max);
        AudioDeviceInfo[] devices=manager.getDevices(AudioManager.GET_DEVICES_ALL);
        check(checks,"audio-real-device-query",devices!=null&&devices.length>0);
        String rate=manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        check(checks,"audio-property-real-platform-shape",rate==null||Integer.parseInt(rate)>0);
        HandlerThread thread=new HandlerThread("owned-audio-callback");thread.start();CountDownLatch added=new CountDownLatch(1);AtomicInteger observed=new AtomicInteger();AtomicReference<String> callbackThread=new AtomicReference<>();
        AudioDeviceCallback callback=new AudioDeviceCallback(){@Override public void onAudioDevicesAdded(AudioDeviceInfo[] values){observed.addAndGet(values.length);callbackThread.set(Thread.currentThread().getName());added.countDown();}};
        manager.registerAudioDeviceCallback(callback,new Handler(thread.getLooper()));
        try{
            check(checks,"audio-real-initial-device-callback-on-requested-looper",added.await(10,TimeUnit.SECONDS)&&observed.get()>0&&"owned-audio-callback".equals(callbackThread.get()));
            if(!baseline){boolean duplicate=false;try{manager.registerAudioDeviceCallback(callback,null);}catch(IllegalStateException expected){duplicate=expected.getMessage().contains("AUDIO_CALLBACK_ALREADY_REGISTERED");}check(checks,"audio-duplicate-callback-admission-rejected",duplicate);}
        }finally{manager.unregisterAudioDeviceCallback(callback);thread.quitSafely();thread.join(2000);}
        if(!baseline){
            boolean repeated=false;try{manager.unregisterAudioDeviceCallback(callback);}catch(IllegalArgumentException expected){repeated=expected.getMessage().contains("AUDIO_CALLBACK_NOT_REGISTERED");}check(checks,"audio-callback-cancellation-is-explicit",repeated);
            // Volume is device audio state any app may set; writing back the current value changes nothing but proves the call is handed to the platform.
            boolean allowed=false;try{manager.setStreamVolume(AudioManager.STREAM_MUSIC,current,0);allowed=true;}catch(UnsupportedOperationException expected){throw new IllegalStateException("AUDIO_VOLUME_PLATFORM_PATH:"+expected.getMessage(),expected);}catch(SecurityException platformDecision){allowed=true;}check(checks,"audio-volume-write-is-platform-decision",allowed);
            boolean recording=false;try{manager.getActiveRecordingConfigurations();}catch(UnsupportedOperationException expected){recording=expected.getMessage().contains("AUDIO_CAPABILITY_UNSUPPORTED");}check(checks,"audio-recording-observation-not-granted",recording);
        }
        return new JSONObject().put("deviceCount",devices.length).put("callbackDeviceCount",observed.get()).put("callbackThread",callbackThread.get()).put("outputSampleRate",rate);
    }
    private AudioChecks(){}
}
