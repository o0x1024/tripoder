package dev.tripoder.hooklab;

import android.os.Bundle;
import org.json.*;
import java.util.*;

/** UI snapshot only; mutation admission always rechecks the authority. */
final class WorkbenchCatalog {
    final List<String> spaces=new ArrayList<>();final List<JSONObject> installs=new ArrayList<>();
    final JSONArray applicationsMeta;
    final Map<String,JSONObject> bindings=new LinkedHashMap<>();
    WorkbenchCatalog(Bundle bundle)throws Exception{
        applicationsMeta=new JSONArray(bundle.getString("applications","[]"));
        JSONArray values=new JSONArray(bundle.getString("spaces"));for(int i=0;i<values.length();i++)spaces.add(values.getString(i));
        values=new JSONArray(bundle.getString("installs"));for(int i=0;i<values.length();i++)installs.add(values.getJSONObject(i));
        values=new JSONArray(bundle.getString("projects"));for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);String id=value.getString("project");JSONObject prior=bindings.get(id);String selected="";for(int j=0;j<applicationsMeta.length();j++)if(id.equals(applicationsMeta.getJSONObject(j).optString("project")))selected=applicationsMeta.getJSONObject(j).optString("selectedSpace");if(prior==null||selected.equals(value.optString("space")))bindings.put(id,value);}
    }
    String project(String pkg){for(int i=0;i<applicationsMeta.length();i++){JSONObject row=applicationsMeta.optJSONObject(i);if(pkg.equals(row.optString("package")))return row.optString("project");}return null;}
    void sync(WorkbenchApplication app)throws Exception{app.projects.reconcile(applicationsMeta);}
    List<JSONObject> committed(){List<JSONObject> out=new ArrayList<>();for(JSONObject value:installs)if("COMMITTED".equals(value.optString("state")))out.add(value);return out;}
    List<String> applications(){return applications(null);}
    List<String> applications(String space){Set<String> packages=new TreeSet<>();for(JSONObject value:installs)if(space==null||space.isEmpty()||space.equals(value.optString("space")))packages.add(value.optString("package"));if(space==null||space.isEmpty())for(int i=0;i<applicationsMeta.length();i++)packages.add(applicationsMeta.optJSONObject(i).optString("package"));return new ArrayList<>(packages);}
    List<JSONObject> installations(String pkg){List<JSONObject> out=new ArrayList<>();for(JSONObject value:committed())if(pkg.equals(value.optString("package")))out.add(value);return out;}
    List<JSONObject> projects(List<JSONObject> projects,String pkg){List<JSONObject> out=new ArrayList<>();for(JSONObject project:projects){JSONObject ref=bindings.get(project.optString("id"));if(ref!=null&&pkg.equals(ref.optString("package")))out.add(project);}return out;}
    /** Application name (once its presentation is known), space name and version, for choosing among installations. */
    static String label(WorkbenchApplication app,JSONObject install){return name(app,install)+"\n空间："+app.spaces.name(install.optString("space"))+" · 版本 "+install.optLong("version");}
    static String name(WorkbenchApplication app,JSONObject install){Bundle meta=app.presentations.get(install.optString("generation"));return meta==null?install.optString("package"):meta.getString("label");}
    String appName(WorkbenchApplication app,String pkg){for(JSONObject install:installations(pkg)){Bundle meta=app.presentations.get(install.optString("generation"));if(meta!=null)return meta.getString("label");}return pkg;}
}
