package dev.tripoder.mtlsfixture;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.net.ssl.*;

/** Fresh, test-only CA and keys generated locally by build_mtls_fixture.py. */
public final class TlsMaterial {
    public interface Assets { InputStream open(String name) throws Exception; }
    private final Assets assets;
    public TlsMaterial(Assets assets){this.assets=assets;}
    private Certificate certificate(String name)throws Exception{
        try(InputStream in=assets.open(name+".crt")){return CertificateFactory.getInstance("X.509").generateCertificate(in);}
    }
    public SSLContext context(String identity,String trustedCa)throws Exception{
        KeyManager[] keys=null;
        if(identity!=null){
            byte[] der;try(InputStream in=assets.open(identity+".der")){der=in.readAllBytes();}
            var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
            char[] password="owned-test-only".toCharArray();
            store.setKeyEntry("identity",key,password,new Certificate[]{certificate(identity),certificate("ca")});
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);keys=km.getKeyManagers();
        }
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("ca",certificate(trustedCa));
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext context=SSLContext.getInstance("TLSv1.3");context.init(keys,tm.getTrustManagers(),null);return context;
    }
}
