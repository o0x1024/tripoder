package dev.tripoder.runtime;

import android.content.Intent;
import android.os.*;

/** Result transport is Android's original Intent; admission grants finite scalar values only. */
final class RuntimeActivityResult {
    static void require(Intent result){
        if(result==null)return;
        if(result.getData()!=null||result.getClipData()!=null||result.getSelector()!=null||result.getComponent()!=null||result.getPackage()!=null||result.getAction()!=null||result.getCategories()!=null||result.getFlags()!=0||result.getType()!=null)throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED");
        // Values unparcel lazily with the loader seen at first access; own it before any read.
        if(RuntimeEngine.current!=null&&RuntimeEngine.current.loader!=null)result.setExtrasClassLoader(RuntimeEngine.current.loader);
        Bundle extras=result.getExtras();if(extras==null)return;
        bundle(extras,0,new int[]{0});
        Parcel parcel=Parcel.obtain();try{extras.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>48*1024)throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");}finally{parcel.recycle();}
    }
    private static void bundle(Bundle extras,int depth,int[] count){
        if(depth>4||extras.size()>16||extras.hasFileDescriptors())throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
        for(String key:extras.keySet()){
            if(key==null||key.length()>100||key.startsWith("tripoder.")||++count[0]>256)throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
            value(extras.get(key),depth,count);
        }
    }
    /** Finite data only: scalars, bounded text and arrays, nested Bundles, and Parcelables defined by the replying package's own loader. */
    private static void value(Object value,int depth,int[] count){
        if(value==null||value instanceof Integer||value instanceof Long||value instanceof Boolean||value instanceof Double||value instanceof Float||value instanceof Short||value instanceof Byte||value instanceof Character)return;
        if(value instanceof String&&((String)value).length()<=16384)return;
        if(value instanceof Bundle){bundle((Bundle)value,depth+1,count);return;}
        if(value instanceof byte[]&&((byte[])value).length<=32768||value instanceof int[]&&((int[])value).length<=4096||value instanceof long[]&&((long[])value).length<=4096||value instanceof boolean[]&&((boolean[])value).length<=4096)return;
        if(value instanceof String[]&&((String[])value).length<=256){for(String item:(String[])value)if(item!=null&&item.length()>16384)throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");return;}
        if(value instanceof Parcelable){RuntimeEngine engine=RuntimeEngine.current;ClassLoader loader=value.getClass().getClassLoader();if(engine!=null&&loader!=null&&engine.ownsLoader(loader))return;}
        throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
    }
    private RuntimeActivityResult(){}
}
