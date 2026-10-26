package dev.tripoder.hooklab;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Validation of the single generated MCP contract before any device dispatch. */
final class AgentSchema {
    static final class Invalid extends IllegalArgumentException {
        final JSONArray issues;
        Invalid(JSONArray issues){super("INVALID_TOOL_ARGUMENTS:"+issues);this.issues=issues;}
    }
    private static String child(String path,String key){return path+"/"+key.replace("~","~0").replace("/","~1");}
    private static Invalid issue(String path,String code,String expected){try{return new Invalid(new JSONArray().put(new JSONObject().put("path",path).put("code",code).put("expected",expected)));}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    static Invalid expectedObject(){return issue("","TYPE_MISMATCH","object");}
    static Invalid invalidAction(JSONArray choices){return issue("/action","INVALID_DISCRIMINATOR",choices.toString());}
    static void validate(Object value,JSONObject schema,String path)throws Exception{
        if(path.equals("arguments"))path="";
        if(schema.has("anyOf")){
            JSONArray branches=schema.getJSONArray("anyOf");
            if(value instanceof JSONObject object){
                outer:for(String key:List.of("kind","type","action"))if(object.has(key)){
                    JSONArray selected=new JSONArray(),choices=new JSONArray();boolean declaring=false;
                    for(int i=0;i<branches.length();i++){JSONObject props=branches.getJSONObject(i).optJSONObject("properties"),field=props==null?null:props.optJSONObject(key);JSONArray allowed=field==null?null:field.optJSONArray("enum");
                        if(allowed!=null&&allowed.length()==1){declaring=true;choices.put(allowed.get(0));if(allowed.get(0).equals(object.get(key)))selected.put(branches.get(i));}}
                    if(declaring){if(selected.length()==0)throw issue(child(path,key),"INVALID_DISCRIMINATOR",choices.toString());branches=selected;break outer;}
                }
            }
            JSONArray issues=new JSONArray();for(int i=0;i<branches.length();i++)try{validate(value,branches.getJSONObject(i),path);return;}catch(Invalid mismatch){for(int n=0;n<mismatch.issues.length()&&issues.length()<16;n++)issues.put(mismatch.issues.get(n));}
            throw new Invalid(issues);
        }
        if(schema.has("enum")){boolean match=false;JSONArray choices=schema.getJSONArray("enum");for(int i=0;i<choices.length();i++)match|=choices.get(i).equals(value);if(!match)throw issue(path,"ENUM_VALUE_INVALID",choices.toString());}
        switch(schema.optString("type")){
            case "string":if(!(value instanceof String))throw issue(path,"TYPE_MISMATCH","string");String text=(String)value;if(text.length()<schema.optInt("minLength",0)||text.length()>schema.optInt("maxLength",32000))throw issue(path,"STRING_LENGTH_INVALID",schema.optInt("minLength",0)+".."+schema.optInt("maxLength",32000));if(schema.has("pattern")&&!Pattern.compile(schema.getString("pattern")).matcher(text).find())throw issue(path,"STRING_PATTERN_INVALID",schema.optString("description","declared string format"));break;
            case "null":if(value!=JSONObject.NULL)throw issue(path,"TYPE_MISMATCH","null");break;
            case "number":case "integer":if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||"integer".equals(schema.getString("type"))&&(number.doubleValue()!=Math.rint(number.doubleValue())||Math.abs(number.doubleValue())>9007199254740991d)||number.doubleValue()<schema.optDouble("minimum",-Double.MAX_VALUE)||number.doubleValue()>schema.optDouble("maximum",Double.MAX_VALUE))throw invalid(path);break;
            case "boolean":if(!(value instanceof Boolean))throw issue(path,"TYPE_MISMATCH","boolean");break;
            case "object":{
                if(!(value instanceof JSONObject object))throw issue(path,"TYPE_MISMATCH","object");JSONObject props=schema.optJSONObject("properties");if(props==null)props=new JSONObject();JSONArray required=schema.optJSONArray("required");
                if(object.length()>schema.optInt("maxProperties",Integer.MAX_VALUE))throw issue(path,"OBJECT_SIZE_INVALID",String.valueOf(schema.getInt("maxProperties")));
                JSONArray missing=new JSONArray();if(required!=null)for(int i=0;i<required.length();i++){String key=required.getString(i);if(!object.has(key)){JSONObject problem=new JSONObject().put("path",child(path,key)).put("code","REQUIRED_PROPERTY_MISSING");if(key.equals("processGeneration"))problem.put("source","application_query(action=targets)");missing.put(problem);}}if(missing.length()>0)throw new Invalid(missing);
                for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();if(schema.has("propertyNames"))validate(key,schema.getJSONObject("propertyNames"),child(path,key));if(props.has(key))validate(object.get(key),props.getJSONObject(key),child(path,key));else if(schema.opt("additionalProperties") instanceof JSONObject additional)validate(object.get(key),additional,child(path,key));else if(Boolean.FALSE.equals(schema.opt("additionalProperties")))throw issue(child(path,key),"UNKNOWN_PROPERTY","declared property");}break;
            }
            case "array":{
                if(!(value instanceof JSONArray array))throw issue(path,"TYPE_MISMATCH","array");if(array.length()<schema.optInt("minItems",0)||array.length()>schema.optInt("maxItems",Integer.MAX_VALUE))throw issue(path,"ARRAY_LENGTH_INVALID",schema.optInt("minItems",0)+".."+schema.optInt("maxItems",Integer.MAX_VALUE));
                for(int i=0;i<array.length();i++)validate(array.get(i),schema.getJSONObject("items"),child(path,String.valueOf(i)));break;
            }
        }
    }
    static void selectors(JSONObject args)throws Exception{
        JSONObject request=args.optJSONObject("request");if(request!=null&&request.has("url")){String url=request.getString("url");try{java.net.URI parsed=java.net.URI.create(url);if(!url.matches("[\\x21-\\x7e]+")||url.matches(".*[<>\\\"{}|\\\\^`].*")||!("http".equalsIgnoreCase(parsed.getScheme())||"https".equalsIgnoreCase(parsed.getScheme()))||parsed.getHost()==null||parsed.getRawUserInfo()!=null||parsed.getRawFragment()!=null||parsed.getPort()>65535||parsed.getPort()==0||parsed.getRawAuthority().contains("%"))throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw issue("/request/url","HTTP_URL_INVALID","ASCII HTTP(S) URL with valid escapes, host, no userinfo or fragment");}if(Set.of("GET","HEAD").contains(request.getString("method"))&&request.has("body"))throw issue("/request/body","HTTP_BODY_NOT_ALLOWED","omit body for GET/HEAD");}

        JSONObject invocation=args.optJSONObject("invocation");
        if(invocation!=null){
            JSONArray types=invocation.getJSONArray("argumentTypes"),values=invocation.getJSONArray("arguments");
            if(types.length()!=values.length())throw issue("/invocation/arguments","ARGUMENT_COUNT_MISMATCH","one value per declared argument");
            for(int i=0;i<types.length();i++)if(!javaArgument(types.getString(i),values.get(i)))throw issue("/invocation/arguments/"+i,"INVOCATION_VALUE_INVALID",types.getString(i));
        }
        JSONArray list=args.optJSONArray("selectors");JSONObject config=args.optJSONObject("configuration");if(list==null&&config!=null){JSONObject hook=config.optJSONObject("startupHook");if(hook!=null)list=hook.optJSONArray("selectors");}
        if(config!=null&&config.has("startupHook")&&!Boolean.TRUE.equals(config.opt("enabled")))throw issue("/configuration/enabled","STARTUP_HOOK_REQUIRES_ENABLED_TRUE","true");
        if(list!=null){Set<String> ids=new HashSet<>();for(int i=0;i<list.length();i++)if(!ids.add(list.getJSONObject(i).getString("id")))throw issue(args.has("selectors")?"/selectors":"/configuration/startupHook/selectors","DUPLICATE_SELECTOR_ID","unique selector ids");}
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
    private static Invalid invalid(String path){return issue(path,"TYPE_OR_RANGE_INVALID","declared type and range");}
}
