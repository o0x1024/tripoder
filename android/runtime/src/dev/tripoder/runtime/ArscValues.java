package dev.tripoder.runtime;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only lookup of the few manifest attributes (process name, enabled, exported) that Android resolves from resources.arsc before using them. Only plain string and boolean entries are accepted. A resource whose value differs between configurations, a bag, a reference into the framework or any malformed chunk is rejected, never guessed. */
final class ArscValues {
    private static final long MAX_TABLE=64L*1024*1024;
    private static final int TYPE_REFERENCE=0x01,TYPE_STRING=0x03,TYPE_BOOLEAN=0x12;
    private final File apk;
    private ByteBuffer table;
    private int poolPosition=-1;

    private static final class Value {
        final int type,data;final String text;
        Value(int type,int data,String text){this.type=type;this.data=data;this.text=text;}
        boolean same(Value other){return type==other.type&&data==other.data&&java.util.Objects.equals(text,other.text);}
    }

    ArscValues(File apk){this.apk=apk;}

    String string(int id)throws Exception{Value value=resolve(id);if(value.type!=TYPE_STRING)throw bad("ARSC_VALUE_NOT_STRING");return value.text;}
    boolean bool(int id)throws Exception{Value value=resolve(id);if(value.type!=TYPE_BOOLEAN)throw bad("ARSC_VALUE_NOT_BOOLEAN");return value.data!=0;}

    private Value resolve(int id)throws Exception{
        load();
        for(int depth=0;depth<5;depth++){
            Value value=find(id);
            if(value.type!=TYPE_REFERENCE)return value;
            id=value.data;
        }
        throw bad("ARSC_REFERENCE_DEPTH");
    }

    private synchronized void load()throws Exception{
        if(table!=null)return;
        byte[] data;
        try(ZipFile zip=new ZipFile(apk)){
            ZipEntry entry=zip.getEntry("resources.arsc");
            if(entry==null||entry.getSize()<=0||entry.getSize()>MAX_TABLE)throw bad("ARSC_UNAVAILABLE");
            try(InputStream in=zip.getInputStream(entry)){data=in.readNBytes((int)MAX_TABLE+1);}
            if(data.length>MAX_TABLE)throw bad("ARSC_TOO_LARGE");
        }
        ByteBuffer buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(buffer.capacity()<12||u16(buffer,0)!=0x0002)throw bad("ARSC_HEADER");
        table=buffer;
        int end=(int)Math.min(table.capacity(),u32(table,4));
        for(int position=u16(table,2);position+8<=end;){
            int type=u16(table,position);long size=u32(table,position+4);
            if(size<8||position+size>end)throw bad("ARSC_CHUNK");
            // The first string pool of the table holds every string value.
            if(type==0x0001&&poolPosition<0)poolPosition=position;
            position+=(int)size;
        }
        if(poolPosition<0)throw bad("ARSC_NO_VALUE_POOL");
    }

    private Value find(int id)throws Exception{
        int packageId=id>>>24,typeId=(id>>>16)&0xff,entry=id&0xffff;
        if(packageId==0x01)throw bad("ARSC_FRAMEWORK_REFERENCE");
        int end=(int)Math.min(table.capacity(),u32(table,4));
        // Android picks the SDK-qualified value that applies to the device. Any other qualifier cannot be evaluated here, so it may only agree with that choice.
        Value best=null;int bestSdk=-1;java.util.List<Value> others=new java.util.ArrayList<>();boolean defined=false;
        for(int position=u16(table,2);position+8<=end;){
            int type=u16(table,position);long size=u32(table,position+4);
            if(size<8||position+size>end)throw bad("ARSC_CHUNK");
            if(type==0x0200&&(int)u32(table,position+8)==packageId){
                int inner=position+u16(table,position+2),limit=position+(int)size;
                while(inner+8<=limit){
                    int innerType=u16(table,inner);long innerSize=u32(table,inner+4);
                    if(innerSize<8||inner+innerSize>limit)throw bad("ARSC_CHUNK");
                    if(innerType==0x0201&&(table.get(inner+8)&0xff)==typeId){
                        Value value=entry(inner,(int)innerSize,entry);
                        if(value!=null){
                            defined=true;int sdk=configSdk(inner,(int)innerSize);
                            if(sdk<0)others.add(value);
                            else if(sdk<=android.os.Build.VERSION.SDK_INT&&sdk>bestSdk){best=value;bestSdk=sdk;}
                        }
                    }
                    inner+=(int)innerSize;
                }
            }
            position+=(int)size;
        }
        if(!defined)throw bad("ARSC_ENTRY_MISSING:0x"+Integer.toHexString(id));
        if(best==null)throw bad("ARSC_NO_APPLICABLE_CONFIG:0x"+Integer.toHexString(id));
        for(Value other:others)if(!other.same(best))throw bad("ARSC_CONFIG_DEPENDENT:0x"+Integer.toHexString(id));
        return best;
    }

