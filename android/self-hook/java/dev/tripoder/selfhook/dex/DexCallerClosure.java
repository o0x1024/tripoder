package dev.tripoder.selfhook.dex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative bytecode callers, including virtual/interface/super dispatch.
 * Virtual and unresolved inherited static references conservatively expand
 * signatures. A resolved static/direct reference keeps its exact owner.
 * This is not an inventory of loaded boot/JIT/AOT code or future class loaders. */
public final class DexCallerClosure {
    private final Map<String,DexFile.Definition> definitions=new LinkedHashMap<>();
    private final Map<String,List<DexFile.MethodRef>> reverse=new HashMap<>();
    public final List<String> dexDigests;
    public final int dynamicSites, externalSites;
    public final boolean completeRuntimeCoverage=false;
    public DexCallerClosure(List<DexFile> files){
        Set<String> digests=new LinkedHashSet<>();
        for(DexFile file:files){
            DexFile.need(digests.add(file.sha256),"CALLER_CLOSURE_DUPLICATE_DEX");
            for(DexFile.Definition definition:file.definitions)
                DexFile.need(definitions.put(definition.method.key(),definition)==null,"CALLER_CLOSURE_DUPLICATE_DEFINITION");
        }
        dexDigests=Collections.unmodifiableList(new ArrayList<>(digests));
        Map<String,List<DexFile.Definition>> signatures=new HashMap<>();
        for(DexFile.Definition d:definitions.values())signatures.computeIfAbsent(signature(d.method),ignored->new ArrayList<>()).add(d);
        int dynamic=0,external=0,edges=0;long work=0;
        for(DexFile.Definition caller:definitions.values()){
            Set<String> targets=new HashSet<>();
            for(DexCode.Call call:caller.calls){
                DexFile.need(++work<=500000,"CALLER_CLOSURE_WORK_BUDGET");
                if(call.target==null||call.opcode==0xfa||call.opcode==0xfb){dynamic++;continue;}
                if(reflectiveOrLoading(call.target))dynamic++;
                if(!definitions.containsKey(call.target.key()))external++;
                boolean direct=call.opcode==0x70||call.opcode==0x76;
                if(direct||call.staticDispatch()&&definitions.containsKey(call.target.key())){
                    DexFile.Definition exact=definitions.get(call.target.key());
                    if(exact!=null&&((exact.flags&8)!=0)==call.staticDispatch())targets.add(call.target.key());
                }
                else{
                    for(DexFile.Definition target:signatures.getOrDefault(signature(call.target),Collections.emptyList())){
                        DexFile.need(++work<=500000,"CALLER_CLOSURE_WORK_BUDGET");
                        if(((target.flags&8)!=0)==call.staticDispatch())targets.add(target.method.key());
                    }
                }
                DexFile.need(targets.size()<=500000,"CALLER_CLOSURE_EDGE_BUDGET");
            }
            edges+=targets.size();DexFile.need(edges<=500000,"CALLER_CLOSURE_EDGE_BUDGET");
            for(String target:targets)reverse.computeIfAbsent(target,ignored->new ArrayList<>()).add(caller.method);
        }
        dynamicSites=dynamic;externalSites=external;
    }
    private static String signature(DexFile.MethodRef method){return method.name+method.descriptor;}
    private static boolean reflectiveOrLoading(DexFile.MethodRef method){
        return method.owner.startsWith("Ljava/lang/reflect/")||method.owner.startsWith("Ljava/lang/invoke/")
            ||method.owner.equals("Ljava/lang/ClassLoader;")||method.owner.startsWith("Ldalvik/system/")
            ||(method.owner.equals("Ljava/lang/Class;")&&method.name.equals("forName"));
    }
    public List<DexFile.MethodRef> candidates(String target){
        DexFile.need(definitions.containsKey(target),"CALLER_CLOSURE_TARGET_NOT_DEFINED");
        Set<String> seen=new HashSet<>();ArrayDeque<String> pending=new ArrayDeque<>();List<DexFile.MethodRef> result=new ArrayList<>();
        seen.add(target);pending.add(target);
        while(!pending.isEmpty())for(DexFile.MethodRef caller:reverse.getOrDefault(pending.remove(),Collections.emptyList())){
            if(seen.add(caller.key())){result.add(caller);pending.add(caller.key());}
        }
        return Collections.unmodifiableList(result);
    }
}
