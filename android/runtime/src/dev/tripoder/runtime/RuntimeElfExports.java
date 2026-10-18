package dev.tripoder.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Declared ELF64 dynamic function exports, never dlsym dependency/global lookup. */
final class RuntimeElfExports {
    static long address(File file,String symbol)throws Exception{
        byte[] wanted=symbol.getBytes(StandardCharsets.UTF_8);
        if(wanted.length==0||wanted.length>256||symbol.indexOf('\0')>=0)throw new IllegalArgumentException("HOOK_EXPORT_NAME");
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            ByteBuffer header=read(input,0,64);long table=header.getLong(40);int count=Short.toUnsignedInt(header.getShort(60));
            if(header.getInt(0)!=0x464c457f||header.get(4)!=2||header.get(5)!=1||header.getShort(18)!=183||header.getShort(58)!=64||count<1||count>4096)throw new IllegalArgumentException("HOOK_EXPORT_SECTIONS_UNSUPPORTED");
            long found=-1;int matches=0;
            for(int index=0;index<count;index++){
                ByteBuffer section=read(input,Math.addExact(table,64L*index),64);if(section.getInt(4)!=11)continue;
                long offset=section.getLong(24),size=section.getLong(32);int strings=section.getInt(40);
                if(section.getLong(56)!=24||size<24||size>24L*262144||size%24!=0||strings<0||strings>=count)throw new IllegalArgumentException("HOOK_EXPORT_TABLE_BOUNDS");
                ByteBuffer stringSection=read(input,Math.addExact(table,64L*strings),64);
                if(stringSection.getInt(4)!=3)throw new IllegalArgumentException("HOOK_EXPORT_STRING_TABLE");
                long stringSize=stringSection.getLong(32);if(stringSize<1||stringSize>16*1024*1024)throw new IllegalArgumentException("HOOK_EXPORT_STRING_BUDGET");
                ByteBuffer names=read(input,stringSection.getLong(24),(int)stringSize),symbols=read(input,offset,(int)size);
                for(int position=0;position<size;position+=24){
                    int name=symbols.getInt(position),info=Byte.toUnsignedInt(symbols.get(position+4)),visibility=symbols.get(position+5)&3,defined=Short.toUnsignedInt(symbols.getShort(position+6));
                    if((info&15)!=2||(info>>>4)!=1&&(info>>>4)!=2||visibility!=0&&visibility!=3||defined==0||defined>=0xff00)continue;
                    if(name<0||name>stringSize-wanted.length-1)continue;
                    boolean equal=names.get(name+wanted.length)==0;for(int n=0;equal&&n<wanted.length;n++)equal=names.get(name+n)==wanted[n];
                    if(equal){found=symbols.getLong(position+8);matches++;}
                }
            }
            if(matches!=1||found<=0)throw new IllegalArgumentException("HOOK_EXPORT_NOT_UNIQUELY_DECLARED");return found;
        }
    }
    static java.util.List<java.util.Map<String,Object>> search(File file,String prefix,int limit)throws Exception{
        if(prefix==null||prefix.length()>256||prefix.indexOf('\0')>=0||limit<1||limit>128)throw new IllegalArgumentException("HOOK_EXPORT_SEARCH_ARGS");
        java.util.List<java.util.Map<String,Object>> out=new java.util.ArrayList<>();
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            ByteBuffer header=read(input,0,64);long table=header.getLong(40);int count=Short.toUnsignedInt(header.getShort(60));
            if(header.getInt(0)!=0x464c457f||header.get(4)!=2||header.get(5)!=1||header.getShort(18)!=183||header.getShort(58)!=64||count<1||count>4096)throw new IllegalArgumentException("HOOK_EXPORT_SECTIONS_UNSUPPORTED");
            for(int index=0;index<count&&out.size()<limit;index++){
                ByteBuffer section=read(input,Math.addExact(table,64L*index),64);if(section.getInt(4)!=11)continue;
                long offset=section.getLong(24),size=section.getLong(32);int strings=section.getInt(40);
                if(section.getLong(56)!=24||size<24||size>24L*262144||size%24!=0||strings<0||strings>=count)throw new IllegalArgumentException("HOOK_EXPORT_TABLE_BOUNDS");
                ByteBuffer stringSection=read(input,Math.addExact(table,64L*strings),64);
                if(stringSection.getInt(4)!=3)throw new IllegalArgumentException("HOOK_EXPORT_STRING_TABLE");
                long stringSize=stringSection.getLong(32);if(stringSize<1||stringSize>16*1024*1024)throw new IllegalArgumentException("HOOK_EXPORT_STRING_BUDGET");
                ByteBuffer names=read(input,stringSection.getLong(24),(int)stringSize),symbols=read(input,offset,(int)size);
                for(int position=0;position<size&&out.size()<limit;position+=24){
                    int name=symbols.getInt(position),info=Byte.toUnsignedInt(symbols.get(position+4)),visibility=symbols.get(position+5)&3,defined=Short.toUnsignedInt(symbols.getShort(position+6));
                    long rva=symbols.getLong(position+8);
                    if((info&15)!=2||(info>>>4)!=1&&(info>>>4)!=2||visibility!=0&&visibility!=3||defined==0||defined>=0xff00||rva<=0)continue;
                    if(name<0||name>=stringSize)continue;
                    int end=name;while(end<stringSize&&names.get(end)!=0&&end-name<=256)end++;
                    if(end<=name||end>=stringSize||end-name>256)continue;
                    byte[] bytes=new byte[end-name];for(int n=0;n<bytes.length;n++)bytes[n]=names.get(name+n);
                    String symbolName=new String(bytes,StandardCharsets.UTF_8);
                    if(symbolName.startsWith(prefix))out.add(java.util.Map.of("symbol",symbolName,"rva","0x"+Long.toHexString(rva)));
                }
            }
            return java.util.List.copyOf(out);
        }
    }
    private static ByteBuffer read(RandomAccessFile input,long offset,int size)throws Exception{if(offset<0||size<0||offset>input.length()-size)throw new IllegalArgumentException("HOOK_EXPORT_BOUNDS");byte[] data=new byte[size];input.seek(offset);input.readFully(data);return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);}
    private RuntimeElfExports(){}
}
