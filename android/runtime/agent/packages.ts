import Java from "frida-java-bridge";

/** A process has exactly one package world. No stack-based caller classification. */
export function installPackages() {
  const Bridge = Java.use("dev.tripoder.runtime.RuntimePackageBridge");
  const use = (name: string) => (Java.classFactory as any).use(name, { members: "methods" });
  const Manager = use("android.app.ApplicationPackageManager"), Base = use("android.content.pm.PackageManager");
  const Thread = use("android.app.ActivityThread"), Context = use("android.app.ContextImpl");
  const Interface = use("android.content.pm.IPackageManager"), ServiceManager = use("android.os.ServiceManager");
  const physical = Java.retain(Thread.getPackageManager());
  const field = (type: any, name: string) => { const value=type.class.getDeclaredField(name);value.setAccessible(true);return value; };
  const endpoint = field(Manager, "mPM"), globalEndpoint = field(Thread, "sPackageManager"), services = field(ServiceManager, "sCache");
  const construct = Manager.$new.overload("android.app.ContextImpl", "android.content.pm.IPackageManager");
  const Wrapper=Java.use("android.content.ContextWrapper"), contextCache=field(Context,"mPackageManager");
  const guarded=(action:()=>any)=>{try{return action();}catch(error:any){if(error?.$h)throw error;throw Java.use("java.lang.IllegalStateException").$new("PACKAGE_ADAPTER_FAILURE:"+String(error?.stack??error));}};
  const unwrap = (value: any) => { let base=value;while(!Context.class.isInstance(base)){if(!Wrapper.class.isInstance(base))throw new Error("PACKAGE_CONTEXT_IMPL_REQUIRED");base=Java.cast(base,Wrapper).getBaseContext();}return Java.cast(base,Context); };
  const current = (pm: any) => { const value=endpoint.get(pm);if(value===null)throw Java.use("java.lang.IllegalStateException").$new("PACKAGE_ENDPOINT_MISSING");return Java.cast(value,Interface); };
  Bridge.construct.implementation = (context: any, root: any) => guarded(()=>construct.call(Manager,unwrap(context),Java.cast(root,Interface)));
  Bridge.currentEndpoint.implementation = () => Thread.getPackageManager();
  Bridge.physicalPackageInfo.implementation = (pkg: any,flags: any,user: number) => {Bridge.requireInfrastructure("package",pkg,null,user);Bridge.infrastructureFlags("package",flags);return physical.getPackageInfo(pkg,flags,user);};
  Bridge.physicalApplicationInfo.implementation = (pkg: any,flags: any,user: number) => {Bridge.requireInfrastructure("application",pkg,null,user);Bridge.infrastructureFlags("application",flags);return physical.getApplicationInfo(pkg,flags,user);};
  Bridge.physicalComponent.implementation = (kind: any,name: any,flags: any,user: number) => {const method=String(kind);if(name===null)throw Java.use("java.lang.IllegalArgumentException").$new("PHYSICAL_COMPONENT_REQUIRED");Bridge.requireInfrastructure(method,name.getPackageName(),name,user);Bridge.infrastructureFlags(method,flags);return physical[method](name,flags,user);};
  Bridge.physicalFeature.implementation = (name: any,version: number) => physical.hasSystemFeature(name,version);
  Bridge.physicalSdkSandboxPackageName.implementation=()=>physical.getSdkSandboxPackageName();
  Bridge.activate.implementation = (root: any, host: any) => guarded(()=>{
    Bridge.beginActivation(root,host,Thread.currentActivityThread().getSystemContext());
    const original=host.getPackageManager();
    Base.disableApplicationInfoCache();Base.disablePackageInfoCache();Manager.disableGetPackagesForUidCache();
    endpoint.set(original,root);Bridge.registerInfrastructureFacade(original);
    Java.cast(services.get(null),Java.use("java.util.Map")).put(Java.use("java.lang.String").$new("package"),Java.cast(root,Java.use("android.os.IInterface")).asBinder());
    globalEndpoint.set(null,root);
    if(!globalEndpoint.get(null).equals(root)||!endpoint.get(original).equals(root))throw new Error("PACKAGE_WORLD_INSTALL_UNCONFIRMED");
    if(!Java.cast(Thread.getPackageManager(),Java.use("java.lang.Object")).equals(root))throw new Error("PACKAGE_WORLD_GETTER_UNCONFIRMED");
  });
  const getManager=Context.getPackageManager.overload();
  getManager.implementation=function(this:any){return guarded(()=>{if(!Bridge.active())return getManager.call(this);let pm=contextCache.get(this);if(pm!==null&&Bridge.registered(Java.cast(pm,Base)))return pm;pm=Bridge.contextManager(this);contextCache.set(this,pm);return pm;});};
  const getters=new Set(["getService","getServiceOrThrow","checkService","waitForService","waitForDeclaredService"]);
  const installedGetters=new Set<string>();
  for(const declaration of ServiceManager.class.getDeclaredMethods()) {
    if((declaration.getModifiers()&8)===0)continue;
    const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(t:any)=>String(t.getName()));
    if(signature[0]!=="java.lang.String")continue;
    const original=ServiceManager[name].overload(...signature);
    original.implementation=function(this:any,...args:any[]){return guarded(()=>{
      if(!Bridge.active()||String(args[0])!=="package")return original.call(this,...args);
      if(getters.has(name)&&signature.length===1)return Bridge.localBinder();
      throw Java.use("java.lang.UnsupportedOperationException").$new("PACKAGE_SERVICE_ENTRY_UNSUPPORTED:"+name);
    });};
    if((getters.has(name)||name==="rawGetService")&&signature.length===1)installedGetters.add(name);
  }
  for(const name of [...getters,"rawGetService"])if(!installedGetters.has(name))throw new Error("PACKAGE_SERVICE_SIGNATURE_MISSING:"+name);
  const user=Java.use("android.os.Process").myUserHandle().getIdentifier();
  Bridge.userId.implementation=()=>user;
  const value=(flags:any)=>{if(flags===null)throw Java.use("java.lang.IllegalArgumentException").$new("PACKAGE_FLAGS_REQUIRED");return flags.getValue();};
  const found=(item:any,key:any)=>{if(item===null)throw Java.use("android.content.pm.PackageManager$NameNotFoundException").$new(String(key));return item;};
  const routes:Record<string,(pm:any,...args:any[])=>any>={};
  for(const [name,selector,flagsType] of [
    ["getPackageInfo","java.lang.String","PackageInfoFlags"],
    ["getApplicationInfo","java.lang.String","ApplicationInfoFlags"],
    ["getServiceInfo","android.content.ComponentName","ComponentInfoFlags"],
    ["getProviderInfo","android.content.ComponentName","ComponentInfoFlags"],
    ["getActivityInfo","android.content.ComponentName","ComponentInfoFlags"],
    ["resolveContentProvider","java.lang.String","ComponentInfoFlags"],
  ]) {
    const query=(pm:any,key:any,flags:any)=>{const answer=current(pm)[name!](key,flags,user);return name==="resolveContentProvider"?answer:found(answer,key);};
    routes[`${name}(${selector},int)`]=(pm,key,flags)=>query(pm,key,flags>>>0);
    routes[`${name}(${selector},android.content.pm.PackageManager$${flagsType})`]=(pm,key,flags)=>query(pm,key,value(flags));
  }
  routes["getPackageInfo(android.content.pm.VersionedPackage,int)"]=(pm,pkg,flags)=>found(current(pm).getPackageInfoVersioned(pkg,flags>>>0,user),pkg);
  routes["getPackageInfo(android.content.pm.VersionedPackage,android.content.pm.PackageManager$PackageInfoFlags)"]=(pm,pkg,flags)=>found(current(pm).getPackageInfoVersioned(pkg,value(flags),user),pkg);
  routes["hasSystemFeature(java.lang.String)"]=(pm,name)=>current(pm).hasSystemFeature(name,0);
  routes["hasSystemFeature(java.lang.String,int)"]=(pm,name,version)=>current(pm).hasSystemFeature(name,version);
  routes["isInstantApp()"]=(pm)=>current(pm).isInstantApp(Bridge.packageName(),user);
  routes["isInstantApp(java.lang.String)"]=(pm,name)=>current(pm).isInstantApp(name,user);
  routes["getPackagesForUid(int)"]=(pm,uid)=>current(pm).getPackagesForUid(uid);
  routes["getSdkSandboxPackageName()"]=(pm)=>current(pm).getSdkSandboxPackageName();
  routes["getResourcesForApplication(java.lang.String)"]=(pm,pkg)=>Bridge.resources(pm,pkg,null);
  routes["getResourcesForApplication(android.content.pm.ApplicationInfo)"]=(pm,info)=>{if(info===null)throw Java.use("java.lang.IllegalArgumentException").$new("APPLICATION_INFO_REQUIRED");return Bridge.resources(pm,info.packageName.value,info);};
  routes["getXml(java.lang.String,int,android.content.pm.ApplicationInfo)"]=(pm,pkg,id,info)=>Bridge.xml(pm,pkg,id,info);
  routes["getText(java.lang.String,int,android.content.pm.ApplicationInfo)"]=(pm,pkg,id,info)=>Bridge.text(pm,pkg,id,info);
  routes["getDrawable(java.lang.String,int,android.content.pm.ApplicationInfo)"]=(pm,pkg,id,info)=>Bridge.drawable(pm,pkg,id,info);
  routes["getApplicationLabel(android.content.pm.ApplicationInfo)"]=(pm,info)=>{if(info===null)throw Java.use("java.lang.IllegalArgumentException").$new("APPLICATION_INFO_REQUIRED");Bridge.resources(pm,info.packageName.value,info);return Java.cast(info,Java.use("android.content.pm.PackageItemInfo")).loadLabel(pm);};
  const installed=new Set<string>();
  for(let type=Manager.class;type!==null&&String(type.getName())!=="java.lang.Object";type=type.getSuperclass()) {
    const owner=use(String(type.getName()));
    for(const declaration of type.getDeclaredMethods()) {
      if((declaration.getModifiers()&(8|1024))!==0)continue;
      const name=String(declaration.getName()),signature=Array.from(declaration.getParameterTypes(),(t:any)=>String(t.getName())),key=name+"("+signature.join(",")+")";
      const method=owner[name].overload(...signature);
      method.implementation=function(this:any,...args:any[]){return guarded(()=>{if(!Bridge.active())return method.call(this,...args);if(Object.hasOwn(routes,key))return routes[key]!(this,...args);throw Java.use("java.lang.UnsupportedOperationException").$new("PACKAGE_MANAGER_OPERATION_UNSUPPORTED:"+key);});};
      installed.add(key);
    }
  }
  for(const key of Object.keys(routes))if(!installed.has(key))throw new Error("PACKAGE_MANAGER_SIGNATURE_MISSING:"+key);
  if(!installed.has("getHoldLockToken()"))throw new Error("PACKAGE_MANAGER_SIGNATURE_MISSING:getHoldLockToken()");
}
