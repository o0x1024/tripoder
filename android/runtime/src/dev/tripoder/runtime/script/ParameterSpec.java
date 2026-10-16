package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Same scalar parameter schema as parameters.ts; parameters never carry a callable or guest object. */
public final class ParameterSpec {
    public final String type;
    public final Object defaultValue;
    public final Double minimum, maximum;
    public final Integer maxLength;
    public ParameterSpec(String type,Object defaultValue,Double minimum,Double maximum,Integer maxLength) {
        require(Set.of("string","boolean","number","integer").contains(type),"PARAM_TYPE");
        require(minimum==null||Double.isFinite(minimum),"PARAM_SCHEMA");
        require(maximum==null||Double.isFinite(maximum),"PARAM_SCHEMA");
        require(minimum==null||maximum==null||minimum<=maximum,"PARAM_SCHEMA");
        require(maxLength==null||maxLength>=0,"PARAM_SCHEMA");
        this.type=type;this.minimum=minimum;this.maximum=maximum;this.maxLength=maxLength;
        this.defaultValue=validate(defaultValue);
    }
    public Object validate(Object value) {
        if(type.equals("boolean"))require(value instanceof Boolean,"PARAM_TYPE");
        else if(type.equals("string")) {
            require(value instanceof String,"PARAM_TYPE");String s=(String)value;
            require(maxLength==null||s.codePointCount(0,s.length())<=maxLength,"PARAM_RANGE");
        } else {
            require(value instanceof Double&&Double.isFinite((Double)value),"PARAM_TYPE");double n=(Double)value;
            require(!type.equals("integer")||n==Math.rint(n)&&Math.abs(n)<=9007199254740991d,"PARAM_RANGE");
            require((minimum==null||n>=minimum)&&(maximum==null||n<=maximum),"PARAM_RANGE");
        }
        return ScriptValues.limit(value);
    }
    public static Map<String,Object> resolve(Map<String,ParameterSpec> specs,Map<String,Object> input) {
        require(specs.size()<=64&&specs.keySet().containsAll(input.keySet()),"UNKNOWN_PARAM");
        Map<String,Object> out=new LinkedHashMap<>();int chars=2;
        for(var e:specs.entrySet()) {
            String key=ScriptValues.key(e.getKey());Object value=e.getValue().validate(input.containsKey(key)?input.get(key):e.getValue().defaultValue);
            chars+=jsonStringLength(key)+1+(value instanceof String?jsonStringLength((String)value):ScriptValues.text(value).length())+(out.isEmpty()?0:1);
            require(chars<=8192,"HOOK_PARAMETER_BUDGET");out.put(key,value);
        }
        return Collections.unmodifiableMap(out);
    }
    private static int jsonStringLength(String text) {
        int length=2;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"'||c=='\\'||c=='\b'||c=='\f'||c=='\n'||c=='\r'||c=='\t')length+=2;
            else if(c<32)length+=6;
            else if(Character.isHighSurrogate(c)&&i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1))){length+=2;i++;}
            else if(Character.isSurrogate(c))length+=6;
            else length++;
        }
        return length;
    }
}
