package dev.tripoder.runtime.script;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

final class HttpRequestPreflightTest {
    static void run(){
        var valid=HttpRequestPreflight.validate(Map.of("method","POST","url","https://example.com/a%20b","headers",Map.of("Content-Type","application/json"),"body","{}"));
        check(valid.method().equals("POST")&&valid.body().equals("{}"),"HTTP preflight retains request without network I/O");
        for(String url:List.of("https://example.com/%zz","https://example.com:99999/","https://user@example.com/","https://example.com/#","https://example.com/中文")){
            try{HttpRequestPreflight.validate(Map.of("method","GET","url",url));throw new AssertionError("invalid URL accepted");}catch(HookPreflightRejected expected){check(expected.getMessage().equals("HTTP_URL_INVALID"),"HTTP URL known rejection");}
        }
        for(String value:List.of("bad\r\nvalue","bad\nsecond\n","bad\u0000value")){
            try{HttpRequestPreflight.validate(Map.of("method","GET","url","https://example.com/","headers",Map.of("X-Probe",value)));throw new AssertionError("invalid header accepted");}catch(HookPreflightRejected expected){check(expected.getMessage().equals("HTTP_HEADER_ENTRY_INVALID"),"HTTP header known rejection");}
        }
        try{HttpRequestPreflight.validate(Map.of("method","GET","url","https://example.com/","body","ignored"));throw new AssertionError("GET body silently ignored");}catch(HookPreflightRejected expected){check(expected.getMessage().equals("HTTP_BODY_NOT_ALLOWED"),"GET body explicit rejection");}
        check(JavaTypes.valid("a",false)&&JavaTypes.valid("[La;",false)&&!JavaTypes.reference("int")&&!JavaTypes.reference("void"),"default-package object signatures preserve primitive classification");
    }
}
