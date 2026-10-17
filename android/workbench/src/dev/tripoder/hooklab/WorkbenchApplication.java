package dev.tripoder.hooklab;

/** One Application class for every process of the merged package. Workbench state exists only in the main (UI) process;
 *  :authority and guest :slot processes keep plain runtime behavior. */
public final class WorkbenchApplication extends dev.tripoder.runtime.RuntimeApplication {
    EngineConnection engine;
    WorkbenchOperations operations;WorkbenchImports imports;WorkbenchProjects projects;
    final WorkbenchHooks hooks=new WorkbenchHooks();
    WorkbenchAgentControl agents;
    @Override public void onCreate(){
        super.onCreate();
        if(!getPackageName().equals(getProcessName()))return;
        engine=new EngineConnection(this);operations=new WorkbenchOperations(this,engine);imports=new WorkbenchImports(this,operations);projects=new WorkbenchProjects(this);agents=new WorkbenchAgentControl(this);
    }
}
