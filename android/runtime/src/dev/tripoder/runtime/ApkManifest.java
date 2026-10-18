package dev.tripoder.runtime;

import com.android.apksig.internal.apk.AndroidBinXmlParser;
import java.nio.ByteBuffer;
import java.util.*;

/** Metadata only. Parsing a declaration never grants permission to execute its component.
 * The binary XML parser is pinned to the SDK 37 apksig dependency, not PackageManager.
 */
public final class ApkManifest {
    private static final String ANDROID="http://schemas.android.com/apk/res/android";
    public final String packageName,splitName,configForSplit,applicationName,applicationProcess;
    public final long versionCode;
    public final int minSdk,targetSdk;
    public final boolean featureSplit,splitRequired;
    public final List<String> usesSplits,splitTypes,requiredSplitTypes;
    public final List<Component> components;
    public final Element declaration,application;

    public static final class Element {
        public final String name,namespace;
        public final Map<String,String> attributes;
        public final List<Element> children;
        Element(String name,String namespace,Map<String,String> attributes,List<Element> children){
            this.name=name;this.namespace=namespace;this.attributes=Collections.unmodifiableMap(new TreeMap<>(attributes));this.children=Collections.unmodifiableList(new ArrayList<>(children));
        }
        String raw(String key,String defaultValue){return attributes.getOrDefault(key,defaultValue);}
        String android(String key,String defaultValue){return raw("android:"+key,defaultValue);}
        Map<String,Object> json(){Map<String,Object> out=new TreeMap<>();out.put("name",name);out.put("namespace",namespace);out.put("attributes",attributes);List<Object> cs=new ArrayList<>();for(Element c:children)cs.add(c.json());out.put("children",cs);return out;}
    }
    public static final class Component {
        public final String type,name,process,permission,authorities,targetActivity;
        /** Null means a resource reference requiring device configuration resolution. */
        public final Boolean exported,enabled;
        public final Map<String,String> attributes;
        public final Element declaration;
        Component(Element e,ApkManifest m,Element app){
            declaration=e;type=e.name;attributes=e.attributes;
            name=className(m.packageName,text(e.android("name",null),"COMPONENT_NAME"));
            String rawProcess=e.android("process",app.android("process",m.applicationProcess));
            process=processName(m.packageName,rawProcess!=null&&rawProcess.isEmpty()?m.applicationProcess:text(rawProcess,"PROCESS"));
            permission=text(e.android("permission",app.android("permission","")),"PERMISSION");
            authorities=text(e.android("authorities",""),"AUTHORITIES");
            targetActivity=e.android("targetActivity",null)==null?"":className(m.packageName,text(e.android("targetActivity",null),"ALIAS_TARGET"));
            boolean filter=false;for(Element c:e.children)if(c.name.equals("intent-filter"))filter=true;
            if(filter&&m.targetSdk>=31&&!attributes.containsKey("android:exported"))throw bad("EXPORTED_REQUIRED");
            exported=declaredBool(e.android("exported",null),type.equals("provider")?m.targetSdk<17:filter);
            Boolean appEnabled=declaredBool(app.android("enabled",null),true),componentEnabled=declaredBool(e.android("enabled",null),true);
            enabled=Boolean.FALSE.equals(appEnabled)||Boolean.FALSE.equals(componentEnabled)?Boolean.FALSE:appEnabled==null||componentEnabled==null?null:Boolean.TRUE;
        }
        Component(Component declaration,boolean exported,boolean enabled){
            this.declaration=declaration.declaration;type=declaration.type;name=declaration.name;process=declaration.process;permission=declaration.permission;authorities=declaration.authorities;targetActivity=declaration.targetActivity;attributes=declaration.attributes;this.exported=exported;this.enabled=enabled;
        }
        Component(Element declaration,String type,String name,String process,String permission,String authorities,String targetActivity,Boolean exported,Boolean enabled){
            this.declaration=declaration;this.type=type;this.name=name;this.process=process;this.permission=permission;this.authorities=authorities;this.targetActivity=targetActivity;this.attributes=declaration.attributes;this.exported=exported;this.enabled=enabled;
        }
        Map<String,Object> json(){Map<String,Object> out=new TreeMap<>();out.put("type",type);out.put("name",name);out.put("process",process);out.put("permission",permission);out.put("authorities",authorities);out.put("targetActivity",targetActivity);out.put("exported",exported);out.put("enabled",enabled);out.put("declaration",declaration.json());return out;}
    }
    static ApkManifest fromJsonReader(android.util.JsonReader r)throws Exception{
        return new ApkManifest(elementFromJsonReader(r,0));
    }
    private static Element elementFromJsonReader(android.util.JsonReader r,int depth)throws Exception{
        String name=null,namespace="";Map<String,String> attrs=new TreeMap<>();List<Element> children=new ArrayList<>();
        r.beginObject();
        while(r.hasNext()){
            switch(r.nextName()){
                case "name"->name=r.nextString();
                case "namespace"->namespace=r.nextString();
                case "attributes"->{r.beginObject();while(r.hasNext())attrs.put(r.nextName(),r.nextString());r.endObject();}
                case "children"->{
                    if(depth==0){
                        r.beginArray();
                        while(r.hasNext()){
                            Element child=elementFromJsonReader(r,1);
                            if("application".equals(child.name)||"uses-sdk".equals(child.name)||"uses-split".equals(child.name))children.add(child);
                        }
                        r.endArray();
                    }else r.skipValue();
                }
                default->r.skipValue();
            }
        }
        r.endObject();
        return new Element(name,namespace,attrs,children);
    }
    private ApkManifest(Element root){
        declaration=root;if(!root.name.equals("manifest")||!root.namespace.isEmpty())throw bad("MANIFEST_ROOT");
        packageName=text(root.raw("package",null),"PACKAGE");if(!packageName.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+"))throw bad("PACKAGE");
        splitName=text(root.raw("split",""),"SPLIT");if(!splitName.isEmpty())identifier(splitName);if(splitName.equals("base"))throw bad("RESERVED_SPLIT_NAME");
        if(root.attributes.containsKey("configForSplit")&&root.attributes.containsKey("android:configForSplit"))throw bad("CONFIG_SPLIT_AMBIGUOUS");
        configForSplit=text(root.raw("configForSplit",root.android("configForSplit","")),"CONFIG_SPLIT");if(!configForSplit.isEmpty())identifier(configForSplit);
        long low=number(root.android("versionCode","0")),high=number(root.android("versionCodeMajor","0"));
        if(low<0||low>0xffffffffL||high<0||high>0x7fffffffL)throw bad("VERSION");versionCode=(high<<32)|low;
        featureSplit=bool(root.android("isFeatureSplit",null),false);
        Element app=null,sdk=null;List<String> deps=new ArrayList<>();
        for(Element e:root.children){if(e.name.equals("application")){if(app!=null)throw bad("DUPLICATE_APPLICATION");app=e;}if(e.name.equals("uses-sdk")){if(sdk!=null)throw bad("DUPLICATE_SDK");sdk=e;}if(e.name.equals("uses-split")){String n=text(e.android("name",null),"USES_SPLIT");identifier(n);if(deps.contains(n))throw bad("DUPLICATE_DEPENDENCY");deps.add(n);}}
        if(app==null)throw bad("APPLICATION_REQUIRED");application=app;
        minSdk=sdk==null?1:integer(sdk.android("minSdkVersion","1"));targetSdk=sdk==null?minSdk:integer(sdk.android("targetSdkVersion",Integer.toString(minSdk)));
        if(minSdk>37||minSdk<1||targetSdk<1)throw bad("SDK_UNSUPPORTED");
        applicationName=app.android("name",null)==null?"":className(packageName,text(app.android("name",null),"APPLICATION_NAME"));
        applicationProcess=processName(packageName,text(app.android("process",packageName),"PROCESS"));
        splitRequired=bool(app.android("isSplitRequired",root.android("isSplitRequired",null)),false);
        splitTypes=types(root.android("splitTypes",""));requiredSplitTypes=types(root.android("requiredSplitTypes",""));
        Collections.sort(deps);usesSplits=Collections.unmodifiableList(deps);
        List<Component> cs=new ArrayList<>();
        // A declaration is not a resolver entry: Android manifests can repeat a
        // component class (including Providers with distinct authority metadata).
        for(Element e:app.children)if(Set.of("activity","activity-alias","service","receiver","provider").contains(e.name))cs.add(new Component(e,this,app));
        components=Collections.unmodifiableList(cs);
    }
    public static ApkManifest parse(byte[] binaryXml)throws Exception {return parse(binaryXml,null);}
    /** Only a process name written as a resource reference is resolved. The old parser rejected such a manifest outright, so no installed descriptor depends on it. Every other reference stays textual: the descriptor recorded at install time includes these attribute values verbatim and is re-derived byte for byte when the install is verified, so changing how an attribute such as enabled is read would invalidate installs that already exist. */
    private static String resolved(ArscValues values,String name,int id)throws Exception{
        if(values!=null&&(name.equals("process")||name.equals("authorities")))return values.string(id);
        return "@0x"+Integer.toHexString(id);
    }
    public static ApkManifest parse(byte[] binaryXml,ArscValues resources)throws Exception {
        if(binaryXml==null||binaryXml.length==0||binaryXml.length>4*1024*1024)throw bad("MANIFEST_SIZE");
        AndroidBinXmlParser p=new AndroidBinXmlParser(ByteBuffer.wrap(binaryXml));
        BinXmlTypedValues typed=new BinXmlTypedValues(binaryXml);
        Deque<Node> stack=new ArrayDeque<>();Element root=null;int count=0;
        while(p.getEventType()!=AndroidBinXmlParser.EVENT_END_DOCUMENT){
            if(p.getEventType()==AndroidBinXmlParser.EVENT_START_ELEMENT){
                if(++count>20000||stack.size()>64||p.getAttributeCount()>512)throw bad("MANIFEST_COMPLEXITY");
                Map<String,String> attrs=new TreeMap<>();
                int[][] values=typed.next(p.getAttributeCount());
                for(int i=0;i<p.getAttributeCount();i++){
                    String ns=p.getAttributeNamespace(i);if(ns==null)ns="";
                    String key=(ns.isEmpty()?"":ns.equals(ANDROID)?"android:":"{"+ns+"}")+p.getAttributeName(i);
                    String value;switch(p.getAttributeValueType(i)){
                        case AndroidBinXmlParser.VALUE_TYPE_STRING:value=p.getAttributeStringValue(i);break;
                        case AndroidBinXmlParser.VALUE_TYPE_INT:value=Long.toString(Integer.toUnsignedLong(p.getAttributeIntValue(i)));break;
                        case AndroidBinXmlParser.VALUE_TYPE_BOOLEAN:value=Boolean.toString(p.getAttributeBooleanValue(i));break;
                        case AndroidBinXmlParser.VALUE_TYPE_REFERENCE:value=ANDROID.equals(ns)?resolved(resources,p.getAttributeName(i),p.getAttributeIntValue(i)):"@0x"+Integer.toHexString(p.getAttributeIntValue(i));break;
                        case AndroidBinXmlParser.VALUE_TYPE_UNSUPPORTED:value=BinXmlTypedValues.preserve(values[i]);break;
                        default:throw bad("MANIFEST_ATTRIBUTE_TYPE_UNSUPPORTED");
                    }
                    if(value==null||value.length()>16384||attrs.put(key,value)!=null)throw bad("MANIFEST_ATTRIBUTE");
                }
                stack.push(new Node(p.getName(),p.getNamespace()==null?"":p.getNamespace(),attrs));
            }else if(p.getEventType()==AndroidBinXmlParser.EVENT_END_ELEMENT){
                if(stack.isEmpty())throw bad("MANIFEST_UNBALANCED");Node node=stack.pop();if(!node.name.equals(p.getName()))throw bad("MANIFEST_UNBALANCED");Element e=new Element(node.name,node.ns,node.attrs,node.children);
                if(stack.isEmpty()){if(root!=null)throw bad("MANIFEST_MULTIPLE_ROOTS");root=e;}else stack.peek().children.add(e);
            }
            p.next();
        }
        typed.finish();if(root==null||!stack.isEmpty())throw bad("MANIFEST_UNBALANCED");return new ApkManifest(root);
    }
    private static final class Node {final String name,ns;final Map<String,String> attrs;final List<Element> children=new ArrayList<>();Node(String n,String s,Map<String,String> a){name=n;ns=s;attrs=a;}}
    static IllegalArgumentException bad(String reason){return new IllegalArgumentException(reason);}
    static String text(String value,String reason){if(value==null||value.startsWith("@")||value.length()>512||value.indexOf('\0')>=0)throw bad(reason);return value;}
    static void identifier(String value){if(!value.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,149}"))throw bad("SPLIT_NAME");}
    static long number(String value){try{return Long.parseLong(value);}catch(NumberFormatException e){throw bad("MANIFEST_INTEGER");}}
    static int integer(String value){long n=number(value);if(n<0||n>Integer.MAX_VALUE)throw bad("MANIFEST_INTEGER");return (int)n;}
    static boolean bool(String value,boolean absent){if(value==null)return absent;if(value.equals("true")||value.equals("1"))return true;if(value.equals("false")||value.equals("0"))return false;throw bad("MANIFEST_BOOLEAN");}
    static Boolean declaredBool(String value,boolean absent){if(value!=null&&value.matches("@0x[0-9a-f]{1,8}"))return null;return bool(value,absent);}
    static String className(String pkg,String value){String n=value.startsWith(".")?pkg+value:value.indexOf('.')<0?pkg+"."+value:value;if(!n.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+"))throw bad("CLASS_NAME");return n;}
    static String processName(String pkg,String value){String n=value.startsWith(":")?pkg+value:value;if(!n.matches("\\.?[a-zA-Z_][a-zA-Z0-9_.-]*(?::[a-zA-Z0-9_][a-zA-Z0-9_.:-]*)?"))throw bad("PROCESS_NAME");return n;}
    static List<String> types(String value){if(value.isEmpty())return List.of();TreeSet<String> set=new TreeSet<>();for(String v:value.split(",",-1)){identifier(v);if(!set.add(v))throw bad("DUPLICATE_SPLIT_TYPE");}return Collections.unmodifiableList(new ArrayList<>(set));}
    Map<String,Object> json(){return declaration.json();}
}
