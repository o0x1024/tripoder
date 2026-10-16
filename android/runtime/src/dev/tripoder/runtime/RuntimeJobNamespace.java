package dev.tripoder.runtime;

/** Default namespace is represented only internally by the empty string. */
final class RuntimeJobNamespace {
    static String logical(String value){
        if(value==null)return "";
        if(value.isEmpty()||value.length()>1000||!value.equals(value.trim())||!value.equals(new String(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8)))throw new IllegalArgumentException("JOB_NAMESPACE_INVALID");
        return value;
    }
    static String physical(String space,String install,String namespace){
        try{return "tripoder.job."+ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest((part(space)+part(install)+part(namespace)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("JOB_NAMESPACE_DIGEST_UNAVAILABLE",error);}
    }
    static String part(String value){return value.length()+":"+value;}
    private RuntimeJobNamespace(){}
}
