import Java from "frida-java-bridge";

export function installNetworkStats(Bridge: any) {
  const Manager = Java.use("android.app.usage.NetworkStatsManager");
  const construct = Manager.$new.overload("android.content.Context", "android.net.INetworkStatsService");
  Bridge.createNetworkStatsManager.implementation = () => construct.call(Manager,null,null);
  const installed = new Set<string>();
  for (const declaration of Manager.class.getDeclaredMethods()) {
    if ((declaration.getModifiers() & 8)!==0) continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+signature.join(",")+")";
    if(installed.has(key))continue;const method=Manager[name].overload(...signature);
    method.implementation=function(this:any,...args:any[]){
      if(!Bridge.ownsNetworkStatsManager(this))return method.call(this,...args);
      throw Java.use("java.lang.SecurityException").$new("NETWORK_HISTORY_CAPABILITY_NOT_GRANTED:"+key);
    };installed.add(key);
  }
  if(!installed.has("querySummary(int,java.lang.String,long,long)"))throw new Error("NETWORK_STATS_SUMMARY_SIGNATURE_MISSING");
  const Traffic=Java.use("android.net.TrafficStats"),seen=new Set<string>();
  const uid=new Set(["getUidRxBytes(int)","getUidTxBytes(int)","getUidRxPackets(int)","getUidTxPackets(int)","getUidStats(int,int)"]);
  const device=new Set(["getMobileRxBytes()","getMobileTxBytes()","getMobileRxPackets()","getMobileTxPackets()","getTotalRxBytes()","getTotalTxBytes()","getTotalRxPackets()","getTotalTxPackets()","getRxBytes(java.lang.String)","getTxBytes(java.lang.String)","getRxPackets(java.lang.String)","getTxPackets(java.lang.String)"]);
  for(const declaration of Traffic.class.getDeclaredMethods()){
    // Preserve the platform's private computation helpers and original native execution.
    if((declaration.getModifiers()&9)!==9)continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+signature.join(",")+")";
    if(seen.has(key))continue;const method=Traffic[name].overload(...signature);
    method.implementation=function(this:any,...args:any[]){
      if(key==="getUidStats(int,int)"||key==="getIfaceStats(java.lang.String,int)")Bridge.trafficKind(args[1]);
      if(key==="getTotalStats(int)")Bridge.trafficKind(args[0]);
      if(uid.has(key)){Bridge.trafficUid(args[0]);return method.call(Traffic,...args);}
      if(device.has(key)||key==="getTotalStats(int)"||key==="getIfaceStats(java.lang.String,int)")return method.call(Traffic,...args);
      throw Java.use("java.lang.SecurityException").$new("TRAFFIC_CAPABILITY_NOT_GRANTED:"+key);
    };seen.add(key);
  }
  for(const key of [...uid,...device,"getTotalStats(int)","getIfaceStats(java.lang.String,int)"])if(!seen.has(key))throw new Error("TRAFFIC_SIGNATURE_MISSING:"+key);
}
