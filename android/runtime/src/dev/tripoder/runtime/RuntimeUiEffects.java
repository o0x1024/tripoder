package dev.tripoder.runtime;

import android.media.AudioManager;
import android.view.SoundEffectConstants;
import android.view.View;

/** Narrow platform UI capability. Never exposes the general host AudioManager to guest Context. */
final class RuntimeUiEffects {
    private RuntimeUiEffects(){}
    static void play(RuntimeEngine engine,View root,int effect){
        if(!engine.ownsWindow(root))throw new SecurityException("UI_SOUND_WINDOW_NOT_OWNED");
        if(effect!=SoundEffectConstants.CLICK)throw new UnsupportedOperationException("UI_SOUND_EFFECT_UNSUPPORTED:"+effect);
        AudioManager audio=(AudioManager)engine.host.getSystemService(android.content.Context.AUDIO_SERVICE);
        if(audio==null)throw new IllegalStateException("PLATFORM_UI_AUDIO_UNAVAILABLE");
        // Android's sound-effects preference and audio policy remain authoritative.
        audio.playSoundEffect(AudioManager.FX_KEY_CLICK);
    }
}
