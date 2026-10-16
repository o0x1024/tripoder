import Java from "frida-java-bridge";

export function installNotifications(Bridge: any) {
  const Manager = (Java.classFactory as any).use("android.app.NotificationManager", { members: "methods" });
  const construct = Manager.$new.overload("android.content.Context", "java.time.InstantSource");
  Bridge.createNotificationManager.implementation = (host: any) => construct.call(Manager, host, Java.use("java.time.InstantSource").system());
  const Channel = Java.use("android.app.NotificationChannel"), Group = Java.use("android.app.NotificationChannelGroup");
  const setId = Channel.setId.overload("java.lang.String");
  Bridge.notificationChannelIdentity.implementation = (value: any,id: any,group: any) => { setId.call(value,id);value.setGroup(group); };
  Bridge.notificationGroupIdentity.implementation = (value: any,id: any,channels: any) => { Java.cast(value,Group).mId.value=id;Java.cast(value,Group).mChannels.value=channels; };
  const singleton=(value: any) => { const list=Java.use("java.util.ArrayList").$new();list.add(value);return list; };
  const queries=new Set(["areNotificationsEnabled()","areNotificationsPaused()","getImportance()","getCurrentInterruptionFilter()","shouldHideSilentStatusBarIcons()"]);
  const routes:Record<string,(...args:any[])=>any>={
    "cancel(int)":id=>Bridge.notificationCancel(null,id),
    "cancel(java.lang.String,int)":(tag,id)=>Bridge.notificationCancel(tag,id),
    "cancelAll()":()=>Bridge.notificationCancelAll(),
    "createNotificationChannel(android.app.NotificationChannel)":v=>Bridge.notificationCreateChannels(singleton(v)),
    "createNotificationChannels(java.util.List)":v=>Bridge.notificationCreateChannels(v),
    "createNotificationChannelGroup(android.app.NotificationChannelGroup)":v=>Bridge.notificationCreateGroups(singleton(v)),
    "createNotificationChannelGroups(java.util.List)":v=>Bridge.notificationCreateGroups(v),
    "getNotificationChannel(java.lang.String)":id=>Bridge.notificationChannel(id,null),
    "getNotificationChannel(java.lang.String,java.lang.String)":(id,conversation)=>Bridge.notificationChannel(id,conversation),
    "getNotificationChannels()":()=>Bridge.notificationChannels(),
    "getNotificationChannelGroup(java.lang.String)":id=>Bridge.notificationGroup(id),
    "getNotificationChannelGroups()":()=>Bridge.notificationGroups(),
    "deleteNotificationChannel(java.lang.String)":id=>Bridge.notificationDeleteChannel(id),
    "deleteNotificationChannelGroup(java.lang.String)":id=>Bridge.notificationDeleteGroup(id),
  };
  const required=new Set([...queries,...Object.keys(routes)]),installed=new Set<string>();
  for(const declaration of Manager.class.getDeclaredMethods()) {
    if((declaration.getModifiers()&8)!==0)continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(type:any)=>String(type.getName())),key=name+"("+signature.join(",")+")";
    if(installed.has(key))continue;const method=Manager[name].overload(...signature);
    method.implementation=function(this:any,...args:any[]) {
      if(!Bridge.ownsNotificationManager(this))return method.call(this,...args);
      if(queries.has(key))return method.call(Bridge.notificationPlatform(),...args);
      if(Object.hasOwn(routes,key))return routes[key]!(...args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("NOTIFICATION_CAPABILITY_UNSUPPORTED:"+key);
    };installed.add(key);
  }
  for(const key of required)if(!installed.has(key))throw new Error("NOTIFICATION_SIGNATURE_MISSING:"+key);
}
