package dev.tripoder.runtime;

import java.util.function.Function;

/** Derives effect ownership from authority-issued journal identities, never connection ids. */
final class RuntimeOperationScope {
    static String identity(String caller,Function<String,String> installation){
        for(String prefix:new String[]{"pending-install:","job-install:"})if(caller.startsWith(prefix)){
            String generation=caller.substring(prefix.length()).split(":",2)[0];
            if(!generation.matches("[a-zA-Z0-9_-]{1,160}"))return "*";
            String scope=installation.apply(generation);return scope==null?"*":scope;
        }
        try{
            String[] parts=new String[5];int at=0;
            for(int i=0;i<5;i++){
                int colon=caller.indexOf(':',at),count=Integer.parseInt(caller.substring(at,colon));at=colon+1;
                parts[i]=caller.substring(at,at+count);at+=count;
            }
            if(at==caller.length()&&parts[0].matches("[a-zA-Z0-9_-]{1,160}")&&parts[1].matches("[a-zA-Z0-9_.]+"))return "space/"+parts[0]+"/package/"+parts[1];
        }catch(RuntimeException malformed){}
        return "*";
    }
    static String recorded(String scope,String command,String caller,Function<String,String> installation){
        // Old internal admissions recorded '*' because their installation key was
        // not length-prefixed. Re-derive only these identities from durable ownership.
        // Explicit global commands and unresolvable history keep the global fence.
        return (scope==null||scope.equals("*")&&"legacy-or-internal".equals(command))?identity(caller,installation):scope;
    }
    static boolean overlap(String a,String b){return a.equals("*")||b.equals("*")||a.equals(b)||a.startsWith(b+"/")||b.startsWith(a+"/");}
    private RuntimeOperationScope(){}
}
