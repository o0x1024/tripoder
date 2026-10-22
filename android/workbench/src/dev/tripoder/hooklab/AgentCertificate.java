package dev.tripoder.hooklab;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.security.auth.x500.X500Principal;

/** Small DER certificate encoder. Signatures and TLS use platform implementations. */
final class AgentCertificate {
    static X509Certificate issue(PublicKey publicKey,PrivateKey privateKey,String ip)throws Exception{
        byte[] algorithm=seq(der(6,new byte[]{42,(byte)134,72,(byte)206,61,4,3,2})); // ecdsa-with-SHA256
        byte[] name=new X500Principal("CN=tripoder.local").getEncoded();
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.ROOT);format.setTimeZone(TimeZone.getTimeZone("UTC"));long now=System.currentTimeMillis();
        byte[] validity=seq(der(24,format.format(new Date(now-86400000L)).getBytes(StandardCharsets.US_ASCII)),der(24,format.format(new Date(now+315360000000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] san=seq(der(6,new byte[]{85,29,17}),der(4,seq(der(130,"tripoder.local".getBytes(StandardCharsets.US_ASCII)),der(135,InetAddress.getByName(ip).getAddress()))));
        byte[] body=seq(der(160,der(2,new byte[]{2})),der(2,new BigInteger(120,new SecureRandom()).add(BigInteger.ONE).toByteArray()),algorithm,name,validity,name,publicKey.getEncoded(),der(163,seq(san)));
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(privateKey);signer.update(body);
        byte[] signed=seq(body,algorithm,der(3,join(new byte[]{0},signer.sign())));
        X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(signed));certificate.verify(publicKey);return certificate;
    }
    private static byte[] seq(byte[]...parts)throws Exception{return der(48,join(parts));}
    private static byte[] join(byte[]...parts)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part);return out.toByteArray();}
    private static byte[] der(int tag,byte[] value)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);if(value.length<128)out.write(value.length);else{int size=value.length,count=0;while(size>0){count++;size>>>=8;}out.write(128|count);for(int i=count-1;i>=0;i--)out.write(value.length>>>(i*8));}out.write(value);return out.toByteArray();}
}
