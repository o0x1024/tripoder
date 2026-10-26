package dev.tripoder.runtime;

import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Public SDK operation parcels preserve back references; the runtime never executes a replacement batch. */
final class RuntimeProviderBatch {
    static Bundle snapshot(ArrayList<ContentProviderOperation> input,String authority){
        if(input==null||input.size()>32)throw new IllegalArgumentException("PROVIDER_BATCH_TOO_LARGE");
        for(int i=0;i<input.size();i++){ContentProviderOperation operation=input.get(i);if(operation==null)throw new IllegalArgumentException("PROVIDER_BATCH_NULL_OPERATION");RuntimeBridge.batchSelectionKeys(operation,i);}
        Parcel p=Parcel.obtain();try{p.writeTypedList(input);if(p.hasFileDescriptors()||p.dataSize()>262144)throw new IllegalArgumentException("PROVIDER_BATCH_PAYLOAD_INVALID");byte[] bytes=p.marshall();decode(bytes,authority);Bundle b=new Bundle();b.putByteArray("batch",bytes);return b;}finally{p.recycle();}
    }
    static ArrayList<ContentProviderOperation> decode(byte[] bytes,String authority){
        if(bytes==null||bytes.length>262144)throw new IllegalArgumentException("PROVIDER_BATCH_PAYLOAD_INVALID");
        Parcel p=Parcel.obtain();ArrayList<ContentProviderOperation> operations;
        try{p.unmarshall(bytes,0,bytes.length);p.setDataPosition(0);operations=p.createTypedArrayList(ContentProviderOperation.CREATOR);p.enforceNoDataAvail();}finally{p.recycle();}
        if(operations==null||operations.size()>32)throw new IllegalArgumentException("PROVIDER_BATCH_TOO_LARGE");
        ContentProviderResult[] previous=new ContentProviderResult[operations.size()];
        for(int i=0;i<operations.size();i++){
            ContentProviderOperation operation=operations.get(i);if(operation==null)throw new IllegalArgumentException("PROVIDER_BATCH_NULL_OPERATION");
            Uri uri=operation.getUri();if(uri==null||!"content".equals(uri.getScheme())||!authority.equals(uri.getAuthority())||uri.toString().length()>4096)throw new SecurityException("PROVIDER_BATCH_URI_MISMATCH");
            kind(operation);if(operation.isExceptionAllowed())throw new UnsupportedOperationException("PROVIDER_BATCH_EXCEPTION_RESULT_NOT_IMPLEMENTED");
            // A tiny parcel may contain a huge sparse selection index. Bound the structure before SDK resolution.
            RuntimeBridge.batchSelectionKeys(operation,i);
            // Validate constant values and reference indices without running a guest callback.
            // The synthetic IDs are discarded. Real back references are resolved solely by target.applyBatch.
            RuntimeProviderValues.copy(operation.resolveValueBackReferences(previous,i));
            RuntimeProviderFacade.primitive(operation.resolveExtrasBackReferences(previous,i));
            String[] selection=operation.resolveSelectionArgsBackReferences(previous,i);if(selection!=null){Bundle b=new Bundle();b.putStringArray("selection",selection);RuntimeProviderFacade.primitive(b);}
            previous[i]=new ContentProviderResult(ContentUris.withAppendedId(Uri.parse("content://"+authority+"/validation"),1));
        }
        return operations;
    }
    static String kind(ContentProviderOperation op){if(op.isInsert())return "insert";if(op.isUpdate())return "update";if(op.isDelete())return "delete";if(op.isAssertQuery())return "query";throw new UnsupportedOperationException("PROVIDER_BATCH_OPERATION_NOT_IMPLEMENTED");}
    static void summary(Bundle request,Bundle payload,String authority){
        summary(request,decode(payload.getByteArray("batch"),authority));
    }
    static void summary(Bundle request,ArrayList<ContentProviderOperation> operations){
        String[] uris=new String[operations.size()],kinds=new String[operations.size()];
        for(int i=0;i<operations.size();i++){uris[i]=operations.get(i).getUri().toString();kinds[i]=kind(operations.get(i));}
        Bundle summary=new Bundle();summary.putStringArray("uris",uris);summary.putStringArray("kinds",kinds);
        Parcel p=Parcel.obtain();try{p.writeBundle(summary);if(p.dataSize()>32768)throw new IllegalArgumentException("PROVIDER_BATCH_ROUTES_TOO_LARGE");}finally{p.recycle();}request.putBundle("batchRoutes",summary);
    }
    static Bundle result(ContentProviderResult[] input,int count,String authority){
        if(input==null||input.length!=count)throw new IllegalStateException("PROVIDER_BATCH_RESULT_COUNT_INVALID");ContentProviderResult[] result=new ContentProviderResult[count];
        for(int i=0;i<count;i++){ContentProviderResult value=input[i];if(value==null||value.exception!=null)throw new IllegalStateException("PROVIDER_BATCH_RESULT_UNSUPPORTED");
            if(value.uri!=null){if(!"content".equals(value.uri.getScheme())||!authority.equals(value.uri.getAuthority())||value.uri.toString().length()>8192)throw new SecurityException("PROVIDER_RESULT_URI_INVALID");result[i]=new ContentProviderResult(value.uri);}
            else if(value.count!=null)result[i]=new ContentProviderResult(value.count);else if(value.extras!=null)result[i]=new ContentProviderResult(RuntimeProviderFacade.primitive(value.extras));else throw new IllegalStateException("PROVIDER_BATCH_RESULT_EMPTY");
        }
        Bundle b=new Bundle();b.putParcelableArray("results",result);RuntimeProviderValues.bounded(b);return b;
    }
    private RuntimeProviderBatch(){}
}
