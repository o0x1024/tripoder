package dev.tripoder.runtime;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class RuntimeNativeMappingEvidenceTest {
    private static final String CORE="/data/app/owned/lib/arm64/libtripoderselfhook.so";
    private static final String GOOD="1000-2000 r--p 00000000 fd:05 700 "+CORE+"\n2000-3000 r-xp 00001000 fd:05 700 "+CORE+"\n3000-4000 rw-p 00002000 fd:05 700 "+CORE+"\n4000-5000 rw-p 00000000 00:00 0 [anon:runtime]\n";
    private static int checks;
    public static void main(String[] args)throws Exception {
        Map<String,Object> result=parse(GOOD);check(((List<?>)result.get("coreRows")).size()==3&&((Number)result.get("mapsRowCount")).intValue()==4,"actual core rows");
        check(((String)result.get("mapsSha256")).matches("[a-f0-9]{64}"),"complete raw digest");
        check(result.get("nativePaths").equals(CORE),"unique loaded file paths");
        check(((List<?>)result.get("foreignBackendRows")).isEmpty(),"no backend row invented");
        reject(GOOD.replace("fd:05 700","fd:05 701"),"IDENTITY");reject(GOOD.replace("fd:05 700","fd:06 700"),"IDENTITY");
        reject(GOOD.replace(CORE,CORE+" (deleted)"),"IDENTITY");reject(GOOD.replace("r-xp","rwxp"),"WX");
        reject(GOOD.replace("r-xp","r--p"),"MISSING");reject(GOOD.substring(0,GOOD.length()-1),"INCOMPLETE");
        reject(GOOD.replace("4000-5000 rw-p","bad-row rw-p"),"ROW");
        check(((List<?>)parse(GOOD+"6000-7000 r-xp 00000000 fd:05 701 /data/app/owned/lib/arm64/libgadget.so\n").get("foreignBackendRows")).size()==1,"unapproved backend observed");
        System.out.println("RuntimeNativeMappingEvidenceTest: "+checks+" passed");
    }
    private static Map<String,Object> parse(String text)throws Exception{return RuntimeNativeMappingEvidence.parse(text.getBytes(StandardCharsets.UTF_8),CORE,700,0xfd05);}
    private static void reject(String text,String code)throws Exception {try{parse(text);throw new AssertionError("accepted "+code);}catch(SecurityException error){check(error.getMessage().contains(code),code);}}
    private static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);checks++;}
}
