package dev.tripoder.hooklab;

/** One Application class for every process of the merged package. Workbench state exists only in the main (UI) process;
 *  :authority and guest :slot processes keep plain runtime behavior. */
public final class WorkbenchApplication extends dev.tripoder.runtime.RuntimeApplication {
    EngineConnection engine;
    WorkbenchOperations operations;WorkbenchImports imports;WorkbenchProjects projects;
    WorkbenchPresentations presentations;WorkbenchSpaces spaces;
    final WorkbenchHooks hooks=new WorkbenchHooks();
    WorkbenchAgentControl agents;
    @Override public void onCreate(){
        super.onCreate();
        if(!getPackageName().equals(getProcessName()))return;
        presentations=new WorkbenchPresentations(this);spaces=new WorkbenchSpaces(this);
        engine=new EngineConnection(this);operations=new WorkbenchOperations(this,engine);imports=new WorkbenchImports(this,operations);projects=new WorkbenchProjects(this);agents=new WorkbenchAgentControl(this);
        engine.observe(()->{if(engine.ready()&&!agents.listening&&getSharedPreferences("agent-control",MODE_PRIVATE).getBoolean("enabled",true))
            try{startForegroundService(new android.content.Intent(this,AgentControlService.class));}catch(Exception error){agents.message="电脑连接入口未启动："+UiText.error(error);}});
    }
}
