package dev.tripoder.runtime;

import android.media.*;
import android.os.*;
import java.util.*;

/** Explicit device queries and bounded device observations; no audio control or capture grant. */
final class RuntimeAudio {
    private final RuntimeEngine engine;
    private volatile AudioManager facade;
    private final Map<AudioDeviceCallback,Registration> callbacks=new IdentityHashMap<>();
    RuntimeAudio(RuntimeEngine engine){this.engine=engine;}
    synchronized AudioManager facade(){if(facade==null){facade=RuntimeBridge.createAudioManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("AUDIO_FACADE_IDENTITY_INVALID");}return facade;}
    boolean owns(AudioManager manager){return manager!=null&&manager==facade;}
    AudioManager platform(){AudioManager value=engine.host.getSystemService(AudioManager.class);if(value==null)throw new IllegalStateException("PLATFORM_AUDIO_UNAVAILABLE");return value;}
    String property(String name){if(!Set.of(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE,AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER,AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND,AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND).contains(name))throw new UnsupportedOperationException("AUDIO_PROPERTY_UNSUPPORTED");return platform().getProperty(name);}
    synchronized void register(AudioDeviceCallback callback,Handler handler){
        if(callback==null||!engine.ownsLoader(callback.getClass().getClassLoader()))throw new SecurityException("AUDIO_CALLBACK_OWNER_INVALID");
        if(callbacks.containsKey(callback))throw new IllegalStateException("AUDIO_CALLBACK_ALREADY_REGISTERED");if(callbacks.size()>=16)throw new IllegalStateException("AUDIO_CALLBACK_LIMIT");
        Registration registration=new Registration(callback,handler==null?new Handler(Looper.getMainLooper()):handler);callbacks.put(callback,registration);
        try{platform().registerAudioDeviceCallback(registration,new Handler(Looper.getMainLooper()));registration.active=true;}catch(RuntimeException|Error error){registration.active=false;throw error;}
    }
    synchronized void unregister(AudioDeviceCallback callback){Registration registration=callbacks.get(callback);if(registration==null)throw new IllegalArgumentException("AUDIO_CALLBACK_NOT_REGISTERED");registration.active=false;platform().unregisterAudioDeviceCallback(registration);callbacks.remove(callback);}
    private final class Registration extends AudioDeviceCallback {
        final AudioDeviceCallback guest;final Handler handler;boolean active;
        Registration(AudioDeviceCallback guest,Handler handler){this.guest=guest;this.handler=handler;}
        void deliver(AudioDeviceInfo[] values,boolean added){
            synchronized(RuntimeAudio.this){if(!active)return;}
            AudioDeviceInfo[] snapshot=Objects.requireNonNull(values,"AUDIO_DEVICES_MISSING").clone();
            if(!handler.post(()->{synchronized(RuntimeAudio.this){if(!active)return;}if(added)guest.onAudioDevicesAdded(snapshot);else guest.onAudioDevicesRemoved(snapshot);}))throw new IllegalStateException("AUDIO_CALLBACK_HANDLER_CLOSED");
        }
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){deliver(devices,true);}
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){deliver(devices,false);}
    }
}
