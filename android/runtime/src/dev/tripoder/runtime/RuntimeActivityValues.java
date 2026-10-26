package dev.tripoder.runtime;

import android.os.Bundle;
import android.content.Intent;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Canonical, bounded data values. Binder authority travels only as an existing owned PendingIntent. */
final class RuntimeActivityValues {
    static final int MAX_BYTES=48*1024;
    static void extras(DataOutputStream out,Bundle extras)throws IOException{bundle(out,extras,0,new int[]{0,0},Collections.newSetFromMap(new IdentityHashMap<>()));}
    private static void bundle(DataOutputStream out,Bundle bundle,int depth,int[] count,Set<Object> path)throws IOException{
        if(bundle==null||bundle.isEmpty()){out.writeInt(-1);return;}
        if(depth>8||!path.add(bundle))throw new IllegalArgumentException("ACTIVITY_BUNDLE_UNSUPPORTED");
        try{
            if(bundle.size()>256)throw new IllegalArgumentException("ACTIVITY_EXTRAS_TOO_MANY");
            for(String key:bundle.keySet())if(key==null||key.startsWith("tripoder.")||key.length()>100)throw new IllegalArgumentException("ACTIVITY_EXTRA_KEY_UNSUPPORTED");
            out.writeInt(bundle.size());for(String key:new TreeSet<>(bundle.keySet())){
                if(++count[0]>256)throw new IllegalArgumentException("ACTIVITY_EXTRAS_TOO_MANY");
                text(out,key);value(out,bundle.get(key),depth,count,path);bound(out);
            }
        }finally{path.remove(bundle);}
    }
    private static void value(DataOutputStream out,Object value,int depth,int[] count,Set<Object> path)throws IOException{
        if(depth>8||++count[1]>4096)throw new IllegalArgumentException("ACTIVITY_VALUE_LIMIT");
        if(value==null){out.writeByte(0);return;}
        if(value instanceof String){out.writeByte(1);text(out,(String)value);}
        else if(value instanceof Integer){out.writeByte(2);out.writeInt((Integer)value);}
        else if(value instanceof Boolean){out.writeByte(3);out.writeBoolean((Boolean)value);}
        else if(value instanceof Long){out.writeByte(4);out.writeLong((Long)value);}
        else if(value instanceof Float){out.writeByte(5);out.writeInt(Float.floatToRawIntBits((Float)value));}
        else if(value instanceof Double){out.writeByte(6);out.writeLong(Double.doubleToRawLongBits((Double)value));}
        else if(value instanceof Short){out.writeByte(7);out.writeShort((Short)value);}
        else if(value instanceof Byte){out.writeByte(8);out.writeByte((Byte)value);}
        else if(value instanceof Character){out.writeByte(9);out.writeChar((Character)value);}
        else if(value instanceof Bundle){out.writeByte(10);bundle(out,(Bundle)value,depth+1,count,path);}
        else if(value instanceof byte[]){out.writeByte(11);size(out,((byte[])value).length);out.write((byte[])value);}
        else if(value instanceof short[]){out.writeByte(12);size(out,((short[])value).length);for(short x:(short[])value)out.writeShort(x);}
        else if(value instanceof int[]){out.writeByte(13);size(out,((int[])value).length);for(int x:(int[])value)out.writeInt(x);}
        else if(value instanceof long[]){out.writeByte(14);size(out,((long[])value).length);for(long x:(long[])value)out.writeLong(x);}
        else if(value instanceof char[]){out.writeByte(15);size(out,((char[])value).length);for(char x:(char[])value)out.writeChar(x);}
        else if(value instanceof boolean[]){out.writeByte(16);size(out,((boolean[])value).length);for(boolean x:(boolean[])value)out.writeBoolean(x);}
        else if(value instanceof float[]){out.writeByte(17);size(out,((float[])value).length);for(float x:(float[])value)out.writeInt(Float.floatToRawIntBits(x));}
        else if(value instanceof double[]){out.writeByte(18);size(out,((double[])value).length);for(double x:(double[])value)out.writeLong(Double.doubleToRawLongBits(x));}
        else if(value instanceof String[]){out.writeByte(19);size(out,((String[])value).length);for(String x:(String[])value){text(out,x);bound(out);}}
        else if(value.getClass()==ArrayList.class){
            if(!path.add(value))throw new IllegalArgumentException("ACTIVITY_LIST_CYCLE");
            try{ArrayList<?> items=(ArrayList<?>)value;out.writeByte(20);size(out,items.size());for(Object item:items)value(out,item,depth+1,count,path);}finally{path.remove(value);}
        }
        else if(value.getClass()==Intent.class){out.writeByte(21);intent(out,(Intent)value,depth+1,count,path);}
        else if(value instanceof android.app.PendingIntent){
            // Parcel cloning retains the real Binder token. Seal its durable authority
            // identity rather than process-local Binder handles or marshalled Parcel bytes.
            RuntimeEngine engine=RuntimeEngine.current;
            if(engine==null)throw new SecurityException("ACTIVITY_PENDING_OWNER_MISSING");
            Bundle token;
            try{token=engine.pendingIntents.view((android.app.PendingIntent)value);}
            catch(Exception error){throw new IOException("ACTIVITY_PENDING_IDENTITY_UNPROVEN",error);}
            if(token==null)throw new SecurityException("ACTIVITY_PENDING_OUTSIDE_INSTALL");
            out.writeByte(23);text(out,RuntimeWire.required(token,"version"));
        }
        else if(value instanceof android.os.Parcelable&&guestOwned(value.getClass())){
            // A Parcelable defined by this package's own loader is application data, not authority: it travels only to components of the same
            // package. Its exact Parcel bytes join the sealed digest; descriptors never do, and the receiver re-creates it with its own loader.
            out.writeByte(22);text(out,value.getClass().getName());android.os.Parcel parcel=android.os.Parcel.obtain();
            try{parcel.writeParcelable((android.os.Parcelable)value,0);if(parcel.hasFileDescriptors())throw new IllegalArgumentException("ACTIVITY_PARCELABLE_DESCRIPTOR_UNSUPPORTED:"+value.getClass().getName());byte[] raw=parcel.marshall();size(out,raw.length);out.write(raw);}
            finally{parcel.recycle();}
        }
        else throw new IllegalArgumentException("ACTIVITY_EXTRA_TYPE_UNSUPPORTED:"+value.getClass().getName());
        bound(out);
    }
    private static boolean guestOwned(Class<?> type){RuntimeEngine engine=RuntimeEngine.current;return engine!=null&&type.getClassLoader()!=null&&engine.ownsLoader(type.getClassLoader());}
    private static void intent(DataOutputStream out,Intent intent,int depth,int[] count,Set<Object> path)throws IOException{
        if(depth>8||!path.add(intent))throw new IllegalArgumentException("ACTIVITY_NESTED_INTENT_CYCLE_OR_DEPTH");
        try{
            if(intent.getComponent()==null||intent.getData()!=null||intent.getType()!=null||intent.getClipData()!=null||intent.getSelector()!=null||intent.getIdentifier()!=null||intent.getSourceBounds()!=null||intent.getPackage()!=null&&!intent.getPackage().equals(intent.getComponent().getPackageName()))throw new IllegalArgumentException("ACTIVITY_NESTED_INTENT_SHAPE_UNSUPPORTED");
            // A nested Intent is data, not permission to launch. Actual dispatch is admitted again.
            int flags=intent.getFlags();if((flags&~(RuntimeActivityIntent.FLAGS|Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT|Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))!=0)throw new IllegalArgumentException("ACTIVITY_NESTED_INTENT_FLAGS_UNSUPPORTED");
            text(out,intent.getComponent().flattenToString());out.writeInt(flags);text(out,intent.getAction());text(out,intent.getPackage());
            Set<String> categories=intent.getCategories();if(categories!=null&&categories.size()>16)throw new IllegalArgumentException("INTENT_CATEGORIES_TOO_LARGE");out.writeInt(categories==null?0:categories.size());if(categories!=null)for(String category:new TreeSet<>(categories))text(out,category);
            bundle(out,intent.getExtras(),depth,count,path);
        }finally{path.remove(intent);}
    }
    private static void size(DataOutputStream out,int n)throws IOException{if(n>4096)throw new IllegalArgumentException("ACTIVITY_EXTRA_ARRAY_TOO_LARGE");out.writeInt(n);}
    private static void bound(DataOutputStream out){if(out.size()>MAX_BYTES)throw new IllegalArgumentException("INTENT_TOO_LARGE");}
    static void text(DataOutputStream out,String value)throws IOException{
        if(value==null){out.writeInt(-1);return;}if(value.length()>4096)throw new IllegalArgumentException("INTENT_TEXT_TOO_LARGE");
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);out.writeInt(bytes.length);out.write(bytes);bound(out);
    }
    private RuntimeActivityValues(){}
}
