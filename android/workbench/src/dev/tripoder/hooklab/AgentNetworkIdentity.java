package dev.tripoder.hooklab;

import android.content.*;
import android.net.*;
import android.security.keystore.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import org.json.JSONObject;

/** App-private network credentials. No cloud registry, trust-on-first-use or exported private key. */
final class AgentNetworkIdentity {
    static final int PORT=29731;
    private static final String ALIAS="tripoder-mcp-tls";
    private final Context context;
    final String ip,token;final Network network;final X509Certificate certificate;final SSLContext tls;
    private AgentNetworkIdentity(Context context,String ip,Network network)throws Exception{
        this.context=context;this.ip=ip;this.network=network;var prefs=context.getSharedPreferences("mcp-network",Context.MODE_PRIVATE);
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC","AndroidKeyStore");generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256).build());generator.generateKeyPair();
        }
        PrivateKey key=(PrivateKey)store.getKey(ALIAS,null);
        KeyInfo policy=KeyFactory.getInstance("EC","AndroidKeyStore").getKeySpec(key,KeyInfo.class);if(!Arrays.asList(policy.getDigests()).containsAll(List.of(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)))throw new GeneralSecurityException("MCP_TLS_KEY_DIGEST_POLICY_INVALID");String encoded=prefs.getString("certificate","");
        if(!ip.equals(prefs.getString("ip",""))||encoded.isEmpty()){
            certificate=AgentCertificate.issue(store.getCertificate(ALIAS).getPublicKey(),key,ip);
            encoded=Base64.getEncoder().encodeToString(certificate.getEncoded());prefs.edit().putString("ip",ip).putString("certificate",encoded).commit();
        }else certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        certificate.checkValidity();String saved=prefs.getString("token","");if(saved.isEmpty()){byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);saved=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);if(!prefs.edit().putString("token",saved).commit())throw new IOException("MCP_CREDENTIAL_SAVE_FAILED");}token=saved;
        // Expose only this endpoint's alias. Other app/guest keys must never be selected.
        tls=SSLContext.getInstance("TLSv1.3");tls.init(new KeyManager[]{new X509ExtendedKeyManager(){
            public String[] getClientAliases(String type,java.security.Principal[] issuers){return null;}
            public String chooseClientAlias(String[] types,java.security.Principal[] issuers,Socket socket){return null;}
            public String[] getServerAliases(String type,java.security.Principal[] issuers){return "EC".equals(type)?new String[]{ALIAS}:null;}
            public String chooseServerAlias(String type,java.security.Principal[] issuers,Socket socket){return "EC".equals(type)?ALIAS:null;}
            public String chooseEngineServerAlias(String type,java.security.Principal[] issuers,SSLEngine engine){return "EC".equals(type)?ALIAS:null;}
            public X509Certificate[] getCertificateChain(String alias){return ALIAS.equals(alias)?new X509Certificate[]{certificate}:null;}
            public PrivateKey getPrivateKey(String alias){return ALIAS.equals(alias)?key:null;}
        }},null,new SecureRandom());
    }
    static AgentNetworkIdentity create(Context context)throws Exception{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        List<Network> networks=new ArrayList<>();List<String> addresses=new ArrayList<>();
        // LAN availability is independent of Android's validated default Internet network.
        for(Network network:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);LinkProperties links=manager.getLinkProperties(network);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))continue;
            for(LinkAddress address:links.getLinkAddresses())if(address.getAddress() instanceof Inet4Address ip&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress()){networks.add(network);addresses.add(ip.getHostAddress());}
        }
        if(addresses.size()==1)return new AgentNetworkIdentity(context,addresses.get(0),networks.get(0));
        if(addresses.size()>1)throw new IOException("MCP_MULTIPLE_LAN_ADDRESSES_SELECT_NETWORK");
        throw new IOException("MCP_CONNECT_WIFI_OR_ETHERNET");
    }
    boolean addressStillPresent(Context context){LinkProperties links=context.getSystemService(ConnectivityManager.class).getLinkProperties(network);return links!=null&&links.getLinkAddresses().stream().anyMatch(a->ip.equals(a.getAddress().getHostAddress()));}
    boolean credentialCurrent(){return token.equals(context.getSharedPreferences("mcp-network",Context.MODE_PRIVATE).getString("token",""));}
    String endpoint(){return "https://"+ip+":"+PORT+"/mcp";}
    JSONObject connection()throws Exception{return new JSONObject().put("url",endpoint()).put("token",token).put("certificatePem","-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(certificate.getEncoded())+"\n-----END CERTIFICATE-----\n");}
    static void rotate(Context context)throws Exception{byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);if(!context.getSharedPreferences("mcp-network",Context.MODE_PRIVATE).edit().putString("token",Base64.getUrlEncoder().withoutPadding().encodeToString(secret)).commit())throw new IOException("MCP_CREDENTIAL_SAVE_FAILED");}
}
