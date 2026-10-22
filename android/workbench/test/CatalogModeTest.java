package dev.tripoder.hooklab;

import android.os.Bundle;
import org.json.*;

public final class CatalogModeTest {
    private static int checks;
    private static JSONObject install(String space,String generation,String state)throws Exception{return new JSONObject().put("space",space).put("package","owned.app").put("generation",generation).put("state",state);}
    private static JSONObject target(String space,String generation,String state,String mode)throws Exception{return install(space,generation,state).put("project","single-owner").put("mode",mode);}
    private static WorkbenchCatalog catalog(JSONArray installs,JSONArray targets)throws Exception{
        Bundle b=new Bundle();b.putString("spaces","[\"a\",\"b\"]");b.putString("installs",installs.toString());b.putString("projects",targets.toString());
        b.putString("applications","[{\"package\":\"owned.app\",\"project\":\"single-owner\",\"selectedSpace\":\"a\"}]");return new WorkbenchCatalog(b);
    }
    private static void equal(String expected,String actual,String name){if(!expected.equals(actual))throw new AssertionError(name+": "+actual);checks++;}
    public static void main(String[] args)throws Exception{
        JSONArray installs=new JSONArray().put(install("a","g1","COMMITTED")).put(install("b","g2","COMMITTED"));
        JSONArray targets=new JSONArray().put(target("a","g1","COMMITTED","thin")).put(target("b","g2","COMMITTED","thick"));
        WorkbenchCatalog both=catalog(installs,targets);
        equal("THIN / THICK",both.modeLabel("owned.app",""),"all spaces retain both bindings despite one project owner");
        equal("THIN",both.modeLabel("owned.app","a"),"space A mode");equal("THICK",both.modeLabel("owned.app","b"),"space B mode");
        equal("",both.modeLabel("owned.app","missing"),"no visible installation");equal("",both.modeLabel("other.app",""),"unrelated package");
        equal("THIN",catalog(installs,new JSONArray()).modeLabel("owned.app",""),"unbound install uses runtime default");
        equal("THIN",catalog(installs,new JSONArray().put(target("a","old","COMMITTED","thick"))).modeLabel("owned.app","a"),"stale generation never supplies a mode");
        equal("THIN",catalog(installs,new JSONArray().put(target("a","g1","STALE","thick"))).modeLabel("owned.app","a"),"uncommitted binding ignored");
        equal("未知模式",catalog(installs,new JSONArray().put(target("a","g1","COMMITTED","invalid"))).modeLabel("owned.app","a"),"unknown mode not mislabeled");
        equal("",catalog(new JSONArray(),targets).modeLabel("owned.app",""),"retained uninstalled project has no mode badge");
        equal("",catalog(new JSONArray().put(install("a","g1","CORRUPT")),targets).modeLabel("owned.app",""),"corrupt install has no runnable mode");
        equal("THIN",catalog(installs,new JSONArray().put(target("a","g1","COMMITTED","thin")).put(target("b","g2","COMMITTED","thin"))).modeLabel("owned.app",""),"matching modes collapse into one badge");
        System.out.println("CatalogModeTest: "+checks+" checks passed");
    }
}
