package dev.tripoder.selfhook;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
/** Read-only declaration evidence for remaining pinned Runtime capabilities.
 * This inventory does not admit or install its methods as Hook targets. */
public final class OwnedRemainingInventoryChecks {
    public static JSONObject run()throws Exception {
        JSONObject result=new JSONObject();int total=0;
        for(int owner=27;owner<49;owner++){
            Class<?> type=FixedFrameworkMembers.owner(owner);List<String> methods=new ArrayList<>(),fields=new ArrayList<>();int instance=0,publicStatic=0,nativeCount=0;
            for(Object candidate:FrameworkAccess.fixedMembers(owner)){
                if(candidate instanceof Method){Method method=(Method)candidate;int modifiers=method.getModifiers();
                    if(method.getDeclaringClass()!=type)throw new AssertionError("REMAINING_INVENTORY_OWNER");
                    methods.add(method.getName()+FixedFrameworkMembers.signature(method.getParameterTypes(),method.getReturnType())+":"+Integer.toHexString(modifiers));
                    if(!Modifier.isStatic(modifiers)&&!Modifier.isAbstract(modifiers))instance++;
                    if(Modifier.isPublic(modifiers)&&Modifier.isStatic(modifiers))publicStatic++;
                    if(Modifier.isNative(modifiers))nativeCount++;
                }else if(candidate instanceof Constructor<?>){Constructor<?> c=(Constructor<?>)candidate;
                    methods.add("<init>"+FixedFrameworkMembers.signature(c.getParameterTypes(),void.class)+":"+Integer.toHexString(c.getModifiers()));
                }
            }
            // Public fields only. Hidden field ownership is added to the same
            // Native registry explicitly after exact targets are determined.
            for(Field field:type.getFields())if(field.getDeclaringClass()==type)fields.add(field.getName()+":"+field.getType().getName());
            Collections.sort(methods);Collections.sort(fields);String text=String.join("\n",methods)+"\n";total+=methods.size();
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            result.put(type.getName(),new JSONObject().put("owner",owner).put("methods",new JSONArray(methods)).put("sha256",digest)
                .put("concreteInstances",instance).put("publicStatic",publicStatic).put("native",nativeCount).put("publicFields",new JSONArray(fields))
                .put("parent",type.getSuperclass()==null?JSONObject.NULL:type.getSuperclass().getName()));
        }
        return new JSONObject().put("owners",22).put("declarations",total).put("inventory",result).put("hookAdmissionGranted",false).put("productAccepted",false);
    }
}
