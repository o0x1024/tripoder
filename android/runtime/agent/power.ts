import Java from "frida-java-bridge";

export function installPower(Bridge:any){
  const Manager=Java.use("android.os.PowerManager");
  const create=Manager.$new.overload("android.content.Context","android.os.IPowerManager","android.os.IThermalService","android.os.Handler");
  Bridge.createPowerManager.implementation=(host:any)=>create.call(Manager,host,null,null,Java.use("android.os.Handler").$new(Java.use("android.os.Looper").getMainLooper()));
  const queries=new Set(["getCurrentThermalStatus()","getThermalHeadroom(int)","getThermalHeadroomThresholds()","getBatteryDischargePrediction()","isBatteryDischargePredictionPersonalized()","isInteractive()","isScreenOn()","isPowerSaveMode()","getLocationPowerSaveMode()","isDeviceIdleMode()","isDeviceLightIdleMode()","isLowPowerStandbyEnabled()","isExemptFromLowPowerStandby()","isAllowedInLowPowerStandby(int)","isAllowedInLowPowerStandby(java.lang.String)","isRebootingUserspaceSupported()","isSustainedPerformanceModeSupported()","isWakeLockLevelSupported(int)"]);
  const routes:Record<string,(...args:any[])=>any>={
    "isIgnoringBatteryOptimizations(java.lang.String)":pkg=>Bridge.powerIgnoring(pkg),
    "addThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)":listener=>Bridge.powerStatusAdd(Bridge.powerDefaultExecutor(),listener),
    "addThermalStatusListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalStatusChangedListener)":(executor,listener)=>Bridge.powerStatusAdd(executor,listener),
    "removeThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)":listener=>Bridge.powerStatusRemove(listener),
    "addThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)":listener=>Bridge.powerHeadroomAdd(Bridge.powerDefaultExecutor(),listener),
    "addThermalHeadroomListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalHeadroomChangedListener)":(executor,listener)=>Bridge.powerHeadroomAdd(executor,listener),
    "removeThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)":listener=>Bridge.powerHeadroomRemove(listener),
  };
  const installed=new Set<string>();
  for(const declaration of Manager.class.getDeclaredMethods()){
    if((declaration.getModifiers()&8)!==0)continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+signature.join(",")+")";
    if(installed.has(key))continue;const method=Manager[name].overload(...signature);
    method.implementation=function(this:any,...args:any[]){
      if(!Bridge.ownsPowerManager(this))return method.call(this,...args);
      if(key==="getThermalHeadroomThresholds()")return Bridge.powerThresholds();
      if(queries.has(key))return method.call(Bridge.powerPlatform(),...args);
      if(Object.hasOwn(routes,key))return routes[key]!(...args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("POWER_CAPABILITY_UNSUPPORTED:"+key);
    };installed.add(key);
  }
  for(const key of [...queries,...Object.keys(routes)])if(!installed.has(key))throw new Error("POWER_SIGNATURE_MISSING:"+key);
}
