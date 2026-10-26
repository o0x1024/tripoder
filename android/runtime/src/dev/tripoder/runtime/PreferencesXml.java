package dev.tripoder.runtime;

import android.system.*;
import dev.tripoder.selfhook.XmlFrameworkAccess;
import org.xmlpull.v1.*;
import java.io.*;
import java.util.*;

/** Independently implemented SDK preference value format; malformed input is never an empty map. */
final class PreferencesXml {
    private static final long MAX_BYTES=8L*1024*1024;
    static Map<String,Object> read(File file)throws Exception{
        if(!file.exists())return new LinkedHashMap<>();
        if(file.length()>MAX_BYTES)throw new IOException("PREFERENCES_SIZE_LIMIT");
        try(var stream=new FileInputStream(Os.open(file.toString(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0))){
            XmlPullParser p=XmlFrameworkAccess.parser(stream);int first=p.nextTag();if(first!=XmlPullParser.START_TAG||!"map".equals(p.getName())||p.getAttributeCount()!=0)throw new IOException("PREFERENCES_MAP_REQUIRED");
            Map<String,Object> values=new LinkedHashMap<>();
            while(p.nextTag()==XmlPullParser.START_TAG){
                String type=p.getName(),key=p.getAttributeValue(null,"name");if(key==null||values.containsKey(key)||values.size()>=100000)throw new IOException("PREFERENCES_KEY_INVALID");
                Object value;
                if(type.equals("string")){if(p.getAttributeCount()!=1)throw new IOException("PREFERENCES_ATTRIBUTE_INVALID");value=p.nextText();}
                else if(type.equals("set")){
                    if(p.getAttributeCount()!=1)throw new IOException("PREFERENCES_ATTRIBUTE_INVALID");Set<String> items=new LinkedHashSet<>();
                    while(p.nextTag()==XmlPullParser.START_TAG){if(!p.getName().equals("string")||p.getAttributeCount()!=0||!items.add(p.nextText()))throw new IOException("PREFERENCES_SET_INVALID");}
                    if(!p.getName().equals("set"))throw new IOException("PREFERENCES_SET_INVALID");value=items;
                }else{
                    String encoded=p.getAttributeValue(null,"value");if(encoded==null||p.getAttributeCount()!=2)throw new IOException("PREFERENCES_VALUE_INVALID");
                    switch(type){case "int":value=Integer.parseInt(encoded);break;case "long":value=Long.parseLong(encoded);break;case "float":value=Float.parseFloat(encoded);break;case "boolean":if(!encoded.equals("true")&&!encoded.equals("false"))throw new IOException("PREFERENCES_BOOLEAN_INVALID");value=Boolean.valueOf(encoded);break;default:throw new IOException("PREFERENCES_TYPE_INVALID:"+type);}
                    if(p.nextTag()!=XmlPullParser.END_TAG||!p.getName().equals(type))throw new IOException("PREFERENCES_VALUE_INVALID");
                }
                values.put(key,value);
            }
            if(!p.getName().equals("map"))throw new IOException("PREFERENCES_MAP_INVALID");
            int event;while((event=p.next())!=XmlPullParser.END_DOCUMENT)if(event!=XmlPullParser.TEXT||!p.isWhitespace())throw new IOException("PREFERENCES_TRAILING_CONTENT");
            return values;
        }
    }
    static void write(RuntimeStorage storage,File file,Map<String,Object> values)throws Exception{
        File stage=new File(file+".new");storage.check(file);storage.check(stage);
        try(var stream=new FileOutputStream(Os.open(stage.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_TRUNC|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0600))){
            XmlSerializer xml=XmlFrameworkAccess.serializer(stream);xml.startDocument("UTF-8",true);xml.startTag(null,"map");
            for(var item:values.entrySet()){
                Object value=item.getValue();String tag=value instanceof String?"string":value instanceof Integer?"int":value instanceof Long?"long":value instanceof Float?"float":value instanceof Boolean?"boolean":value instanceof Set?"set":null;
                if(tag==null)throw new IOException("PREFERENCES_TYPE_INVALID");xml.startTag(null,tag);xml.attribute(null,"name",item.getKey());
                if(value instanceof String)xml.text((String)value);
                else if(value instanceof Set)for(Object entry:(Set<?>)value){xml.startTag(null,"string");xml.text((String)entry);xml.endTag(null,"string");}
                else xml.attribute(null,"value",value.toString());
                xml.endTag(null,tag);
            }
            xml.endTag(null,"map");xml.endDocument();xml.flush();if(stream.getChannel().size()>MAX_BYTES)throw new IOException("PREFERENCES_SIZE_LIMIT");stream.getFD().sync();
        }
        Os.rename(stage.toString(),file.toString());
    }
    private PreferencesXml(){}
}
