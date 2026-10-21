package dev.tripoder.hooklab;

import android.content.Context;
import java.io.File;
import java.nio.file.*;
import org.json.*;

/** Host filesystem acceptance for the actual source store, independent of a device. */
public final class SingleProjectSourceTest {
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("tripoder-single-project-source-").toFile();WorkbenchProjects store=new WorkbenchProjects(new Context(root));
        String first=store.create("first"),second=store.create("second"),orphan=store.create("unbound");
        store.script(first,"first source");store.script(second,"second source");store.script(orphan,"unbound source");
        store.selectors(second,"[{\"id\":\"old\"}]");
        JSONArray ownership=new JSONArray().put(new JSONObject().put("project",first).put("package","owned.app").put("legacyProjects",new JSONArray().put(second)));
        store.reconcile(ownership);
        check(store.all().size()==1,"one active source project");check(store.script(first).equals("first source"),"canonical source preserved");
        check(store.get(first).getBoolean("migrationPending"),"different source requires a version decision");
        check(store.archived(null).size()==2,"duplicate and orphan source both archived");check(store.archivedScript(second).equals("second source"),"duplicate source recoverable");check(store.archivedScript(orphan).equals("unbound source"),"unbound source recoverable");
        store.reconcile(ownership);check(store.all().size()==1&&store.archived(null).size()==2,"repeated recovery does not duplicate or overwrite");
        store.restore(first,second);store.acceptMigration(first);check(store.script(first).equals("second source"),"selected version restored");check(new JSONArray(store.selectors(first)).getJSONObject(0).getString("id").equals("old"),"selectors follow selected version");
        check(store.archived(null).size()==3,"current source backed up before restoration");
        boolean stale=false;try{store.script(first,"lost update","first source");}catch(java.io.IOException expected){stale=true;}check(stale&&store.script(first).equals("second source"),"stale editor cannot overwrite external edit");
        store.script(first,"new source","second source");store.reconcile(ownership);check(store.script(first).equals("new source"),"reimport and additional spaces keep the source");check(!store.get(first).getBoolean("migrationPending"),"accepted migration remains accepted");
        store.reconcile(new JSONArray());check(store.script(first).equals("new source")&&store.all().size()==1,"an older catalog cannot archive an established application source");
        boolean limit=false;try{store.script(first,"中".repeat(22000));}catch(java.io.IOException expected){limit=true;}check(limit&&store.script(first).equals("new source"),"UTF-8 byte limit checked before write");
        File outside=Files.createTempFile("tripoder-outside-",".ts").toFile();Files.writeString(outside.toPath(),"external source");File script=new File(root,"projects/"+first+"/scripts/main.ts");Files.delete(script.toPath());Files.createSymbolicLink(script.toPath(),outside.toPath());
        boolean symlink=false;try{store.script(first,"overwrite");}catch(java.io.IOException expected){symlink=true;}check(symlink&&Files.readString(outside.toPath()).equals("external source"),"source never follows symlinks");
        store.delete(first);check(Files.readString(outside.toPath()).equals("external source"),"deletion never follows symlinks");
        store.reconcile(ownership);check(store.all().isEmpty(),"an older catalog snapshot cannot recreate a deleted project");
        System.out.println("SingleProjectSourceTest: 18 checks passed");
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
}
