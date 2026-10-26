package dev.tripoder.runtime;

import java.util.Map;
import java.util.function.Function;

/** Regression for unrelated THICK apps being fenced by pending/job identities. */
public final class RuntimeOperationScopeTest {
    private static int checks;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    private static String key(String...parts){StringBuilder out=new StringBuilder();for(String p:parts)out.append(p.length()).append(':').append(p);return out.toString();}
    public static void main(String[] args){
        String a="space/qa-a/package/dev.a",b="space/qa-b/package/dev.b";
        Function<String,String> installs=Map.of("install-a",a,"install-b",b)::get;
        check(RuntimeOperationScope.identity("pending-install:install-a",installs).equals(a),"pending creator scoped");
        check(RuntimeOperationScope.identity("pending-install:install-a:data-a",installs).equals(a),"pending delivery scoped");
        check(RuntimeOperationScope.identity("job-install:install-a",installs).equals(a),"job creator scoped");
        check(RuntimeOperationScope.identity(key("qa-a","dev.a","install-a","dev.a:worker","process-a"),installs).equals(a),"session identity scoped");
        String historical=RuntimeOperationScope.recorded("*","legacy-or-internal","pending-install:install-a",installs);
        check(historical.equals(a)&&!RuntimeOperationScope.overlap(historical,b),"historical internal UNKNOWN cannot poison another app");
        check(RuntimeOperationScope.overlap(historical,a),"own UNKNOWN still fenced");
        check(RuntimeOperationScope.overlap(historical,"space/qa-a"),"space deletion still fenced");
        check(RuntimeOperationScope.overlap(historical,"*"),"global mutation still fenced");
        check(!RuntimeOperationScope.overlap(a,a+"2"),"package prefix not ownership");
        check(RuntimeOperationScope.recorded("*","createSpace","pending-install:install-a",installs).equals("*"),"explicit global scope preserved");
        check(RuntimeOperationScope.recorded(null,null,"job-install:install-b",installs).equals(b),"missing historical scope derived from authority identity");
        check(RuntimeOperationScope.recorded("space/wider","legacy-or-internal","job-install:install-a",installs).equals("space/wider"),"recorded non-global scope preserved");
        check(RuntimeOperationScope.identity("job-install:removed-install",installs).equals("*"),"unproved install stays globally fenced");
        for(String malformed:new String[]{"controller","pending-install:","job-install:../escape","0:","9999999999:bad",key("qa-a","dev.a","a","p","g")+"trailing",key("qa/a","dev.a","a","p","g")})
            check(RuntimeOperationScope.identity(malformed,installs).equals("*"),"malformed identity stays globally fenced");
        System.out.println("RUNTIME_OPERATION_SCOPE_PASS checks="+checks);
    }
}
