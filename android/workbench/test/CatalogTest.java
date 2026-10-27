package dev.tripoder.hooklab;

import android.os.Bundle;
import org.json.*;

public final class CatalogTest {
    private static int checks;
    private static JSONObject install(String space,String generation,String state)throws Exception{return new JSONObject().put("space",space).put("package","owned.app").put("generation",generation).put("state",state);}
    private static JSONObject target(String space,String generation,String state)throws Exception{return install(space,generation,state).put("project","single-owner");}
    private static WorkbenchCatalog catalog(JSONArray installs,JSONArray targets)throws Exception{
        Bundle b=new Bundle();b.putString("spaces","[\"a\",\"b\"]");b.putString("installs",installs.toString());b.putString("projects",targets.toString());
        b.putString("applications","[{\"package\":\"owned.app\",\"project\":\"single-owner\",\"selectedSpace\":\"a\"}]");return new WorkbenchCatalog(b);
    }
    private static void equal(String expected,String actual,String name){if(!expected.equals(actual))throw new AssertionError(name+": "+actual);checks++;}
    public static void main(String[] args)throws Exception{
        JSONArray installs=new JSONArray().put(install("a","g1","COMMITTED")).put(install("b","g2","COMMITTED"));
        JSONArray targets=new JSONArray().put(target("a","g1","COMMITTED")).put(target("b","g2","COMMITTED"));
        WorkbenchCatalog both=catalog(installs,targets);
        equal("g1",both.bindings.get("single-owner").getString("generation"),"selected space determines target");
        equal("single-owner",both.project("owned.app"),"one owner across spaces");
        equal("2",String.valueOf(both.installations("owned.app").size()),"both installations retained");
        equal("1",String.valueOf(both.applications("a").size()),"space filtering retains application");
        equal("0",String.valueOf(both.applications("missing").size()),"missing space is empty");
        equal("owned.app",catalog(new JSONArray(),targets).applications().get(0),"uninstalled project remains visible");
        equal("0",String.valueOf(catalog(new JSONArray().put(install("a","g1","CORRUPT")),targets).committed().size()),"corrupt installation cannot run");
        equal("",both.runState("owned.app",""),"stopped application has no running badge");
        System.out.println("CatalogTest: "+checks+" checks passed");
    }
}
