package dev.tripoder.hooklab;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import dev.tripoder.control.AgentCrypto;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Device identity stays in the trusted workbench UID's Android Keystore. */
final class AgentDeviceKey {
    private static final String ALIAS="tripoder-external-agent-v1";
    private final PrivateKey privateKey;
    final String publicKey, fingerprint;
    AgentDeviceKey()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());generator.generateKeyPair();
        }
        privateKey=(PrivateKey)store.getKey(ALIAS,null);publicKey=Base64.getEncoder().encodeToString(store.getCertificate(ALIAS).getPublicKey().getEncoded());fingerprint=AgentCrypto.fingerprint(publicKey);
    }
    String sign(String id,String payload)throws Exception{return AgentCrypto.sign(privateKey,"response",id,payload);}
}
