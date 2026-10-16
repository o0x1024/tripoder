package dev.tripoder.runtime;

import android.content.Intent;
import android.os.*;

/** Result transport is Android's original Intent; admission grants finite scalar values only. */
final class RuntimeActivityResult {
    static void require(Intent result){
        if(result==null)return;
        if(result.getData()!=null||result.getClipData()!=null||result.getSelector()!=null||result.getComponent()!=null||result.getPackage()!=null||result.getAction()!=null||result.getCategories()!=null||result.getFlags()!=0||result.getType()!=null)throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED");
        Bundle extras=result.getExtras();if(extras==null)return;
        if(extras.size()>16||extras.hasFileDescriptors())throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
        for(String key:extras.keySet()){
            if(key==null||key.length()>100||key.startsWith("tripoder."))throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
            Object value=extras.get(key);
            if(value==null||value instanceof Integer||value instanceof Long||value instanceof Boolean||value instanceof Double||value instanceof Float||value instanceof Short||value instanceof Byte||value instanceof Character)continue;
            if(value instanceof String&&((String)value).length()<=16384)continue;
            throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
        }
        Parcel parcel=Parcel.obtain();try{extras.writeToParcel(parcel,0);if(parcel.dataSize()>48*1024)throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");}finally{parcel.recycle();}
    }
    private RuntimeActivityResult(){}
}
