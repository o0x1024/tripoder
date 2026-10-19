package dev.tripoder.runtime;

import android.content.ContentValues;
import android.os.*;
import java.util.Map;

/** Typed, bounded snapshots. Binder parceling is the wire format, never guest Java serialization. */
final class RuntimeProviderValues {
    static ContentValues copy(ContentValues values){
        if(values==null)return null;
        if(values.size()>2048)throw new IllegalArgumentException("PROVIDER_VALUES_TOO_LARGE");
        ContentValues out=new ContentValues(values.size());
        for(Map.Entry<String,Object> entry:values.valueSet()){
            String key=entry.getKey();Object v=entry.getValue();
            if(key==null||key.isEmpty()||key.length()>512)throw new IllegalArgumentException("PROVIDER_VALUE_KEY_INVALID");
            if(v==null)out.putNull(key);
            else if(v instanceof String){if(((String)v).length()>524288)throw new IllegalArgumentException("PROVIDER_VALUE_TOO_LARGE");out.put(key,(String)v);}
            else if(v instanceof Byte)out.put(key,(Byte)v);else if(v instanceof Short)out.put(key,(Short)v);
            else if(v instanceof Integer)out.put(key,(Integer)v);else if(v instanceof Long)out.put(key,(Long)v);
            else if(v instanceof Float)out.put(key,(Float)v);else if(v instanceof Double)out.put(key,(Double)v);
            else if(v instanceof Boolean)out.put(key,(Boolean)v);
            else if(v instanceof byte[]){if(((byte[])v).length>524288)throw new IllegalArgumentException("PROVIDER_VALUE_TOO_LARGE");out.put(key,((byte[])v).clone());}
            else throw new IllegalArgumentException("PROVIDER_VALUE_TYPE_UNSUPPORTED");
        }
        Parcel parcel=Parcel.obtain();try{out.writeToParcel(parcel,0);if(parcel.dataSize()>524288)throw new IllegalArgumentException("PROVIDER_VALUES_TOO_LARGE");}finally{parcel.recycle();}
        return out;
    }
    static Bundle single(ContentValues values,Bundle extras){Bundle b=new Bundle();b.putParcelable("values",copy(values));b.putBundle("extras",RuntimeProviderFacade.primitive(extras));bounded(b);return b;}
    static Bundle bulk(ContentValues[] input){
        if(input==null||input.length>256)throw new IllegalArgumentException("PROVIDER_BULK_INVALID");
        ContentValues[] copy=new ContentValues[input.length];for(int i=0;i<input.length;i++)copy[i]=copy(input[i]);
        Bundle b=new Bundle();b.putParcelableArray("values",copy);bounded(b);return b;
    }
    static void bounded(Bundle b){Parcel p=Parcel.obtain();try{p.writeBundle(b);if(p.dataSize()>786432)throw new IllegalArgumentException("PROVIDER_PAYLOAD_TOO_LARGE");}finally{p.recycle();}}
    static Bundle selection(String where,String[] args){Bundle b=new Bundle();if(where!=null)b.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,where);if(args!=null)b.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args.clone());return b;}
    private RuntimeProviderValues(){}
}
