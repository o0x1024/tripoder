package dev.tripoder.hooklab;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private authenticated storage; call only from a worker thread. */
final class ApiKeyVault {
    private final AtomicFile record;
    private final String alias;

    ApiKeyVault(Context context) { this(context, "ai-credential-v1"); }
    private ApiKeyVault(Context context, String name) {
        record = new AtomicFile(new File(context.getNoBackupFilesDir(), name + ".json"));
        alias = context.getPackageName() + "." + name;
    }
    private boolean hasRecord() { return record.getBaseFile().exists() || new File(record.getBaseFile() + ".bak").exists(); }
    private KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }
    private SecretKey encryptionKey(boolean create) throws Exception {
        KeyStore store = keyStore();
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new java.security.GeneralSecurityException("Saved credential key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private static byte[] associatedData(String endpoint) {
        return ("Tripoder API credential v1\n" + endpoint).getBytes(StandardCharsets.UTF_8);
    }
    void save(String endpoint, String credential) throws Exception {
        String address = AiClient.validateEndpoint(endpoint).toASCIIString();
        if (credential.trim().isEmpty() || credential.length() > 8192 || credential.contains("\n") || credential.contains("\r"))
            throw new IllegalArgumentException("Invalid credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
        cipher.updateAAD(associatedData(address));
        byte[] plain = credential.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); } finally { Arrays.fill(plain, (byte) 0); }
        write(new JSONObject().put("version", 1).put("endpoint", address)
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8));
    }
    String load(String endpoint) throws Exception {
        if (!hasRecord()) return "";
        String address = AiClient.validateEndpoint(endpoint).toASCIIString();
        if (record.getBaseFile().length() > 32 * 1024) throw new java.io.IOException("Invalid credential record");
        JSONObject data = new JSONObject(new String(record.readFully(), StandardCharsets.UTF_8));
        if (data.getInt("version") != 1) throw new java.io.IOException("Unknown credential version");
        // A different service must never receive a previously saved key.
        if (!address.equals(data.getString("endpoint"))) return "";
        byte[] iv = Base64.decode(data.getString("iv"), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(data.getString("ciphertext"), Base64.NO_WRAP);
        if (iv.length != 12 || encrypted.length < 16) throw new java.io.IOException("Invalid credential record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(associatedData(address));
        byte[] plain = cipher.doFinal(encrypted);
        try { return new String(plain, StandardCharsets.UTF_8); } finally { Arrays.fill(plain, (byte) 0); }
    }
    void clear() throws Exception {
        // Invalidate the encryption key first, including when deletion is interrupted.
        keyStore().deleteEntry(alias);
        record.delete();
        if (hasRecord()) throw new java.io.IOException("Credential deletion failed");
    }
    private void write(byte[] bytes) throws Exception {
        FileOutputStream out = record.startWrite();
        try { out.write(bytes); record.finishWrite(out); }
        catch (Exception error) { record.failWrite(out); throw error; }
    }
    private static void check(JSONArray checks, String name, boolean passed) throws Exception {
        if (!passed) throw new java.io.IOException("Credential test failed: " + name);
        checks.put(new JSONObject().put("name", name).put("passed", true));
    }
    static JSONArray selfTest(Context context) throws Exception {
        // A separate alias and file keep diagnostics independent of the user's key.
        ApiKeyVault vault = new ApiKeyVault(context, "credential-self-test");
        JSONArray checks = new JSONArray();
        String endpoint = "https://example.com/v1/chat/completions", secret = "synthetic-test-credential-" + java.util.UUID.randomUUID();
        vault.clear();
        try {
            vault.save(endpoint, secret);
            byte[] first = vault.record.readFully();
            check(checks, "keystore-roundtrip-after-vault-recreation", secret.equals(new ApiKeyVault(context, "credential-self-test").load(endpoint)));
            check(checks, "credential-persisted-without-plaintext", !new String(first, StandardCharsets.UTF_8).contains(secret));
            check(checks, "keystore-key-not-exportable", vault.encryptionKey(false).getEncoded() == null);
            vault.save(endpoint, secret);
            byte[] second = vault.record.readFully();
            check(checks, "credential-random-iv-per-write", !new JSONObject(new String(first, StandardCharsets.UTF_8)).getString("iv").equals(new JSONObject(new String(second, StandardCharsets.UTF_8)).getString("iv")));
            check(checks, "credential-bound-to-endpoint", vault.load("https://other.example/v1/chat/completions").isEmpty());
            JSONObject corrupt = new JSONObject(new String(second, StandardCharsets.UTF_8));
            byte[] ciphertext = Base64.decode(corrupt.getString("ciphertext"), Base64.NO_WRAP); ciphertext[0] ^= 1;
            corrupt.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            vault.write(corrupt.toString().getBytes(StandardCharsets.UTF_8));
            boolean rejected = false;
            try { vault.load(endpoint); } catch (java.security.GeneralSecurityException expected) { rejected = true; }
            check(checks, "credential-tampering-rejected", rejected);
            corrupt = new JSONObject(new String(second, StandardCharsets.UTF_8)).put("endpoint", "https://other.example/v1/chat/completions");
            vault.write(corrupt.toString().getBytes(StandardCharsets.UTF_8)); rejected = false;
            try { vault.load("https://other.example/v1/chat/completions"); } catch (java.security.GeneralSecurityException expected) { rejected = true; }
            check(checks, "credential-endpoint-tampering-rejected", rejected);
            vault.write(second); vault.keyStore().deleteEntry(vault.alias); rejected = false;
            try { vault.load(endpoint); } catch (java.security.GeneralSecurityException expected) { rejected = true; }
            check(checks, "credential-missing-keystore-key-fails-closed", rejected);
            vault.clear(); vault.save(endpoint, secret); vault.clear();
            check(checks, "credential-clear-removes-record-and-key", vault.load(endpoint).isEmpty() && !vault.hasRecord() && !vault.keyStore().containsAlias(vault.alias));
            return checks;
        } finally { vault.clear(); }
    }
}
