package dev.tripoder.selfhook;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/** The boot framework's own preference XML codec and format detection. */
public final class XmlFrameworkAccess {
    private static Method reader,writer;
    private static synchronized void prepare(){
        if(reader!=null)return;
        try{
            Method read=FixedFrameworkMembers.method(65,"resolvePullParser","(Ljava/io/InputStream;)Lcom/android/modules/utils/TypedXmlPullParser;",true);
            Method write=FixedFrameworkMembers.method(65,"newFastSerializer","()Lcom/android/modules/utils/TypedXmlSerializer;",true);
            reader=read;writer=write;
        }catch(Exception error){throw new IllegalStateException("PREFERENCE_XML_FRAMEWORK_UNAVAILABLE",error);}
    }
    public static XmlPullParser parser(InputStream stream){prepare();return (XmlPullParser)FixedFrameworkMembers.call(reader,null,java.util.Objects.requireNonNull(stream));}
    public static XmlSerializer serializer(OutputStream stream)throws java.io.IOException{
        prepare();XmlSerializer xml=(XmlSerializer)FixedFrameworkMembers.call(writer,null);
        // SharedPreferencesImpl uses this text codec via XmlUtils.writeMapXml.
        // Binary XML token UTF fields have a 64KiB bound that String preferences do not.
        xml.setOutput(java.util.Objects.requireNonNull(stream),"UTF-8");return xml;
    }
    private XmlFrameworkAccess(){}
}
