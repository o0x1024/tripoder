package dev.tripoder.runtime.script;

import java.net.URI;
import java.util.*;

/** No connection or guest code is executed here. Only failures after this stage may be UNKNOWN. */
public final class HttpRequestPreflight {
    public record Request(String method,URI uri,Map<String,String> headers,String body,int timeoutMs){}
    public static Request validate(Map<String,Object> spec){
        if(spec==null)throw rejected("HTTP_REQUEST_SPEC_REQUIRED");
        if(!(spec.get("method") instanceof String method)||!Set.of("GET","POST","PUT","DELETE","PATCH","HEAD","OPTIONS").contains(method))throw rejected("HTTP_METHOD_UNSUPPORTED");
        if(!(spec.get("url") instanceof String text)||text.isEmpty()||text.length()>2048)throw rejected("HTTP_URL_INVALID");
        URI uri;try{uri=URI.create(text);}catch(IllegalArgumentException invalid){throw rejected("HTTP_URL_INVALID");}
        if(!text.matches("[\\x21-\\x7e]+")||text.matches(".*[<>\\\"{}|\\\\^`].*")||!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||uri.getPort()>65535||uri.getPort()==0||uri.getRawAuthority().contains("%"))throw rejected("HTTP_URL_INVALID");
        int timeout=5000;if(spec.containsKey("timeoutMs")){Object raw=spec.get("timeoutMs");if(!(raw instanceof Number number)||number.doubleValue()!=Math.rint(number.doubleValue())||number.doubleValue()<100||number.doubleValue()>10000)throw rejected("HTTP_TIMEOUT_RANGE");timeout=number.intValue();}
        Map<String,String> headers=new LinkedHashMap<>();if(spec.containsKey("headers")){
            if(!(spec.get("headers") instanceof Map<?,?> entries)||entries.size()>32)throw rejected("HTTP_HEADERS_INVALID");
            for(var entry:entries.entrySet()){
                if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value)||key.length()>128||!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||value.length()>2048||value.chars().anyMatch(c->c<32&&c!=9||c==127))throw rejected("HTTP_HEADER_ENTRY_INVALID");headers.put(key,value);
            }
        }
        String body=null;if(spec.containsKey("body")){if(!(spec.get("body") instanceof String value)||value.length()>16384)throw rejected("HTTP_BODY_INVALID");if(Set.of("GET","HEAD").contains(method))throw rejected("HTTP_BODY_NOT_ALLOWED");body=value;}
        return new Request(method,uri,Collections.unmodifiableMap(headers),body,timeout);
    }
    private static HookPreflightRejected rejected(String code){return new HookPreflightRejected(code);}
    private HttpRequestPreflight(){}
}