    /** The SDK version a type chunk's configuration is qualified by (0 for the default), or -1 when it carries any other qualifier. */
    private int configSdk(int chunk,int chunkSize)throws Exception{
        int config=chunk+20;long size=u32(table,config);
        if(size<28||config+size>chunk+chunkSize)throw bad("ARSC_CONFIG");
        for(int i=4;i<24;i++)if(table.get(config+i)!=0)return -1;
        if(u16(table,config+26)!=0)return -1;
        for(long i=28;i<size;i++)if(table.get(config+(int)i)!=0)return -1;
        return u16(table,config+24);
    }

    /** The entry in one configuration's type chunk, or null when that configuration does not define it. */
    private Value entry(int chunk,int size,int index)throws Exception{
        int headerSize=u16(table,chunk+2),flags=table.get(chunk+9)&0xff;long count=u32(table,chunk+12),entriesStart=u32(table,chunk+16);
        if(headerSize<20||count>0x10000||entriesStart>size)throw bad("ARSC_TYPE_CHUNK");
        long offset;
        if((flags&0x01)!=0){
            long found=-1;
            for(long i=0;i<count;i++){long word=u32(table,chunk+headerSize+(int)(i*4));if((word&0xffff)==index){found=(word>>>16)*4;break;}}
            if(found<0)return null;offset=found;
        }else{
            if(index>=count)return null;
            if((flags&0x02)!=0){int small=u16(table,chunk+headerSize+index*2);if(small==0xffff)return null;offset=small*4L;}
            else{long wide=u32(table,chunk+headerSize+index*4);if(wide==0xffffffffL)return null;offset=wide;}
        }
        long at=chunk+entriesStart+offset;
        if(at<chunk||at+16>chunk+size)throw bad("ARSC_ENTRY_RANGE");
        int entrySize=u16(table,(int)at),entryFlags=u16(table,(int)at+2);
        if((entryFlags&0x0001)!=0||(entryFlags&0x0008)!=0)throw bad("ARSC_ENTRY_NOT_PLAIN");
        long value=at+entrySize;
        if(entrySize<8||value+8>chunk+size)throw bad("ARSC_ENTRY_RANGE");
        int dataType=table.get((int)value+3)&0xff;int data=(int)u32(table,(int)value+4);
        return new Value(dataType,data,dataType==TYPE_STRING?poolString(data):null);
    }

    private String poolString(int index)throws Exception{
        int header=u16(table,poolPosition+2);long count=u32(table,poolPosition+8),flags=u32(table,poolPosition+16),start=u32(table,poolPosition+20);
        if(index<0||index>=count)throw bad("ARSC_STRING_INDEX");
        long at=poolPosition+start+u32(table,poolPosition+header+index*4);
        if(at<0||at+2>table.capacity())throw bad("ARSC_STRING_RANGE");
        int p=(int)at;
        if((flags&0x100)!=0){
            int skip=table.get(p)&0x80;p+=skip!=0?2:1;
            int length=table.get(p)&0xff;p++;if((length&0x80)!=0){length=((length&0x7f)<<8)|(table.get(p)&0xff);p++;}
            if(length>4096||p+length>table.capacity())throw bad("ARSC_STRING_RANGE");
            byte[] bytes=new byte[length];table.get(p,bytes);return new String(bytes,StandardCharsets.UTF_8);
        }
        int length=u16(table,p);p+=2;if((length&0x8000)!=0){length=((length&0x7fff)<<16)|u16(table,p);p+=2;}
        if(length>4096||p+(long)length*2>table.capacity())throw bad("ARSC_STRING_RANGE");
        byte[] bytes=new byte[length*2];table.get(p,bytes);return new String(bytes,StandardCharsets.UTF_16LE);
    }

    private static int u16(ByteBuffer b,int at){if(at<0||at+2>b.capacity())throw new IllegalArgumentException("ARSC_RANGE");return b.getShort(at)&0xffff;}
    private static long u32(ByteBuffer b,int at){if(at<0||at+4>b.capacity())throw new IllegalArgumentException("ARSC_RANGE");return b.getInt(at)&0xffffffffL;}
    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException(reason);}
}
