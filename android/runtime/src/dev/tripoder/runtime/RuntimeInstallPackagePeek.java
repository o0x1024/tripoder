package dev.tripoder.runtime;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Names the package an install request will touch before admission, so an unrelated unknown effect does not fence it.
 *  The answer is only a claim: the install transaction re-checks it against the materialized artifact set and aborts on a mismatch.
 *  Anything unusual yields null, which keeps the whole-space scope. */
final class RuntimeInstallPackagePeek {
    private static final int MAX_MANIFEST=4*1024*1024;
    private RuntimeInstallPackagePeek(){}
    static String of(Bundle input){
        try{
            Object names=input.get("names");if(!(names instanceof ArrayList)||((ArrayList<?>)names).isEmpty()||((ArrayList<?>)names).size()>64)return null;
            String common=null;
            for(int i=0;i<((ArrayList<?>)names).size();i++){
                Object value=input.get("fd"+i);if(value==null||value.getClass()!=ParcelFileDescriptor.class)return null;
                String pkg=read((ParcelFileDescriptor)value);if(pkg==null||common!=null&&!common.equals(pkg))return null;common=pkg;
            }
            return common;
        }catch(Throwable unreadable){return null;}
    }
    private static String read(ParcelFileDescriptor descriptor)throws Exception{
        var fd=descriptor.getFileDescriptor();var stat=Os.fstat(fd);
        // Only a regular file may be reopened through /proc: a pipe would block here, before any validation ran.
        if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_size<=0||stat.st_size>ArtifactSet.MAX_APK_BYTES)return null;
        // A new open file description, so the offset of the descriptor the install later reads is untouched.
        try(ZipFile zip=new ZipFile(new File("/proc/self/fd/"+descriptor.getFd()))){
            ZipEntry entry=zip.getEntry("AndroidManifest.xml");if(entry==null||entry.getSize()<=0||entry.getSize()>MAX_MANIFEST)return null;
            byte[] bytes;try(InputStream in=zip.getInputStream(entry)){bytes=in.readNBytes(MAX_MANIFEST+1);}
            if(bytes.length>MAX_MANIFEST)return null;
            AndroidBinXmlParser parser=new AndroidBinXmlParser(ByteBuffer.wrap(bytes));
            while(parser.getEventType()!=AndroidBinXmlParser.EVENT_START_ELEMENT){if(parser.getEventType()==AndroidBinXmlParser.EVENT_END_DOCUMENT)return null;parser.next();}
            if(!"manifest".equals(parser.getName())||parser.getNamespace()!=null&&!parser.getNamespace().isEmpty())return null;
            for(int i=0;i<parser.getAttributeCount();i++){
                String ns=parser.getAttributeNamespace(i);
                if("package".equals(parser.getAttributeName(i))&&(ns==null||ns.isEmpty())&&parser.getAttributeValueType(i)==AndroidBinXmlParser.VALUE_TYPE_STRING){
                    String pkg=parser.getAttributeStringValue(i);
                    return pkg!=null&&pkg.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")?pkg:null;
                }
            }
            return null;
        }
    }
}
