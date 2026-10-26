package dev.tripoder.runtime.script;

import java.util.Set;
import java.util.regex.Pattern;

/** Hook JVM signatures are independent of the JSON invocation value ABI. */
public final class JavaTypes {
    private static final String NAME="[^<>.;/\\[\\]\\x00-\\x20\\x7f]+",BINARY=NAME+"(?:\\."+NAME+")*";
    private static final Pattern REFERENCE=Pattern.compile("(?:"+BINARY+"|\\[+(?:[ZBSCIJFD]|L"+BINARY+";))");
    private static final Set<String> VALUES=Set.of("boolean","byte","short","int","long","char","float","double","java.lang.String","[B","byte[]");
    public static boolean reference(String type){return type!=null&&!Set.of("void","boolean","byte","short","int","long","char","float","double","int32","uint32","int64","uint64").contains(type)&&REFERENCE.matcher(type).matches();}
    public static boolean valid(String type,boolean result){return type!=null&&type.length()<=512&&(VALUES.contains(type)||reference(type)||result&&type.equals("void"));}
    public static boolean invocation(String type,boolean result){return VALUES.contains(type)||result&&type.equals("void");}
    private JavaTypes(){}
}
