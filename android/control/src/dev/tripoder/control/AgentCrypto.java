package dev.tripoder.control;

import java.util.*;
import org.json.JSONObject;

/** Legacy class name for unsigned wire field validation. No identity or cryptography. */
public final class AgentCrypto {
    public static final int PROTOCOL=2,DEVICE_PORT=29731;
    public static void fields(JSONObject object,String... fields){Set<String> actual=new HashSet<>();object.keys().forEachRemaining(actual::add);if(!actual.equals(Set.of(fields)))throw new IllegalArgumentException("AGENT_FIELDS_UNSUPPORTED");}
    public static String text(JSONObject object,String key)throws Exception{Object value=object.get(key);if(!(value instanceof String))throw new IllegalArgumentException("AGENT_FIELD_TYPE:"+key);return (String)value;}
    public static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("AGENT_ID_INVALID");return value;}
    private AgentCrypto(){}
}
