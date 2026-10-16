import Java from "frida-java-bridge";

export function installWifiP2p(Bridge:any){
  const Manager=Java.use("android.net.wifi.p2p.WifiP2pManager"),Context=Java.use("android.content.Context");
  const construct=Manager.$new.overload("android.net.wifi.p2p.IWifiP2pManager");
  Bridge.createWifiP2pManager.implementation=(host:any)=>{
    if(host===null)return construct.call(Manager,null);
    const original=Java.cast(Java.cast(host,Context).getSystemService("wifip2p"),Manager);
    if(original===null)throw Java.use("java.lang.IllegalStateException").$new("PLATFORM_WIFI_P2P_UNAVAILABLE");
    return construct.call(Manager,original.mService.value);
  };
  const queries=new Set(["isChannelConstrainedDiscoverySupported()","isGroupClientRemovalSupported()","isGroupOwnerIPv6LinkLocalAddressProvided()","isPccModeSupported()","isSetVendorElementsSupported()","isWiFiDirectR2Supported()"]);
  const initialize="initialize(android.content.Context,android.os.Looper,android.net.wifi.p2p.WifiP2pManager$ChannelListener)",installed=new Set<string>();
  for(const declaration of Manager.class.getDeclaredMethods()){
    if((declaration.getModifiers()&8)!==0)continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+signature.join(",")+")";
    if(installed.has(key))continue;const method=Manager[name].overload(...signature);
    method.implementation=function(this:any,...args:any[]){
      if(!Bridge.ownsWifiP2pManager(this))return method.call(this,...args);
      if(key===initialize)return Bridge.wifiP2pInitialize(...args);
      if(queries.has(key))return method.call(Bridge.wifiP2pPlatform(),...args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("WIFI_P2P_CAPABILITY_UNSUPPORTED:"+key);
    };installed.add(key);
  }
  for(const key of [...queries,initialize])if(!installed.has(key))throw new Error("WIFI_P2P_SIGNATURE_MISSING:"+key);
  const Channel=Java.use("android.net.wifi.p2p.WifiP2pManager$Channel"),close=Channel.close.overload();
  close.implementation=function(this:any){
    if(!Bridge.ownsWifiP2pChannel(this))return close.call(this);
    Bridge.wifiP2pBeginClose(this);
    let confirmed=false;
    try{const result=close.call(this);confirmed=true;return result;}
    finally{Bridge.wifiP2pEndClose(this,confirmed);}
  };
}
