package dev.tripoder.runtime;

import java.io.*;
import java.nio.*;
import java.util.HexFormat;
import org.json.*;

/** Bounded ELF64/AArch64 GNU build-id reader for an already verified artifact. */
final class RuntimeElfIdentity {
    static JSONObject inspect(File file)throws Exception{
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            ByteBuffer header=read(input,0,64);
            if(header.getInt(0)!=0x464c457f||header.get(4)!=2||header.get(5)!=1||header.getShort(16)!=3||header.getShort(18)!=183)throw new IllegalArgumentException("HOOK_ELF_UNSUPPORTED");
            long phoff=header.getLong(32);int size=Short.toUnsignedInt(header.getShort(54)),count=Short.toUnsignedInt(header.getShort(56));
            if(size!=56||count<1||count>256)throw new IllegalArgumentException("HOOK_ELF_HEADERS");
            boolean zeroLoad=false;long virtualSize=0;String buildId=null;JSONArray notes=new JSONArray();
            for(int i=0;i<count;i++){
                ByteBuffer ph=read(input,Math.addExact(phoff,(long)i*size),size);int type=ph.getInt(0);long offset=ph.getLong(8),virtual=ph.getLong(16),bytes=ph.getLong(32);
                if(type==1&&offset==0&&virtual==0)zeroLoad=true;
                if(type==1){long memory=ph.getLong(40);if(virtual<0||memory<0)throw new IllegalArgumentException("HOOK_ELF_LOAD_BOUNDS");virtualSize=Math.max(virtualSize,Math.addExact(virtual,memory));}
                if(type!=4)continue;
                if(bytes<0||bytes>65536||virtual<0)throw new IllegalArgumentException("HOOK_ELF_NOTE_SIZE");ByteBuffer data=read(input,offset,(int)bytes);
                for(int p=0;p+12<=bytes;){
                    int names=data.getInt(p),desc=data.getInt(p+4),kind=data.getInt(p+8);if(names<0||desc<0||names>1024||desc>4096)throw new IllegalArgumentException("HOOK_ELF_NOTE_SHAPE");
                    int nameAt=p+12,descAt=nameAt+align(names),next=Math.addExact(descAt,align(desc));if(next>bytes||next<=p)throw new IllegalArgumentException("HOOK_ELF_NOTE_BOUNDS");
                    if(kind==3&&names==4&&data.getInt(nameAt)==0x00554e47){
                        if(desc<4||desc>64||buildId!=null)throw new IllegalArgumentException("HOOK_ELF_BUILD_ID");byte[] value=new byte[desc];data.position(descAt);data.get(value);buildId=HexFormat.of().formatHex(value);
                        notes.put(new JSONObject().put("virtualAddress",Math.addExact(virtual,descAt)).put("size",desc));
                    }
                    p=next;
                }
            }
            if(!zeroLoad||buildId==null||virtualSize<=0||virtualSize>Integer.MAX_VALUE)throw new IllegalArgumentException("HOOK_ELF_BUILD_ID_MISSING");return new JSONObject().put("buildId",buildId).put("buildIdNotes",notes).put("virtualSize",virtualSize);
        }
    }
    private static int align(int value){return (value+3)&~3;}
    private static ByteBuffer read(RandomAccessFile input,long offset,int size)throws Exception{if(offset<0||size<0||offset>input.length()-size)throw new IllegalArgumentException("HOOK_ELF_BOUNDS");byte[] bytes=new byte[size];input.seek(offset);input.readFully(bytes);return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private RuntimeElfIdentity(){}
}
