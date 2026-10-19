package dev.tripoder.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded wire-value reader for attributes not exposed by apksig's metadata API.
 * Layout authority: AOSP androidfw ResourceTypes.h, ResXMLTree_attrExt/attribute/Res_value.
 * This retains type + all 32 data bits; it neither resolves resources nor coerces semantics.
 */
final class BinXmlTypedValues {
    private final List<int[][]> elements=new ArrayList<>();
    private int next;
    BinXmlTypedValues(byte[] bytes){
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(bytes.length<8||u16(b,0)!=3||u16(b,2)!=8||u32(b,4)!=bytes.length)throw bad();
        int offset=8;
        while(offset<bytes.length){
            if(bytes.length-offset<8)throw bad();
            int type=u16(b,offset),header=u16(b,offset+2);long length=u32(b,offset+4);
            if(header<8||length<header||length>bytes.length-offset)throw bad();
            int end=offset+(int)length;
            if(type==0x0102){
                if(header!=16||end-offset-header<20||elements.size()>=20000)throw bad();
                int ext=offset+header,start=u16(b,ext+8),stride=u16(b,ext+10),count=u16(b,ext+12);
                if(start<20||stride<20||count>512||(long)header+start+(long)count*stride>length)throw bad();
                int[][] values=new int[count][2];
                for(int i=0;i<count;i++){
                    int value=ext+start+i*stride+12;
                    // Res_value.size is informational: ResXMLParser reads dataType/data at
                    // fixed offsets (value+3, value+4) from the stride, never from size.
                    // AOSP ignores the field; obfuscated manifests (抖音 writes it on ~1/6 of
                    // its attributes) emit 0 instead of 8. Accept both — the kind whitelist
                    // below and the chunk/stride bounds above still bound the read — rather
                    // than reject the whole APK. res0 is reserved and 0 in every manifest seen.
                    int valueSize=u16(b,value);
                    if((valueSize!=8&&valueSize!=0)||b.get(value+2)!=0)throw bad();
                    int kind=b.get(value+3)&255,data=b.getInt(value+4);
                    if(!(kind<=8||kind>=0x10&&kind<=0x12||kind>=0x1c&&kind<=0x1f))throw bad();
                    values[i][0]=kind;values[i][1]=data;
                }
                elements.add(values);
            }
            offset=end;
        }
    }
    int[][] next(int count){
        if(next>=elements.size())throw bad();int[][] values=elements.get(next++);
        if(values.length!=count)throw bad();return values;
    }
    void finish(){if(next!=elements.size())throw bad();}
    static String preserve(int[] value){return String.format(Locale.ROOT,"@typed:0x%02x:0x%08x",value[0],value[1]);}
    private static int u16(ByteBuffer b,int at){return Short.toUnsignedInt(b.getShort(at));}
    private static long u32(ByteBuffer b,int at){return Integer.toUnsignedLong(b.getInt(at));}
    private static IllegalArgumentException bad(){return ApkManifest.bad("MANIFEST_TYPED_VALUE_INVALID");}
}
