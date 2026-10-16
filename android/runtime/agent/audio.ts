import Java from "frida-java-bridge";

export function installAudio(Bridge: any) {
  const Manager = Java.use("android.media.AudioManager");
  const create = Manager.$new.overload("android.content.Context");
  Bridge.createAudioManager.implementation = (host: any) => create.call(Manager, host);
  const allowed = new Set([
    "getMode()", "getRingerMode()", "isMusicActive()", "isMicrophoneMute()", "isSpeakerphoneOn()",
    "isBluetoothScoOn()", "isBluetoothA2dpOn()", "isBluetoothScoAvailableOffCall()", "isWiredHeadsetOn()",
    "isVolumeFixed()", "getStreamVolume(int)", "getStreamMaxVolume(int)", "getStreamMinVolume(int)",
    "isStreamMute(int)", "getDevices(int)",
  ]);
  const required = new Set([...allowed,"getProperty(java.lang.String)","registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)","unregisterAudioDeviceCallback(android.media.AudioDeviceCallback)"]);
  const installed = new Set<string>();
  for (const declaration of Manager.class.getDeclaredMethods()) {
    if ((declaration.getModifiers() & 8) !== 0) continue;
    const name = String(declaration.getName());
    const signature = Array.from(declaration.getParameterTypes(), (type: any) => String(type.getName()));
    const key = name + "(" + signature.join(",") + ")";
    if (installed.has(key)) continue;
    const method = Manager[name].overload(...signature);
    method.implementation = function(this: any,...args: any[]) {
      if (!Bridge.ownsAudioManager(this)) return method.call(this,...args);
      if (allowed.has(key)) return method.call(Bridge.audioPlatform(),...args);
      if (key === "getProperty(java.lang.String)") return Bridge.audioProperty(args[0]);
      if (key === "registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)") return Bridge.audioRegister(...args);
      if (key === "unregisterAudioDeviceCallback(android.media.AudioDeviceCallback)") return Bridge.audioUnregister(args[0]);
      throw Java.use("java.lang.UnsupportedOperationException").$new("AUDIO_CAPABILITY_UNSUPPORTED:"+key);
    };
    installed.add(key);
  }
  for (const key of required) if (!installed.has(key)) throw new Error("AUDIO_SIGNATURE_MISSING:"+key);
}
