package dev.tripoder.hooklab;

public final class WorkbenchApplication extends android.app.Application {
    EngineConnection engine;
    WorkbenchOperations operations;WorkbenchImports imports;WorkbenchProjects projects;
    final WorkbenchHooks hooks=new WorkbenchHooks();
    WorkbenchAgentControl agents;
    @Override public void onCreate(){super.onCreate();engine=new EngineConnection(this);operations=new WorkbenchOperations(this,engine);imports=new WorkbenchImports(this,operations);projects=new WorkbenchProjects(this);agents=new WorkbenchAgentControl(this);}
}
