package dev.tripoder.selfhook.dex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HelperDexHostTest {
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]);Files.createDirectories(output);int checks=0;
        List<String> mixed=Arrays.asList("I","J","D","Ljava/lang/Object;","F","J","D","[I","I","J","D","F","Ljava/lang/String;","I","J","D","F","Ljava/lang/Object;");
        String[] results={"V","Z","B","S","C","I","J","F","D","Ljava/lang/Object;","[I"};
        for(int i=0;i<results.length;i++)for(boolean instance:new boolean[]{false,true}){
            int ordinal=i*2+(instance?1:0),slot=ordinal==19?63:ordinal==20?64:ordinal==21?HookCapacity.MAX_ENTRIES-1:ordinal;
            AbiSignature abi=new AbiSignature(results[i],mixed,instance);byte[] bytes=HelperDex.create(abi,slot);DexFile dex=new DexFile(bytes);
            if(dex.definitions.size()!=1||dex.definitions.get(0).flags!=9)throw new AssertionError("SINGLE_STATIC_MANAGED_HELPER");checks++;
            StringBuilder expected=new StringBuilder("(");for(String type:abi.helperParameters())expected.append(type);
            expected.append(')').append(AbiSignature.reference(abi.result)?"Ljava/lang/Object;":abi.result);
            if(!dex.definitions.get(0).method.descriptor.equals(expected.toString()))throw new AssertionError("EXACT_HELPER_DESCRIPTOR");checks++;
            Files.write(output.resolve("helper-"+i+"-"+instance+".dex"),bytes);
        }
        for(String invalid:new String[]{"","[V","Lbad.name;","L/leading;","V"}){
            try{new AbiSignature("I",Collections.singletonList(invalid),false);throw new AssertionError("INVALID_PARAMETER_ACCEPTED");}
            catch(IllegalArgumentException expected){checks++;}
        }
        for(int slot:new int[]{-1,HookCapacity.MAX_ENTRIES})
            try{HelperDex.create(new AbiSignature("V",Collections.emptyList(),false),slot);throw new AssertionError("SLOT_BUDGET");}catch(IllegalArgumentException expected){checks++;}
        AbiSignature cached=new AbiSignature("J",List.of("I","J","Ljava/lang/String;","D"),true);
        for(int first:new int[]{8,98,998,4094}){
            int count=first==4094?2:4;byte[][] batch=HelperDex.createBatch(Collections.nCopies(count,cached),first);
            for(int i=0;i<count;i++){
                int slot=first+i;byte[] expected=HelperDex.create(cached,slot);
                if(!Arrays.equals(batch[i],expected))throw new AssertionError("TEMPLATE_VS_FULL_ENCODING:"+slot);checks++;
                DexFile parsed=new DexFile(batch[i]);if(!parsed.definitions.get(0).method.owner.equals("L"+HelperDex.className(slot).replace('.','/')+";"))throw new AssertionError("TEMPLATE_CLASS_IDENTITY");checks++;
                Files.write(output.resolve("cached-"+slot+".dex"),batch[i]);
            }
        }
        System.out.println("{\"checks\":"+checks+",\"passed\":true,\"dexFiles\":36}");
    }
}
