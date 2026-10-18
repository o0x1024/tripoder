package dev.tripoder.selfhook.dex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.Adler32;

/** One managed adapter in DEX 039. ART owns arguments, stack maps and GC roots. */
public final class HelperDex {
    private static final String OBJECT="Ljava/lang/Object;", ARRAY="[Ljava/lang/Object;";
    private static final String BRIDGE="Ldev/tripoder/selfhook/ManagedHook;";
    private static final String TYPES="ZBSCIJFD";
    private static final String[] BOXES={"Boolean","Byte","Short","Character","Integer","Long","Float","Double"};
    private static final String[] UNBOX={"booleanValue","byteValue","shortValue","charValue","intValue","longValue","floatValue","doubleValue"};
    private final AbiSignature abi;
    private final int slot;
    private final String owner;
    private final TreeSet<String> stringSet=new TreeSet<>(), typeSet=new TreeSet<>();
    private final List<Proto> protos=new ArrayList<>();
    private final List<Method> methods=new ArrayList<>();
    private List<String> strings,types;
    private final List<Integer> code=new ArrayList<>();
    private final List<int[]> sections=new ArrayList<>();
    private int ownerDigitsOffset,slotValueOffset;
    private record Shape(String result,List<String> parameters,boolean instance,int digits){}
    private static final Map<Shape,Template> TEMPLATES=new LinkedHashMap<>();
    private static final class Sealer {
        final java.security.MessageDigest sha1;final Adler32 checksum=new Adler32();
        Sealer(){try{sha1=java.security.MessageDigest.getInstance("SHA-1");}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
        void seal(byte[] file){
            sha1.update(file,32,file.length-32);System.arraycopy(sha1.digest(),0,file,12,20);
            checksum.reset();checksum.update(file,12,file.length-12);put(file,8,(int)checksum.getValue());
        }
    }
    private static final class Template {
        final byte[] bytes;final int owner,slot,digits;
        Template(HelperDex writer,byte[] bytes){
            this.bytes=bytes;owner=writer.ownerDigitsOffset;slot=writer.slotValueOffset;String text=Integer.toString(writer.slot);digits=text.length();
            if(owner<=0||slot<2||slot+2>bytes.length||owner+digits>bytes.length||bytes[slot-2]!=0x13||bytes[slot-1]!=2
                ||((bytes[slot]&255)|((bytes[slot+1]&255)<<8))!=writer.slot)throw new IllegalStateException("HELPER_TEMPLATE_OFFSETS");
            for(int i=0;i<digits;i++)if(bytes[owner+i]!=(byte)text.charAt(i))throw new IllegalStateException("HELPER_TEMPLATE_OWNER");
        }
        byte[] instantiate(int value,Sealer sealer){
            String text=Integer.toString(value);if(text.length()!=digits)throw new IllegalStateException("HELPER_TEMPLATE_WIDTH");
            byte[] result=bytes.clone();for(int i=0;i<digits;i++)result[owner+i]=(byte)text.charAt(i);
            result[slot]=(byte)value;result[slot+1]=(byte)(value>>>8);sealer.seal(result);return result;
        }
    }
    private static final class Proto {
        final String result;final List<String> args;
        Proto(String result,List<String> args){this.result=result;this.args=new ArrayList<>(args);}
        String shorty(){StringBuilder s=new StringBuilder(shortType(result));for(String t:args)s.append(shortType(t));return s.toString();}
        String key(){return args.toString()+result;}
    }
    private static final class Method {
        final String owner,name;final Proto proto;
        Method(String owner,String name,Proto proto){this.owner=owner;this.name=name;this.proto=proto;}
    }
    private HelperDex(AbiSignature abi,int slot){
        if(slot<0||slot>=HookCapacity.MAX_ENTRIES)throw new IllegalArgumentException("HELPER_SLOT_BUDGET");
        this.abi=abi;this.slot=slot;this.owner="Ldev/tripoder/selfhook/generated/Entry"+slot+";";
    }
    /** Canonical single-helper writer, also used as the uncached host oracle. */
    public static byte[] create(AbiSignature abi,int slot){return new HelperDex(abi,slot).write(new Sealer());}
    /** Pure immutable bytes: no helper class loading, slot reservation or Hook
     * publication. Fixed boot adapters can compute these before deoptimization. */
    public static synchronized void prepareTemplates(List<AbiSignature> signatures){
        if(signatures==null||signatures.isEmpty()||signatures.size()>HookCapacity.MAX_ENTRIES)throw new IllegalArgumentException("HELPER_SLOT_BUDGET");
        Sealer sealer=new Sealer();
        for(AbiSignature abi:signatures)for(int slot=0;slot<HookCapacity.MAX_ENTRIES;slot=slot==0?10:slot*10)template(abi,slot,sealer);
    }
    /** Each shape/decimal width is encoded and parsed once. Instantiation only
     * changes the class-name digits and dispatch constant, then seals hashes.
     * Immutable templates contain erased ABI strings and bytes, never guest
     * classes. Reuse across fixed installer groups; each result remains a private
     * byte array. The existing entry limit also bounds retained shape templates. */
    public static synchronized byte[][] createBatch(List<AbiSignature> signatures,int first){
        if(signatures==null||signatures.isEmpty()||first<0||first>=HookCapacity.MAX_ENTRIES||signatures.size()>HookCapacity.MAX_ENTRIES-first)
            throw new IllegalArgumentException("HELPER_SLOT_BUDGET");
        Sealer sealer=new Sealer();byte[][] result=new byte[signatures.size()][];
        for(int i=0;i<result.length;i++){
            AbiSignature abi=signatures.get(i);int slot=first+i;
            result[i]=template(abi,slot,sealer).instantiate(slot,sealer);
        }
        return result;
    }
    private static Template template(AbiSignature abi,int slot,Sealer sealer){
        Shape shape=new Shape(AbiSignature.reference(abi.result)?OBJECT:abi.result,List.copyOf(abi.helperParameters()),abi.instance,Integer.toString(slot).length());
        Template template=TEMPLATES.get(shape);
        if(template==null){
            if(TEMPLATES.size()>=HookCapacity.MAX_ENTRIES)throw new IllegalStateException("HELPER_TEMPLATE_BUDGET");
            HelperDex writer=new HelperDex(abi,slot);template=new Template(writer,writer.write(sealer));TEMPLATES.put(shape,template);
        }return template;
    }
    public static String className(int slot){return "dev.tripoder.selfhook.generated.Entry"+slot;}
    private static String shortType(String type){return AbiSignature.reference(type)?"L":type;}
    private static String box(String type){return "Ljava/lang/"+BOXES[TYPES.indexOf(type)]+";";}
    private void type(String value){typeSet.add(value);stringSet.add(value);}
    private Method method(String owner,String name,String result,List<String> args){
        type(owner);type(result);for(String arg:args)type(arg);stringSet.add(name);
        Proto p=new Proto(result,args);Proto selected=null;
        for(Proto old:protos)if(old.key().equals(p.key()))selected=old;
        if(selected==null){selected=p;protos.add(p);stringSet.add(p.shorty());}
        Method m=new Method(owner,name,selected);methods.add(m);return m;
    }
    private int typeIndex(String type){return types.indexOf(type);}
    private int methodIndex(Method method){return methods.indexOf(method);}
    private void emit(int... words){for(int word:words){if((word&~65535)!=0)throw new IllegalArgumentException("DEX_WORD");code.add(word);}}
    private void invoke(int opcode,Method method,int start,int words){emit(opcode|(words<<8),methodIndex(method),start);}
    private byte[] write(Sealer sealer){
        type(OBJECT);type(ARRAY);
        // Reference types share the same quick ABI. Erase them in the helper;
        // the original class identity is checked by ManagedHook, so private
        // application types need not be resolved from this generated class.
        Method entry=method(owner,"invoke",AbiSignature.reference(abi.result)?OBJECT:abi.result,abi.helperParameters());
        Method dispatch=method(BRIDGE,"dispatch",OBJECT,Arrays.asList("I",OBJECT,ARRAY));
        Map<String,Method> boxers=new LinkedHashMap<>();
        for(String t:abi.parameters)if(!AbiSignature.reference(t)&&!boxers.containsKey(t))boxers.put(t,method(box(t),"valueOf",box(t),Arrays.asList(t)));
        Method unbox=null;
        if(!abi.result.equals("V")&&!AbiSignature.reference(abi.result))unbox=method(box(abi.result),UNBOX[TYPES.indexOf(abi.result)],abi.result,java.util.Collections.emptyList());
        strings=new ArrayList<>(stringSet);types=new ArrayList<>(typeSet);
        protos.sort((a,b)->{int c=Integer.compare(typeIndex(a.result),typeIndex(b.result));
            for(int i=0;c==0&&i<Math.min(a.args.size(),b.args.size());i++)c=Integer.compare(typeIndex(a.args.get(i)),typeIndex(b.args.get(i)));
            return c==0?Integer.compare(a.args.size(),b.args.size()):c;});
        methods.sort(Comparator.comparingInt((Method m)->typeIndex(m.owner)).thenComparingInt(m->strings.indexOf(m.name)).thenComparingInt(m->protos.indexOf(m.proto)));
        int incoming=abi.instance?1:0;for(String t:abi.parameters)incoming+=AbiSignature.words(t);
        int locals=8,register=locals+(abi.instance?1:0);
        emit(0x0013,abi.parameters.size()); // const/16 v0, count
        emit(0x0023,typeIndex(ARRAY)); // new-array v0,v0,Object[]
        for(int i=0;i<abi.parameters.size();i++){
            String t=abi.parameters.get(i);int width=AbiSignature.words(t);
            if(AbiSignature.reference(t))emit(0x0108,register); // move-object/from16 v1, incoming
            else{emit((width==2?0x05:0x02)|(2<<8),register);invoke(0x77,boxers.get(t),2,width);emit(0x010c);}
            emit(0x0313,i);emit(0x014d,0x0300); // index v3; aput-object v1,v0,v3
            register+=width;
        }
        int slotWord=code.size()+1;emit(0x0213,slot);
        if(abi.instance)emit(0x0308,locals);else emit(0x0312); // receiver or null
        emit(0x0408,0);invoke(0x77,dispatch,2,3);
        if(abi.result.equals("V"))emit(0x000e);
        else{
            emit(0x000c); // move-result-object v0
            if(AbiSignature.reference(abi.result))emit(0x0011);
            else{emit(0x001f,typeIndex(box(abi.result)));invoke(0x74,unbox,0,1);
                emit(AbiSignature.words(abi.result)==2?0x000b:0x000a);emit(AbiSignature.words(abi.result)==2?0x0010:0x000f);}
        }
        Bytes out=new Bytes();out.zero(112);
        int stringIds=out.position();out.zero(strings.size()*4);section(1,strings.size(),stringIds);
        int typeIds=out.position();for(String t:types)out.u32(strings.indexOf(t));section(2,types.size(),typeIds);
        int protoIds=out.position();out.zero(protos.size()*12);section(3,protos.size(),protoIds);
        int methodIds=out.position();for(Method m:methods){out.u16(typeIndex(m.owner));out.u16(protos.indexOf(m.proto));out.u32(strings.indexOf(m.name));}section(5,methods.size(),methodIds);
        int classDefs=out.position();out.zero(32);section(6,1,classDefs);
        int dataStart=out.position(),listStart=0,listCount=0;
        for(int i=0;i<protos.size();i++){
            Proto p=protos.get(i);int offset=0;
            if(!p.args.isEmpty()){out.align();offset=out.position();if(listCount++==0)listStart=offset;out.u32(p.args.size());for(String t:p.args)out.u16(typeIndex(t));}
            out.patch(protoIds+i*12,strings.indexOf(p.shorty()));out.patch(protoIds+i*12+4,typeIndex(p.result));out.patch(protoIds+i*12+8,offset);
        }
        if(listCount>0)section(0x1001,listCount,listStart);
        int stringData=out.position();for(int i=0;i<strings.size();i++){
            out.patch(stringIds+i*4,out.position());
            if(strings.get(i).equals(owner)){if(owner.length()>=128)throw new IllegalStateException("HELPER_TEMPLATE_NAME_LENGTH");ownerDigitsOffset=out.position()+1+owner.length()-1-Integer.toString(slot).length();}
            out.string(strings.get(i));
        }section(0x2002,strings.size(),stringData);
        out.align();int codeOffset=out.position();slotValueOffset=codeOffset+16+slotWord*2;
        out.u16(locals+incoming);out.u16(incoming);out.u16(3);out.u16(0);out.u32(0);out.u32(code.size());for(int word:code)out.u16(word);section(0x2001,1,codeOffset);
        int classData=out.position();out.uleb(0);out.uleb(0);out.uleb(1);out.uleb(0);out.uleb(methodIndex(entry));out.uleb(9);out.uleb(codeOffset);section(0x2000,1,classData);
        out.patch(classDefs,typeIndex(owner));out.patch(classDefs+4,0x11);out.patch(classDefs+8,typeIndex(OBJECT));out.patch(classDefs+16,-1);out.patch(classDefs+24,classData);
        out.align();int map=out.position();section(0,1,0);section(0x1000,1,map);sections.sort(Comparator.comparingInt(a->a[2]));
        out.u32(sections.size());for(int[] section:sections){out.u16(section[0]);out.u16(0);out.u32(section[1]);out.u32(section[2]);}
        byte[] file=out.bytes();byte[] magic={'d','e','x','\n','0','3','9',0};System.arraycopy(magic,0,file,0,8);
        put(file,32,file.length);put(file,36,112);put(file,40,0x12345678);put(file,52,map);
        int[] table={strings.size(),stringIds,types.size(),typeIds,protos.size(),protoIds,0,0,methods.size(),methodIds,1,classDefs,file.length-dataStart,dataStart};
        for(int i=0;i<table.length;i++)put(file,56+i*4,table[i]);
        sealer.seal(file);
        new DexFile(file);return file;
    }
    private void section(int type,int count,int offset){sections.add(new int[]{type,count,offset});}
    private static void put(byte[] data,int at,int value){for(int i=0;i<4;i++)data[at+i]=(byte)(value>>>(i*8));}
    private static final class Bytes {
        byte[] data=new byte[65536];int size;
        int position(){return size;}
        void byteValue(int value){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=(byte)value;}
        void zero(int count){for(int i=0;i<count;i++)byteValue(0);}
        void u16(int value){byteValue(value);byteValue(value>>>8);}
        void u32(int value){u16(value);u16(value>>>16);}
        void patch(int at,int value){put(data,at,value);}
        void align(){while((size&3)!=0)byteValue(0);}
        void uleb(int value){do{int next=value&127;value>>>=7;byteValue(next|(value==0?0:128));}while(value!=0);}
        void string(String value){uleb(value.length());for(int i=0;i<value.length();i++){int c=value.charAt(i);
            if(c>0&&c<128)byteValue(c);else if(c<2048){byteValue(0xc0|(c>>>6));byteValue(0x80|(c&63));}
            else{byteValue(0xe0|(c>>>12));byteValue(0x80|((c>>>6)&63));byteValue(0x80|(c&63));}}byteValue(0);}
        byte[] bytes(){return Arrays.copyOf(data,size);}
    }
}
