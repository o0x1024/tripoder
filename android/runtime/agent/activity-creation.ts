import Java from "frida-java-bridge";

/** Admit at the real launch boundary; guest Instrumentation owns object construction. */
export function installActivityCreation(Bridge:any):void {
  const Creation=Java.use("dev.tripoder.runtime.RuntimeActivityCreation");
  const Instrumentation=Java.use("android.app.Instrumentation"),Thread=Java.use("android.app.ActivityThread"),Activity=Java.use("android.app.Activity");
  const signature=["java.lang.ClassLoader","java.lang.String","android.content.Intent"];
  const classes=Java.array("java.lang.Class",signature.map(name=>Java.use(name).class));
  const installed:any[]=[];
  const currentInstrumentation=()=>Thread.currentActivityThread().mInstrumentation.value;
  Bridge.instrumentationBound.implementation=(instrumentation:any)=>{
    const bound=Java.cast(instrumentation,Instrumentation).mThread.value;
    if(bound===null)return false;
    if(!bound.equals(Thread.currentActivityThread()))throw Java.use("java.lang.SecurityException").$new("INSTRUMENTATION_THREAD_MISMATCH");
    return true;
  };
  function selected(instrumentation:any):any {
    const declaration=instrumentation.getClass().getMethod("newActivity",classes).getDeclaringClass();
    for(const entry of installed)if(entry.declaration.equals(declaration))return entry;
    if(installed.length>=64)throw Java.use("java.lang.IllegalStateException").$new("INSTRUMENTATION_CLASS_LIMIT");
    const loader=declaration.getClassLoader(),factory=loader===null?Java.classFactory:Java.ClassFactory.get(loader);
    const type=factory.use(String(declaration.getName())),method=type.newActivity.overload(...signature);
    const entry={declaration:Java.retain(declaration),method,base:String(declaration.getName())==="android.app.Instrumentation"};installed.push(entry);
    method.implementation=function(this:any,loader:any,name:any,intent:any){
      const permit=Creation.current();
      if(permit===null)return method.call(this,loader,name,intent);
      if(permit.constructing())return entry.base?Creation.constructDefault(this,loader,name,intent):method.call(this,loader,name,intent);
      permit.requireArguments(name,intent);
      return construct(permit,this,entry);
    };
    return entry;
  }
  function construct(permit:any,instrumentation:any,entry:any):any {
    permit.enter();
    const result=entry.base?Creation.constructDefault(instrumentation,permit.loader(),permit.name(),permit.intent()):entry.method.call(instrumentation,permit.loader(),permit.name(),permit.intent());
    return permit.complete(result);
  }
  selected(Instrumentation.$new()); // The base-super boundary is mandatory even with a custom override.
  const initialize=Instrumentation.$init.overload();
  initialize.implementation=function(this:any){
    const result=initialize.call(this);
    // Enroll the runtime Class before the constructor returns to guest code. A
    // getContext override can replace mInstrumentation inside performLaunchActivity.
    // Selecting only at the launch prologue would miss that next receiver.
    selected(this);return result;
  };
  Bridge.constructWithCurrentInstrumentation.implementation=(permit:any)=>{const instrumentation=currentInstrumentation();return construct(permit,instrumentation,selected(instrumentation));};
  const launch=Thread.performLaunchActivity.overload("android.app.ActivityThread$ActivityClientRecord","android.content.Intent");
  launch.implementation=function(this:any,record:any,custom:any){
    if(!Creation.physical(record.intent.value))return launch.call(this,record,custom);
    const permit=Creation.begin(record.intent.value);
    try{
      // handleLaunchActivity finishes the actual token when performLaunchActivity returns null.
      if(permit.rejected())return null;
      permit.bindRecord(record,record.token.value);
      selected(currentInstrumentation());
      return launch.call(this,record,custom);
    }finally{permit.end();}
  };
  const attach=Activity.attach.overload("android.content.Context","android.app.ActivityThread","android.app.Instrumentation","android.os.IBinder","int","android.app.Application","android.content.Intent","android.content.pm.ActivityInfo","java.lang.CharSequence","android.app.Activity","java.lang.String","android.app.Activity$NonConfigurationInstances","android.content.res.Configuration","java.lang.String","com.android.internal.app.IVoiceInteractor","android.view.Window","android.view.ViewRootImpl$ActivityConfigCallback","android.os.IBinder","android.os.IBinder","android.os.IBinder");
  attach.implementation=function(this:any,...args:any[]){
    if(!Bridge.owns(this)){
      if(Creation.physical(args[6])&&!Creation.attachRejected(this,args[6]))throw Java.use("java.lang.SecurityException").$new("ACTIVITY_CREATION_NOT_ADMITTED");
      return attach.call(this,...args);
    }
    Creation.attaching(this,args[6],args[7],args[3]);
    args[0]=Bridge.context(args[0]);args[5]=Bridge.application();args[6]=Bridge.intent(this);args[7]=Bridge.activityInfo(this);
    const result=attach.call(this,...args);
    Bridge.attach(this);Bridge.attachedActivity(this);return result;
  };
  const theme=Java.use("android.content.pm.ActivityInfo").getThemeResource.overload();
  theme.implementation=function(this:any){const mapped=Creation.theme(this);return mapped===null?theme.call(this):mapped.intValue();};
}
