package dev.tripoder.selfhook.dex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.ZipFile;

public final class DexGraphHostTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static void rejects(byte[] bytes, String reason) {
        try { new DexFile(bytes); throw new AssertionError("accepted " + reason); }
        catch (IllegalArgumentException error) { check(error.getMessage().equals(reason), error.toString()); }
    }
    private static byte[] resign(byte[] value) {
        byte[] signature = DexFile.digest("SHA-1", Arrays.copyOfRange(value, 32, value.length));
        System.arraycopy(signature, 0, value, 12, signature.length);
        Adler32 a = new Adler32(); a.update(value, 12, value.length - 12);
        ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int)a.getValue()); return value;
    }
    public static void main(String[] args) throws Exception {
        byte[] oracle = Files.readAllBytes(Path.of(args[0]));
        DexFile file = new DexFile(oracle);
        DexCallGraph graph = new DexCallGraph(Collections.singletonList(file));
        String root = "Ldev/tripoder/selfhook/dex/DexOracle;->target(I)I";
        Set<String> callers = new HashSet<>();
        for (DexFile.MethodRef ref : graph.staticAncestors(root)) callers.add(ref.key());
        check(callers.equals(Set.of("Ldev/tripoder/selfhook/dex/DexOracle;->caller(I)I",
                                   "Ldev/tripoder/selfhook/dex/DexOracle;->outer(I)I")), "transitive static callers");
        DexFile.Definition payload = file.definitions.stream().filter(x -> x.method.name.equals("payloads")).findFirst().orElseThrow();
        check(payload.calls.isEmpty(), "payload bytes were treated as invoke instructions");
        check(file.methods.stream().anyMatch(x -> x.owner.equals("[B") && x.name.equals("clone")), "array clone reference");
        check(graph.unresolvedDispatchSites > 0, "non-static dispatch not disclosed");
        check(file.sha256.equals(DexFile.hex(DexFile.digest("SHA-256", oracle))), "DEX identity");
        byte[] changed = oracle.clone(); changed[changed.length - 1] ^= 1; rejects(changed, "DEX_CHECKSUM");
        changed = oracle.clone(); changed[6] = '1'; rejects(changed, "DEX_VERSION_NOT_039");
        changed = oracle.clone(); ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(32, oracle.length + 1);
        rejects(resign(changed), "DEX_HEADER");
        changed = oracle.clone(); ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(60, oracle.length);
        rejects(resign(changed), "DEX_BOUNDS");
        changed = oracle.clone(); changed[12] ^= 1;
        Adler32 a = new Adler32(); a.update(changed, 12, changed.length - 12);
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int)a.getValue()); rejects(changed, "DEX_SIGNATURE");
        rejects(Arrays.copyOf(oracle, 100), "DEX_SIZE");
        try { new DexCallGraph(Arrays.asList(file, file)); throw new AssertionError("duplicate DEX"); }
        catch (IllegalArgumentException error) { check(error.getMessage().equals("DEX_GRAPH_DUPLICATE_INPUT"), error.toString()); }
        try { graph.staticAncestors("Lmissing;->target(I)I"); throw new AssertionError("unknown target"); }
        catch (IllegalArgumentException error) { check(error.getMessage().equals("DEX_GRAPH_TARGET_NOT_DEFINED_STATIC"), error.toString()); }
        try (ZipFile apk = new ZipFile(args[1])) {
            DexFile actual = new DexFile(apk.getInputStream(apk.getEntry("classes.dex")).readAllBytes());
            var found = new DexCallGraph(Collections.singletonList(actual)).staticAncestors(
                "Ldev/tripoder/selfhookprobe/InlineFixture;->plusOne(I)I");
            check(found.size() == 1 && found.get(0).key().equals("Ldev/tripoder/selfhookprobe/ManagedCaller;->call(I)I"),
                "actual APK compiled caller discovery");
        }
        System.out.println("{\"passed\":true,\"checks\":" + checks + ",\"oracleDexSha256\":\"" + file.sha256 + "\"}");
    }
}
