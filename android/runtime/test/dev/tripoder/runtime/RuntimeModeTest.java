package dev.tripoder.runtime;

import java.util.*;

/** Host test for the mode parsing and fail-closed rules; RuntimeMode has no Android dependency. */
public final class RuntimeModeTest {
    private static int checks;
    private static final List<String> names=new ArrayList<>();
    private interface Attempt {void run()throws Exception;}
    private static void check(String name,boolean value){if(!value)throw new AssertionError(name);checks++;names.add(name);}
    private static void rejects(String name,Class<? extends Throwable> kind,String reason,Attempt attempt)throws Exception{
        try{attempt.run();throw new AssertionError(name+" accepted");}
        catch(Throwable error){if(!kind.isInstance(error)||!String.valueOf(error.getMessage()).startsWith(reason))throw new AssertionError(name+": "+error);check(name,true);}
    }
    public static void main(String[] args)throws Exception {
        // The wire strings are a persisted/IPC contract: changing them silently strands stored projects.
        check("wire-key-stable",RuntimeMode.KEY.equals("mode"));
        check("wire-values-stable",RuntimeMode.THICK.wire.equals("thick")&&RuntimeMode.THIN.wire.equals("thin"));
        check("default-is-thin-after-census-parity",RuntimeMode.DEFAULT==RuntimeMode.THIN);
        check("absent-value-is-default",RuntimeMode.parse(null)==RuntimeMode.DEFAULT);
        check("parse-thick",RuntimeMode.parse("thick")==RuntimeMode.THICK);
        check("parse-thin",RuntimeMode.parse("thin")==RuntimeMode.THIN);
        for(RuntimeMode mode:RuntimeMode.values())check("roundtrip-"+mode.wire,RuntimeMode.parse(mode.wire)==mode);
        rejects("unknown-rejected-not-degraded",IllegalArgumentException.class,"RUNTIME_MODE_UNKNOWN:",()->RuntimeMode.parse("bogus"));
        rejects("empty-rejected",IllegalArgumentException.class,"RUNTIME_MODE_UNKNOWN:",()->RuntimeMode.parse(""));
        rejects("case-sensitive",IllegalArgumentException.class,"RUNTIME_MODE_UNKNOWN:",()->RuntimeMode.parse("THICK"));
        try{RuntimeMode.parse("x".repeat(500));throw new AssertionError("long accepted");}
        catch(IllegalArgumentException error){check("rejection-message-bounded",error.getMessage().length()<=64);}
        check("thick-implemented",RuntimeMode.THICK.implemented());
        check("thin-implemented",RuntimeMode.THIN.implemented());
        check("require-implemented-returns-same",RuntimeMode.THICK.requireImplemented()==RuntimeMode.THICK);
        check("require-implemented-thin-returns-same",RuntimeMode.THIN.requireImplemented()==RuntimeMode.THIN);
        // The slot-side read path in RuntimeEngine: parse then require.
        check("slot-start-accepts-thin",RuntimeMode.parse("thin").requireImplemented()==RuntimeMode.THIN);
        check("slot-start-accepts-absent",RuntimeMode.parse(null).requireImplemented()==RuntimeMode.THIN);
        check("resolve-empty-is-default",RuntimeMode.resolve(List.of())==RuntimeMode.THIN);
        check("resolve-single-thick",RuntimeMode.resolve(List.of("thick"))==RuntimeMode.THICK);
        check("resolve-single-thin",RuntimeMode.resolve(List.of("thin"))==RuntimeMode.THIN);
        check("resolve-multiple-agreeing-thin",RuntimeMode.resolve(List.of("thin","thin"))==RuntimeMode.THIN);
        rejects("resolve-conflict-rejected",IllegalStateException.class,"RUNTIME_MODE_CONFLICT:thick:thin",()->RuntimeMode.resolve(List.of("thick","thin")));
        for(RuntimeMode mode:RuntimeMode.values())check("routes-through-platform-"+mode.wire,mode.routesThroughPlatform());
        check("thick-conceals-device",RuntimeMode.THICK.concealsDevice());
        check("thin-uses-platform-device",!RuntimeMode.THIN.concealsDevice());
        rejects("resolve-unknown-rejected",IllegalArgumentException.class,"RUNTIME_MODE_UNKNOWN:",()->RuntimeMode.resolve(List.of("invalid")));
        System.out.println("{\"passed\":true,\"assertions\":"+checks+",\"checks\":[\""+String.join("\",\"",names)+"\"]}");
    }
}
