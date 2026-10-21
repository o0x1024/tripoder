package dev.tripoder.runtime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class RuntimeNativeMappingEvidenceTest {
    static final String CORE="/data/app/owned/lib/arm64/libtripoderselfhook.so";
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError("check "+checks);checks++;}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static String row(int index,String path){return Long.toHexString(0x100000L+index*0x2000L)+"-"+Long.toHexString(0x101000L+index*0x2000L)+" r-xp 00000000 00:00 "+(index==0?7:100+index)+" "+path+"\n";}
    static Map<String,Object> parse(String rows)throws Exception{return RuntimeNativeMappingEvidence.parse(rows.getBytes(StandardCharsets.UTF_8),CORE,7,0);}
    static void rejected(String rows)throws Exception{try{parse(rows);throw new AssertionError("accepted invalid core");}catch(SecurityException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        StringBuilder rows=new StringBuilder(row(0,CORE));List<String> paths=new ArrayList<>();paths.add(CORE);
        for(int i=1;i<=700;i++){String path="/data/app/"+"a".repeat(80)+i+"/lib/arm64/libfixture"+i+".so";paths.add(path);rows.append(row(i,path));}
        String foreign="/data/app/late/libfrida.so";paths.add(foreign);rows.append(row(701,foreign));Map<String,Object> value=parse(rows.toString());
        check(Boolean.TRUE.equals(value.get("nativePathsTruncated"))&&value.get("nativePathCount").equals(702));
        String displayed=(String)value.get("nativePaths");check(displayed.length()<=32000&&displayed.startsWith(CORE+"\n")&&displayed.split("\n").length==((Number)value.get("nativePathsReported")).intValue());
        check(((List<?>)value.get("foreignBackendRows")).size()==1);
        check(value.get("nativePathsSha256").equals(hash(String.join("\n",paths)))&&value.get("nativeRowsSha256").equals(hash(rows.toString())));
        value=parse(row(0,CORE)+row(1,"/apex/libfixture.so"));check(Boolean.FALSE.equals(value.get("nativePathsTruncated"))&&value.get("nativePathCount").equals(2));
        rejected(row(0,CORE).replace("00:00 7 ","00:00 8 "));
        rejected(row(0,CORE).replace(" r-xp "," r--p "));
        rejected(row(0,CORE)+row(1,"/different/libtripoderselfhook.so"));
        for(String invalid:List.of("-2000 r--p 0 00:00 0", "1000- r--p 0 00:00 0", "G000-2000 r--p 0 00:00 0",
            "1000-2000  r--p 0 00:00 0", "1000-2000 rwzp 0 00:00 0", "1000-2000 r--p  00:00 0",
            "1000-2000 r--p 0 :00 0", "1000-2000 r--p 0 00: 0", "1000-2000 r--p 0 00:00 -1",
            "1000-2000 r--p 0 00:00 ", "1000-2000 r--p 0 00:00 0 /bad\rpath"))rejected(row(0,CORE)+invalid+"\n");
        StringBuilder large=new StringBuilder(row(0,CORE));for(int i=1;i<18000;i++)large.append(row(i,"[anon:helper]"));
        large.append(row(18000,"/data/app/libGaDgEt.so"));value=parse(large.toString());
        check(value.get("mapsRowCount").equals(18001)&&((List<?>)value.get("foreignBackendRows")).size()==1);
        check(value.get("mapsSha256").equals(hash(large.toString())));
        System.out.println("{\"passed\":true,\"checks\":"+checks+",\"scope\":\"host-mapping-evidence-fixtures\"}");
    }
}
