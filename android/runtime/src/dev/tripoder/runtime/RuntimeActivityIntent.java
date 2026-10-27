package dev.tripoder.runtime;

import android.content.*;
import android.os.Bundle;
import android.os.Parcel;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit Activity capability with a bounded task-flag set and bounded typed data extras. */
final class RuntimeActivityIntent {
    private RuntimeActivityIntent(){}
    // Standard start flags travel on the physical stub intent, so the platform's own task engine applies them to the stub Activities that stand for the guest's;
    // the ledger follows the resulting destroy/attach events like any other. Only flags whose meaning needs the caller's identity or a result channel stay out.
    static final int FLAGS=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED|Intent.FLAG_ACTIVITY_SINGLE_TOP
        |Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_CLEAR_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        |Intent.FLAG_ACTIVITY_NO_HISTORY|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
    static void requireFlags(int flags){if((flags&~FLAGS)!=0)throw new IllegalArgumentException("INTENT_FLAGS_UNSUPPORTED:0x"+Integer.toHexString(flags));}
    static Intent snapshot(Intent input)throws Exception{
        ClassLoader guest=RuntimeEngine.current==null?null:RuntimeEngine.current.guestLoader();if(guest!=null)input.setExtrasClassLoader(guest);
        String before=digest(input);Intent snapshot;Parcel parcel=Parcel.obtain();
        try{input.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>64*1024)throw new IllegalArgumentException("ACTIVITY_PARCEL_UNSUPPORTED");parcel.setDataPosition(0);snapshot=Intent.CREATOR.createFromParcel(parcel);if(parcel.dataAvail()!=0)throw new IllegalArgumentException("ACTIVITY_PARCEL_TRAILING_DATA");}finally{parcel.recycle();}
        // Real platform parcel cloning also owns nested Intent objects and retains Android's
        // origin/security metadata. Never clear creator tokens or launch security protection.
        if(guest!=null)snapshot.setExtrasClassLoader(guest);
        if(!before.equals(digest(snapshot)))throw new IllegalStateException("ACTIVITY_INTENT_MUTATED_DURING_SNAPSHOT");return snapshot;
    }
    static String digest(Intent intent)throws Exception{
        if(intent==null||intent.getComponent()==null||intent.getData()!=null||intent.getType()!=null||intent.getClipData()!=null||intent.getSelector()!=null||intent.getIdentifier()!=null||intent.getSourceBounds()!=null||intent.getPackage()!=null&&!intent.getPackage().equals(intent.getComponent().getPackageName()))throw new IllegalArgumentException("INTENT_SHAPE_UNSUPPORTED");
        requireFlags(intent.getFlags());var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(4);
        text(out,intent.getComponent().flattenToString());out.writeInt(intent.getFlags());text(out,intent.getAction());text(out,intent.getPackage());
        Set<String> categories=intent.getCategories();if(categories!=null&&categories.size()>16)throw new IllegalArgumentException("INTENT_CATEGORIES_TOO_LARGE");out.writeInt(categories==null?0:categories.size());if(categories!=null)for(String category:new TreeSet<>(categories))text(out,category);
        RuntimeActivityValues.extras(out,intent.getExtras());
        out.flush();if(bytes.size()>48*1024)throw new IllegalArgumentException("INTENT_TOO_LARGE");return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    private static void text(DataOutputStream out,String value)throws IOException{
        RuntimeActivityValues.text(out,value);
    }
    static String receivedDigest(Intent physical,Intent visible)throws Exception{
        // Android may add NEW_TASK/BROUGHT_TO_FRONT. The sealed request flag value
        // is fingerprinted; getIntent still exposes the actual delivered flags.
        Intent comparable=new Intent(visible).setFlags(physical.getIntExtra("tripoder.flags",0));
        // Intent copies do not carry the extras class loader; the receiver's guest loader owns any package-defined Parcelable.
        if(RuntimeEngine.current!=null&&RuntimeEngine.current.loader!=null)comparable.setExtrasClassLoader(RuntimeEngine.current.guestLoader());
        return digest(comparable);
    }
    static Intent visible(Intent physical,String pkg,ClassLoader loader){
        RuntimeGuestPayload.prepare(physical,loader);
        String component=RuntimeWire.required(physical.getExtras(),"tripoder.component");Intent visible=new Intent(physical);
        for(String key:new String[]{"callback","operation","generation","component","ticket","delivery","flags","extras","thinRoute","callerKind","callerPackage","callerComponent","callerPhysical","expectsResult"})visible.removeExtra("tripoder."+key);
        if(!physical.getBooleanExtra("tripoder.extras",true)){Bundle remaining=visible.getExtras();if(remaining!=null&&!remaining.isEmpty())throw new SecurityException("ACTIVITY_EXTRAS_PRESENCE_MISMATCH");visible.replaceExtras((Bundle)null);}
        visible.setComponent(new ComponentName(pkg,component));visible.setExtrasClassLoader(loader);return visible;
    }
}
