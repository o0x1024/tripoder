package dev.tripoder.hooklab;

/** One Application class for every process of the merged package. Workbench state exists only in the main (UI) process;
 *  :authority and guest :slot processes keep plain runtime behavior. */
public final class WorkbenchApplication extends dev.tripoder.runtime.RuntimeApplication {
    EngineConnection engine;
    WorkbenchOperations operations;WorkbenchImports imports;WorkbenchProjects projects;
    WorkbenchPresentations presentations;WorkbenchSpaces spaces;
    final WorkbenchHooks hooks=new WorkbenchHooks();
    WorkbenchAgentControl agents;
    void controlChanged(org.json.JSONObject target){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{if(target!=null)hooks.invalidate(target);operations.externalChanged();});}
    @Override public void onCreate(){
        super.onCreate();
        if(!getPackageName().equals(getProcessName()))return;
        presentations=new WorkbenchPresentations(this);spaces=new WorkbenchSpaces(this);
        engine=new EngineConnection(this);projects=new WorkbenchProjects(this);operations=new WorkbenchOperations(this,engine);imports=new WorkbenchImports(this,operations);agents=new WorkbenchAgentControl(this);
        engine.observe(()->{if(engine.ready()&&!agents.listening&&getSharedPreferences("agent-control",MODE_PRIVATE).getBoolean("enabled",true))
            try{startForegroundService(new android.content.Intent(this,AgentControlService.class));}catch(Exception error){agents.message="电脑连接入口未启动："+UiText.error(error);}});
    }
}
