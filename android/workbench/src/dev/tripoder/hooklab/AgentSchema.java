package dev.tripoder.hooklab;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Validation of the single generated MCP contract before any device dispatch. */
final class AgentSchema {
    static void validate(Object value,JSONObject schema,String path)throws Exception{
        if(schema.has("anyOf")){
            JSONArray branches=schema.getJSONArray("anyOf");
            for(int i=0;i<branches.length();i++)try{validate(value,branches.getJSONObject(i),path);return;}catch(IllegalArgumentException mismatch){}
            throw invalid(path);
        }
        if(schema.has("enum")){boolean match=false;JSONArray choices=schema.getJSONArray("enum");for(int i=0;i<choices.length();i++)match|=choices.get(i).equals(value);if(!match)throw invalid(path);}
        switch(schema.optString("type")){
            case "string":if(!(value instanceof String text)||text.length()<schema.optInt("minLength",0)||text.length()>schema.optInt("maxLength",32000)||schema.has("pattern")&&!Pattern.compile(schema.getString("pattern")).matcher(text).find())throw invalid(path);break;
            case "null":if(value!=JSONObject.NULL)throw invalid(path);break;
            case "number":case "integer":if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||"integer".equals(schema.getString("type"))&&(number.doubleValue()!=Math.rint(number.doubleValue())||Math.abs(number.doubleValue())>9007199254740991d)||number.doubleValue()<schema.optDouble("minimum",-Double.MAX_VALUE)||number.doubleValue()>schema.optDouble("maximum",Double.MAX_VALUE))throw invalid(path);break;
            case "boolean":if(!(value instanceof Boolean))throw invalid(path);break;
            case "object":{
                if(!(value instanceof JSONObject object))throw invalid(path);JSONObject props=schema.optJSONObject("properties");if(props==null)props=new JSONObject();JSONArray required=schema.optJSONArray("required");
                if(object.length()>schema.optInt("maxProperties",Integer.MAX_VALUE))throw invalid(path+" too many properties");
                if(required!=null)for(int i=0;i<required.length();i++)if(!object.has(required.getString(i)))throw invalid(path+" missing "+required.getString(i));
                for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();if(schema.has("propertyNames"))validate(key,schema.getJSONObject("propertyNames"),path+" key");if(props.has(key))validate(object.get(key),props.getJSONObject(key),path+"."+key);else if(schema.opt("additionalProperties") instanceof JSONObject additional)validate(object.get(key),additional,path+"."+key);else if(Boolean.FALSE.equals(schema.opt("additionalProperties")))throw invalid(path+" unsupported "+key);}break;
            }
            case "array":{
                if(!(value instanceof JSONArray array)||array.length()<schema.optInt("minItems",0)||array.length()>schema.optInt("maxItems",Integer.MAX_VALUE))throw invalid(path);
                for(int i=0;i<array.length();i++)validate(array.get(i),schema.getJSONObject("items"),path+"["+i+"]");break;
            }
        }
    }
    static void selectors(JSONObject args)throws Exception{
        JSONObject invocation=args.optJSONObject("invocation");
        if(invocation!=null){
            JSONArray types=invocation.getJSONArray("argumentTypes"),values=invocation.getJSONArray("arguments");
            if(types.length()!=values.length())throw invalid("invocation.arguments count must match argumentTypes");
            for(int i=0;i<types.length();i++)if(!javaArgument(types.getString(i),values.get(i)))throw invalid("invocation.arguments["+i+"] invalid "+types.getString(i)+" value");
        }
        JSONArray list=args.optJSONArray("selectors");JSONObject config=args.optJSONObject("configuration");if(list==null&&config!=null){JSONObject hook=config.optJSONObject("startupHook");if(hook!=null)list=hook.optJSONArray("selectors");}
        if(list!=null){Set<String> ids=new HashSet<>();for(int i=0;i<list.length();i++)if(!ids.add(list.getJSONObject(i).getString("id")))throw invalid("selectors duplicate id");}
    }
    private static boolean javaArgument(String type,Object value){
        return switch(type){
            case "java.lang.String"->value==JSONObject.NULL||value instanceof String;
            case "[B","byte[]"->value==JSONObject.NULL||value instanceof String text&&text.length()<=8192&&text.length()%2==0&&text.matches("[0-9a-fA-F]*");
            case "char"->value instanceof String text&&text.length()==1;
            case "boolean"->value instanceof Boolean;
            case "long"->{if(!(value instanceof String text)||!text.matches("-?[0-9]{1,20}"))yield false;java.math.BigInteger n=new java.math.BigInteger(text);yield n.bitLength()<=63;}
            case "float","double"->value instanceof Number number&&Double.isFinite(number.doubleValue())&&(!type.equals("float")||Float.isFinite(number.floatValue()));
            case "byte","short","int"->{double low=type.equals("byte")?-128:type.equals("short")?-32768:Integer.MIN_VALUE,high=type.equals("byte")?127:type.equals("short")?32767:Integer.MAX_VALUE;yield value instanceof Number number&&number.doubleValue()==Math.rint(number.doubleValue())&&number.doubleValue()>=low&&number.doubleValue()<=high;}
            default->false;
        };
    }
    private static IllegalArgumentException invalid(String path){return new IllegalArgumentException("INVALID_TOOL_ARGUMENTS:"+path);}
}
