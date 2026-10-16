package dev.tripoder.hooklab;

import android.os.Bundle;
import org.json.*;
import java.util.*;

/** UI snapshot only; mutation admission always rechecks the authority. */
final class WorkbenchCatalog {
    final List<String> spaces=new ArrayList<>();final List<JSONObject> installs=new ArrayList<>();
    final Map<String,JSONObject> bindings=new LinkedHashMap<>();
    WorkbenchCatalog(Bundle bundle)throws Exception{
        JSONArray values=new JSONArray(bundle.getString("spaces"));for(int i=0;i<values.length();i++)spaces.add(values.getString(i));
        values=new JSONArray(bundle.getString("installs"));for(int i=0;i<values.length();i++)installs.add(values.getJSONObject(i));
        values=new JSONArray(bundle.getString("projects"));for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);bindings.put(value.getString("project"),value);}
    }
    List<JSONObject> committed(){List<JSONObject> out=new ArrayList<>();for(JSONObject value:installs)if("COMMITTED".equals(value.optString("state")))out.add(value);return out;}
    List<String> applications(){Set<String> packages=new TreeSet<>();for(JSONObject value:installs)packages.add(value.optString("package"));return new ArrayList<>(packages);}
    List<JSONObject> installations(String pkg){List<JSONObject> out=new ArrayList<>();for(JSONObject value:committed())if(pkg.equals(value.optString("package")))out.add(value);return out;}
    List<JSONObject> projects(List<JSONObject> projects,String pkg){List<JSONObject> out=new ArrayList<>();for(JSONObject project:projects){JSONObject ref=bindings.get(project.optString("id"));if(ref!=null&&pkg.equals(ref.optString("package")))out.add(project);}return out;}
    static String label(JSONObject install){return install.optString("package")+"\n空间："+install.optString("space")+" · 版本 "+install.optLong("version");}
}
