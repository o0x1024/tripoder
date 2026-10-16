package dev.tripoder.selfhook;
import java.nio.charset.StandardCharsets;
/** Process-lifetime fixed-linker observer. Raw identity stays in the trusted
 * backend; it is never a script value or a user-supplied pointer capability. */
public final class NativeModules {
    public static final class Snapshot {
        public final long generation,base,references;public final boolean live,ambiguous;
        private Snapshot(long[] values){generation=values[0];base=values[1];references=values[2];live=values[3]==1;ambiguous=values[4]==1;}
    }
    private NativeModules(){}
    public static void initialize(String[] canonicalArtifactLibraries){
        if(canonicalArtifactLibraries==null||canonicalArtifactLibraries.length>4096)throw new IllegalArgumentException("NATIVE_MODULE_ALLOWLIST");
        byte[][] paths=new byte[canonicalArtifactLibraries.length][];
        for(int i=0;i<paths.length;i++)paths[i]=path(canonicalArtifactLibraries[i]);
        if(!initialize0(paths))throw new IllegalStateException("NATIVE_MODULE_INITIALIZATION_REJECTED");
    }
    static byte[] path(String value){
        if(value==null||!value.startsWith("/")||value.indexOf('\0')>=0)throw new IllegalArgumentException("NATIVE_CANONICAL_PATH");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>=1024)throw new IllegalArgumentException("NATIVE_PATH_BUDGET");return bytes;
    }
    public static Snapshot snapshot(int index){long[] values=snapshot0(index);if(values==null||values.length!=5)throw new IllegalStateException("NATIVE_MODULE_SNAPSHOT_REJECTED");return new Snapshot(values);}
    public static long[] events(){long[] values=events0();if(values==null||values.length>320||values.length%5!=0)throw new IllegalStateException("NATIVE_MODULE_EVENTS_BUSY");return values;}
    public static boolean attest(int index,long generation,long exportRva,String buildId){
        return attest(index,generation,exportRva,buildId,false);
    }
    public static boolean attestExport(int index,long generation,long exportRva,String buildId){
        if(exportRva<=0||(exportRva&3)!=0)throw new IllegalArgumentException("NATIVE_EXPORT_RVA");
        return attest(index,generation,exportRva,buildId,true);
    }
    private static boolean attest(int index,long generation,long exportRva,String buildId,boolean prefix){
        if(index<0||generation<=0||generation>0xffffffffL||exportRva<0||buildId==null||!buildId.matches("[a-f0-9]{8,128}")||(buildId.length()&1)!=0)throw new IllegalArgumentException("NATIVE_MODULE_ATTESTATION");
        return attest0(index,generation,exportRva,java.util.HexFormat.of().parseHex(buildId),prefix);
    }
    private static native boolean initialize0(byte[][] paths);
    private static native long[] snapshot0(int index);
    private static native long[] events0();
    private static native boolean attest0(int index,long generation,long exportRva,byte[] buildId,boolean prefix);
}
