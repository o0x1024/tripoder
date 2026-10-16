package dev.tripoder.selfhook.dex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact static-call dependency index within an explicitly supplied DEX set.
 * It does not infer ClassLoader ownership, virtual dispatch or dynamic targets. */
public final class DexCallGraph {
    private final Map<String, DexFile.Definition> definitions = new LinkedHashMap<>();
    private final Map<String, List<DexFile.MethodRef>> callers = new HashMap<>();
    public final int unresolvedDispatchSites;
    public DexCallGraph(List<DexFile> files) {
        Set<String> digests = new HashSet<>();
        for (DexFile file : files) {
            DexFile.need(digests.add(file.sha256), "DEX_GRAPH_DUPLICATE_INPUT");
            for (DexFile.Definition method : file.definitions)
                DexFile.need(definitions.put(method.method.key(), method) == null, "DEX_GRAPH_DUPLICATE_DEFINITION");
        }
        long edgeBudget = 0;
        for (DexFile.Definition method : definitions.values()) edgeBudget += method.calls.size();
        DexFile.need(edgeBudget <= 500000, "DEX_GRAPH_EDGE_BUDGET");
        int unresolved = 0;
        for (DexFile.Definition method : definitions.values()) {
            Set<String> edges = new HashSet<>();
            for (DexCode.Call call : method.calls) {
                if (!call.staticDispatch()) { unresolved++; continue; }
                String key = call.target.key();
                DexFile.Definition target = definitions.get(key);
                if (target != null) DexFile.need((target.flags & 8) != 0, "DEX_STATIC_TARGET_NOT_STATIC");
                if (edges.add(key)) callers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(method.method);
            }
        }
        unresolvedDispatchSites = unresolved;
    }
    public List<DexFile.MethodRef> staticAncestors(String targetKey) {
        DexFile.Definition target = definitions.get(targetKey);
        DexFile.need(target != null && (target.flags & 8) != 0, "DEX_GRAPH_TARGET_NOT_DEFINED_STATIC");
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        List<DexFile.MethodRef> found = new ArrayList<>();
        visited.add(targetKey); pending.add(targetKey);
        while (!pending.isEmpty()) {
            for (DexFile.MethodRef method : callers.getOrDefault(pending.remove(), Collections.emptyList())) {
                if (visited.add(method.key())) { found.add(method); pending.add(method.key()); }
            }
        }
        return Collections.unmodifiableList(found);
    }
    public boolean hasDefinition(String key) { return definitions.containsKey(key); }
    public int definitionCount() { return definitions.size(); }
}
