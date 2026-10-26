package dev.tripoder.runtime;

/** Canonical five-field journal identity; session existence remains a separate proof. */
final class RuntimeSessionIdentity {
    static String[] parse(String caller){
        if(caller==null)return null;
        try{
            String[] parts=new String[5];StringBuilder canonical=new StringBuilder();int at=0;
            for(int i=0;i<parts.length;i++){
                int colon=caller.indexOf(':',at),count=Integer.parseInt(caller.substring(at,colon));if(count<1||count>4096)return null;at=colon+1;
                parts[i]=caller.substring(at,at+count);at+=count;canonical.append(parts[i].length()).append(':').append(parts[i]);
            }
            return at==caller.length()&&caller.contentEquals(canonical)?parts:null;
        }catch(RuntimeException invalid){return null;}
    }
    private RuntimeSessionIdentity(){}
}
