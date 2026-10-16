package dev.tripoder.control;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.*;
import org.json.JSONObject;

/** Versioned, signed messages. Transport identity is never authorization. */
public final class AgentCrypto {
    public static final int PROTOCOL = 1;
    public static final int DEVICE_PORT = 29731;
    public static PublicKey publicKey(String encoded) throws Exception {
        if (encoded == null || encoded.length() > 256) throw new SecurityException("AGENT_KEY_INVALID");
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class);
        if (!(key instanceof ECPublicKey)) throw new SecurityException("AGENT_KEY_CURVE_UNSUPPORTED");
        ECParameterSpec actual=((ECPublicKey)key).getParams();
        if (!actual.getCurve().equals(expected.getCurve())||!actual.getGenerator().equals(expected.getGenerator())||!actual.getOrder().equals(expected.getOrder())||actual.getCofactor()!=expected.getCofactor())
            throw new SecurityException("AGENT_KEY_CURVE_UNSUPPORTED");
        return key;
    }
    public static String fingerprint(String encoded) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey(encoded).getEncoded()));
    }
    public static byte[] message(String direction, String session, String payload) {
        return ("tripoder-agent-v1\n" + direction + "\n" + session + "\n" + payload).getBytes(StandardCharsets.UTF_8);
    }
    public static void verify(PublicKey key, String direction, String session, String payload, String signature) throws Exception {
        if (signature == null || signature.length() > 256) throw new SecurityException("AGENT_SIGNATURE_INVALID");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);verifier.update(message(direction, session, payload));
        if (!verifier.verify(Base64.getDecoder().decode(signature))) throw new SecurityException("AGENT_SIGNATURE_REJECTED");
    }
    public static String sign(PrivateKey key, String direction, String session, String payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);signer.update(message(direction, session, payload));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
    public static void fields(JSONObject object, String... fields) {
        Set<String> actual=new HashSet<>();object.keys().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(fields))) throw new IllegalArgumentException("AGENT_FIELDS_UNSUPPORTED");
    }
    public static String text(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("AGENT_FIELD_TYPE:" + key);
        return (String) value;
    }
    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("AGENT_ID_INVALID");
        return value;
    }
    private AgentCrypto() {}
}
