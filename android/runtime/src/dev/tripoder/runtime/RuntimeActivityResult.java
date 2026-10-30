package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.Intent;
import android.os.*;

/** Android transports the original Intent. Application objects retain their owner and bounded data-only channel. */
final class RuntimeActivityResult {
    static void require(Intent result){require(result,false);}
    static void require(Intent result,boolean samePackageResult){
        if(result==null)return;
        if(!samePackageResult){
            if(result.getData()!=null||result.getClipData()!=null||result.getSelector()!=null||result.getComponent()!=null||result.getPackage()!=null||result.getAction()!=null||result.getCategories()!=null||result.getFlags()!=0||result.getType()!=null)throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED");
        }else{
            if(result.getSelector()!=null)throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED:selector");
            ComponentName cmp=result.getComponent();
            String pkg=result.getPackage();
            RuntimeEngine engine=RuntimeEngine.current;
            String expectedPkg=engine!=null?engine.packageName:null;
            if(cmp!=null&&expectedPkg!=null&&!expectedPkg.equals(cmp.getPackageName()))throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED:component="+cmp);
            if(pkg!=null&&expectedPkg!=null&&!expectedPkg.equals(pkg))throw new IllegalArgumentException("RESULT_SHAPE_UNSUPPORTED:package="+pkg);
        }
        // Values unparcel lazily with the loader seen at first access; own it before any read.
        if(RuntimeEngine.current!=null&&RuntimeEngine.current.loader!=null)result.setExtrasClassLoader(RuntimeEngine.current.guestLoader());
        Bundle extras=result.getExtras();if(extras==null)return;
        bundle(extras,0,new int[]{0},samePackageResult);
        Parcel parcel=Parcel.obtain();try{
            extras.writeToParcel(parcel,0);
            int maxBytes=samePackageResult?512*1024:48*1024;
            if(parcel.hasFileDescriptors()||parcel.dataSize()>maxBytes)throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
            // marshall rejects Binder objects too, including capabilities hidden
            // inside a package-defined Parcelable. The original Intent still travels
            // through Android; this copy only checks its bounded data-only shape.
            try{parcel.marshall();}catch(RuntimeException capability){throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED:capability");}
        }finally{parcel.recycle();}
    }
    private static void bundle(Bundle extras,int depth,int[] count,boolean samePackageResult){
        int maxDepth=samePackageResult?8:4;
        int maxSize=samePackageResult?128:16;
        if(depth>maxDepth||extras.size()>maxSize||extras.hasFileDescriptors())throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
        for(String key:extras.keySet()){
            if(key==null||key.length()>100||key.startsWith("tripoder.")||++count[0]>(samePackageResult?512:256))throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");
            value(extras.get(key),depth,count,samePackageResult);
        }
    }
    /** Serializable domain objects are admitted only to a recorded same-package Activity caller. */
    private static void value(Object value,int depth,int[] count,boolean samePackageResult){
        if(value==null||value instanceof Integer||value instanceof Long||value instanceof Boolean||value instanceof Double||value instanceof Float||value instanceof Short||value instanceof Byte||value instanceof Character)return;
        if(value instanceof String&&((String)value).length()<=(samePackageResult?65536:16384))return;
        if(value instanceof Bundle){bundle((Bundle)value,depth+1,count,samePackageResult);return;}
        if(value instanceof byte[]&&((byte[])value).length<=(samePackageResult?131072:32768)||value instanceof int[]&&((int[])value).length<=4096||value instanceof long[]&&((long[])value).length<=4096||value instanceof boolean[]&&((boolean[])value).length<=4096)return;
        if(value instanceof String[]&&((String[])value).length<=(samePackageResult?1024:256)){for(String item:(String[])value)if(item!=null&&item.length()>(samePackageResult?65536:16384))throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED");return;}
        if(value instanceof Parcelable){RuntimeEngine engine=RuntimeEngine.current;ClassLoader loader=value.getClass().getClassLoader();if(engine!=null&&(loader==null||loader==Object.class.getClassLoader()||engine.ownsLoader(loader)))return;}
        if(samePackageResult&&value instanceof java.io.Serializable&&!(value instanceof IBinder)){
            RuntimeEngine engine=RuntimeEngine.current;ClassLoader loader=value.getClass().getClassLoader();
            if(engine!=null&&(loader==null||loader==Object.class.getClassLoader()||engine.ownsLoader(loader)))return;
        }
        // Report structural type only. Result values can contain identity
        // verification material and must never be included in diagnostics.
        throw new IllegalArgumentException("RESULT_EXTRAS_UNSUPPORTED:type="+value.getClass().getName());
    }
    private RuntimeActivityResult(){}
}
