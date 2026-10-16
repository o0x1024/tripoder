package dev.tripoder.runtime;

import android.content.Intent;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable scalar payloads, with unambiguous typed request identity. */
final class RuntimeServiceIntent {
    static Intent snapshot(Intent input)throws Exception{Intent copy=new Intent(Objects.requireNonNull(input,"SERVICE_INTENT_REQUIRED"));if(input.getExtras()!=null)copy.replaceExtras(new Bundle(input.getExtras()));digest(copy);return copy;}
    static String digest(Intent intent)throws Exception{
        if(intent.getComponent()==null||intent.getData()!=null||intent.getType()!=null||intent.getClipData()!=null||intent.getSelector()!=null||intent.getFlags()!=0||intent.getCategories()!=null||intent.getPackage()!=null)throw new IllegalArgumentException("SERVICE_INTENT_SHAPE_UNSUPPORTED");
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);text(out,intent.getAction());Bundle extras=intent.getExtras();
        if(extras==null)out.writeInt(-1);else{
            if(extras.size()>16||extras.hasFileDescriptors())throw new IllegalArgumentException("SERVICE_INTENT_TOO_LARGE");
            for(String key:extras.keySet())if(key==null||key.startsWith("tripoder.")||key.length()>100)throw new IllegalArgumentException("SERVICE_EXTRA_KEY_UNSUPPORTED");
            out.writeInt(extras.size());for(String key:new TreeSet<>(extras.keySet())){text(out,key);Object value=extras.get(key);
                if(value==null){out.writeByte(0);continue;}
                if(value instanceof String){out.writeByte(1);text(out,(String)value);}
                else if(value instanceof Integer){out.writeByte(2);out.writeInt((Integer)value);}
                else if(value instanceof Long){out.writeByte(3);out.writeLong((Long)value);}
                else if(value instanceof Boolean){out.writeByte(4);out.writeBoolean((Boolean)value);}
                else if(value instanceof Double){out.writeByte(5);out.writeLong(Double.doubleToRawLongBits((Double)value));}
                else if(value instanceof Float){out.writeByte(6);out.writeInt(Float.floatToRawIntBits((Float)value));}
                else if(value instanceof Short){out.writeByte(7);out.writeShort((Short)value);}
                else if(value instanceof Byte){out.writeByte(8);out.writeByte((Byte)value);}
                else if(value instanceof Character){out.writeByte(9);out.writeChar((Character)value);}
                else throw new IllegalArgumentException("SERVICE_EXTRA_UNSUPPORTED:"+value.getClass().getName());
            }
        }
        out.flush();if(bytes.size()>48*1024)throw new IllegalArgumentException("SERVICE_INTENT_TOO_LARGE");return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    private static void text(DataOutputStream out,String value)throws IOException{if(value==null){out.writeInt(-1);return;}if(value.length()>4096)throw new IllegalArgumentException("SERVICE_TEXT_TOO_LARGE");byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private RuntimeServiceIntent(){}
}
