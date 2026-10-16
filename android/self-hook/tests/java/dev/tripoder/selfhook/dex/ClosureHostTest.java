package dev.tripoder.selfhook.dex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
public final class ClosureHostTest {
    public static void main(String[] args)throws Exception{
        DexFile file=new DexFile(Files.readAllBytes(Path.of(args[0])));
        DexCallerClosure closure=new DexCallerClosure(Collections.singletonList(file));
        String prefix="Ldev/tripoder/selfhook/dex/ClosureOracle";int checks=0;
        for(String target:new String[]{prefix+"$Base;->run(I)I",prefix+"$Child;->run(I)I"}){
            Set<String> names=new HashSet<>();for(DexFile.MethodRef caller:closure.candidates(target))names.add(caller.name);
            for(String expected:new String[]{"virtualCall","interfaceCall","outer"}){if(!names.contains(expected))throw new AssertionError("MISSING_CALLER:"+expected);checks++;}
        }
        if(closure.dynamicSites<2||closure.externalSites==0||closure.completeRuntimeCoverage)throw new AssertionError("UNPROVEN_RUNTIME_COVERAGE");checks++;
        try{closure.candidates("Lmissing;->run(I)I");throw new AssertionError("MISSING_TARGET_ACCEPTED");}catch(IllegalArgumentException expected){checks++;}
        try{new DexCallerClosure(java.util.Arrays.asList(file,file));throw new AssertionError("DUPLICATE_DEX_ACCEPTED");}catch(IllegalArgumentException expected){checks++;}
        Set<String> selected=new HashSet<>(),unrelated=new HashSet<>();
        for(DexFile.MethodRef caller:closure.candidates(prefix+"$StaticBase;->value(I)I"))selected.add(caller.name);
        for(DexFile.MethodRef caller:closure.candidates(prefix+"$Unrelated;->value(I)I"))unrelated.add(caller.name);
        for(String name:new String[]{"selected","inherited","selectedOuter"}){
            if(!selected.contains(name))throw new AssertionError("STATIC_CALLER_MISSING:"+name);checks++;
        }
        for(String name:new String[]{"unrelated","unrelatedOuter"}){
            if(selected.contains(name))throw new AssertionError("UNRELATED_STATIC_CALLER:"+name);checks++;
            if(!unrelated.contains(name))throw new AssertionError("UNRELATED_CALLER_MISSING:"+name);checks++;
        }
        if(unrelated.contains("selected")||unrelated.contains("selectedOuter"))throw new AssertionError("EXACT_OWNER_LOST");checks++;
        System.out.println("{\"passed\":true,\"checks\":"+checks+",\"dynamicSites\":"+closure.dynamicSites+",\"externalSites\":"+closure.externalSites+",\"completeRuntimeCoverage\":false}");
    }
}
