import Java from "frida-java-bridge";

/** No system Binder is held by the guest facade. Unsupported operations never reach Launcher. */
export function installShortcutCapability(Bridge:any):void {
  const Manager=Java.use("android.content.pm.ShortcutManager");
  const constructor=Manager.$new.overload("android.content.Context","android.content.pm.IShortcutService");
  Bridge.createShortcutCapability.implementation=(context:any)=>constructor.call(Manager,context,null);
  const installed=new Set<string>();
  for(const declaration of Manager.class.getDeclaredMethods()){
    if((declaration.getModifiers()&8)!==0)continue;
    const name=String(declaration.getName()),types=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+types.join(",")+")";
    if(installed.has(key))continue;const method=Manager[name].overload(...types);
    method.implementation=function(this:any,...args:any[]){
      if(!Bridge.ownsShortcutCapability(this))return method.call(this,...args);
      if(key==="isRequestPinShortcutSupported()")return Bridge.shortcutPinSupported();
      throw Java.use("java.lang.UnsupportedOperationException").$new("SHORTCUT_CAPABILITY_UNSUPPORTED:"+key);
    };installed.add(key);
  }
  for(const key of ["isRequestPinShortcutSupported()","requestPinShortcut(android.content.pm.ShortcutInfo,android.content.IntentSender)","getDynamicShortcuts()","removeAllDynamicShortcuts()"])
    if(!installed.has(key))throw new Error("SHORTCUT_SIGNATURE_MISSING:"+key);
}
