package dev.tripoder.hooklab;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Validation of the single generated MCP contract before any device dispatch. */
final class AgentSchema {
    static void validate(Object value,JSONObject schema,String path)throws Exception{
        if(schema.has("anyOf")){
            if(!(value instanceof JSONObject object))throw invalid(path);
            JSONArray branches=schema.getJSONArray("anyOf");
            for(int i=0;i<branches.length();i++){JSONObject branch=branches.getJSONObject(i);JSONArray kinds=branch.getJSONObject("properties").getJSONObject("kind").getJSONArray("enum");if(kinds.toString().contains("\""+object.optString("kind")+"\"")){validate(value,branch,path);return;}}
            throw invalid(path);
        }
        if(schema.has("enum")){boolean match=false;JSONArray choices=schema.getJSONArray("enum");for(int i=0;i<choices.length();i++)match|=choices.get(i).equals(value);if(!match)throw invalid(path);}
        switch(schema.optString("type")){
            case "string":if(!(value instanceof String text)||text.length()<schema.optInt("minLength",0)||text.length()>schema.optInt("maxLength",32000)||schema.has("pattern")&&!Pattern.compile(schema.getString("pattern")).matcher(text).find())throw invalid(path);break;
            case "integer":if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=Math.rint(number.doubleValue())||number.doubleValue()<schema.optDouble("minimum",-9007199254740991d)||number.doubleValue()>schema.optDouble("maximum",9007199254740991d))throw invalid(path);break;
            case "boolean":if(!(value instanceof Boolean))throw invalid(path);break;
            case "object":{
                if(!(value instanceof JSONObject object))throw invalid(path);JSONObject props=schema.optJSONObject("properties");if(props==null)props=new JSONObject();JSONArray required=schema.optJSONArray("required");
                if(required!=null)for(int i=0;i<required.length();i++)if(!object.has(required.getString(i)))throw invalid(path+" missing "+required.getString(i));
                for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();if(props.has(key))validate(object.get(key),props.getJSONObject(key),path+"."+key);else if(!schema.optBoolean("additionalProperties",true))throw invalid(path+" unsupported "+key);}break;
            }
            case "array":{
                if(!(value instanceof JSONArray array)||array.length()<schema.optInt("minItems",0)||array.length()>schema.optInt("maxItems",Integer.MAX_VALUE))throw invalid(path);
                for(int i=0;i<array.length();i++)validate(array.get(i),schema.getJSONObject("items"),path+"["+i+"]");break;
            }
        }
    }
    static void selectors(JSONObject args)throws Exception{
        JSONArray list=args.optJSONArray("selectors");JSONObject config=args.optJSONObject("configuration");if(list==null&&config!=null){JSONObject hook=config.optJSONObject("startupHook");if(hook!=null)list=hook.optJSONArray("selectors");}
        if(list!=null){Set<String> ids=new HashSet<>();for(int i=0;i<list.length();i++)if(!ids.add(list.getJSONObject(i).getString("id")))throw invalid("selectors duplicate id");}
    }
    private static IllegalArgumentException invalid(String path){return new IllegalArgumentException("INVALID_TOOL_ARGUMENTS:"+path);}
}
