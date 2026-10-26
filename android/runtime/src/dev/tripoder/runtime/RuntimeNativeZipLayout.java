package dev.tripoder.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Physical ZIP offsets for the pinned 4 KiB platform; ZIP64 is explicitly unsupported here. */
final class RuntimeNativeZipLayout {
    private static long u32(byte[] bytes,int at){return (bytes[at]&255L)|((bytes[at+1]&255L)<<8)|((bytes[at+2]&255L)<<16)|((bytes[at+3]&255L)<<24);}
    private static int u16(byte[] bytes,int at){return (bytes[at]&255)|((bytes[at+1]&255)<<8);}
    /** Validates every required entry and returns each one's absolute data offset in the archive. */
    static Map<String,Long> require(String descriptor,Set<String> required)throws Exception{
        if(required.isEmpty())return Map.of();Set<String> found=new HashSet<>();Map<String,Long> offsets=new HashMap<>();
        try(var input=new RandomAccessFile(descriptor,"r")){
            long length=input.length();int tail=(int)Math.min(length,65557);if(tail<22)throw new SecurityException("PUBLIC_NATIVE_ZIP_EOCD_MISSING");
            byte[] end=new byte[tail];input.seek(length-tail);input.readFully(end);int offset=-1;
            for(int i=tail-22;i>=0;i--)if(u32(end,i)==0x06054b50L&&i+22+u16(end,i+20)==tail){offset=i;break;}
            if(offset<0||u16(end,offset+4)!=0||u16(end,offset+6)!=0)throw new SecurityException("PUBLIC_NATIVE_ZIP_SHAPE_REJECTED");
            int entries=u16(end,offset+10);long cursor=u32(end,offset+16),centralSize=u32(end,offset+12);
            if(entries==65535||cursor==0xffffffffL||centralSize==0xffffffffL||entries!=u16(end,offset+8)||cursor+centralSize!=length-tail+offset)throw new UnsupportedOperationException("PUBLIC_NATIVE_ZIP64_OR_LAYOUT_UNSUPPORTED");
            long centralStart=cursor,endCentral=cursor+centralSize;byte[] header=new byte[46];
            for(int i=0;i<entries;i++){
                input.seek(cursor);input.readFully(header);if(u32(header,0)!=0x02014b50L)throw new SecurityException("PUBLIC_NATIVE_CENTRAL_HEADER_INVALID");
                int names=u16(header,28),extra=u16(header,30),comments=u16(header,32);long next=cursor+46+names+extra+comments;
                if(next>endCentral)throw new SecurityException("PUBLIC_NATIVE_CENTRAL_BOUNDS_INVALID");
                byte[] encoded=new byte[names];input.readFully(encoded);String name=new String(encoded,StandardCharsets.UTF_8);
                if(required.contains(name)){
                    if(!found.add(name)||u16(header,10)!=0||(u16(header,8)&1)!=0||u16(header,34)!=0||u32(header,20)!=u32(header,24))throw new SecurityException("PUBLIC_NATIVE_ZIP_LIBRARY_NOT_STORED");
                    long local=u32(header,42);byte[] data=new byte[30];if(local+30>centralStart)throw new SecurityException("PUBLIC_NATIVE_LOCAL_BOUNDS_INVALID");input.seek(local);input.readFully(data);
                    if(u32(data,0)!=0x04034b50L||u16(data,8)!=0||u16(data,6)!=u16(header,8)||u16(data,26)!=names)throw new SecurityException("PUBLIC_NATIVE_LOCAL_HEADER_INVALID");
                    byte[] localName=new byte[names];input.readFully(localName);long start=local+30+names+u16(data,28);
                    if(!Arrays.equals(encoded,localName)||start%4096!=0||start+u32(header,20)>centralStart)throw new SecurityException("PUBLIC_NATIVE_ZIP_ALIGNMENT_REJECTED");
                    offsets.put(name,start);
                }
                cursor=next;
            }
            if(cursor!=endCentral||!found.equals(required))throw new SecurityException("PUBLIC_NATIVE_ZIP_LIBRARY_SET_CHANGED");
        }
        return offsets;
    }
    private RuntimeNativeZipLayout(){}
}
