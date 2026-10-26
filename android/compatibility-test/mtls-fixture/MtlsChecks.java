package dev.tripoder.mtlsfixture;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** Same real TLS checks on the host JVM and in the Android guest. Loopback only. */
public final class MtlsChecks {
    public static String run(TlsMaterial material,String runId)throws Exception{
        if(!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID");
        Case positive=exchange(material,runId,"client","ca");
        require(positive.clientError==null&&positive.serverError==null,"POSITIVE_HANDSHAKE");
        require(positive.authenticated&&positive.request.equals(positive.received),"AUTHENTICATED_EXACT_REQUEST");
        require(positive.response.equals(positive.returned),"EXACT_RESPONSE");
        Case missing=exchange(material,runId+"-no-client",null,"ca");
        require(missing.serverError instanceof SSLException&&missing.clientError instanceof SSLException&&!missing.authenticated&&missing.received==null,"MISSING_CLIENT_CERT_REJECTED");
        Case untrusted=exchange(material,runId+"-untrusted","client","other-ca");
        require(untrusted.clientError instanceof SSLException&&!untrusted.authenticated&&untrusted.received==null,"UNTRUSTED_SERVER_REJECTED");
        return "{\"passed\":true,\"runId\":\""+runId+"\",\"protocol\":\""+positive.protocol+"\",\"clientAuthenticated\":true,\"exactRequest\":true,\"exactResponse\":true,\"missingClientRejected\":true,\"untrustedServerRejected\":true,\"externalRequests\":0}";
    }
    private static final class Case {
        String request,response,received,returned,protocol;
        Throwable clientError,serverError;
        boolean authenticated;
    }
    private static Case exchange(TlsMaterial material,String id,String clientIdentity,String trustedCa)throws Exception{
        Case result=new Case();String body="owned-request-"+id,reply="owned-response-"+id;
        result.request="POST /owned/"+id+" HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+body.length()+"\r\nConnection: close\r\n\r\n"+body;
        result.response="HTTP/1.1 200 OK\r\nContent-Length: "+reply.length()+"\r\nConnection: close\r\n\r\n"+reply;
        SSLContext serverContext=material.context("server","ca"),clientContext=material.context(clientIdentity,trustedCa);
        try(SSLServerSocket listener=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            listener.setSoTimeout(7000);listener.setEnabledProtocols(new String[]{"TLSv1.3"});listener.setNeedClientAuth(true);
            Thread server=new Thread(()->{
                try(SSLSocket socket=(SSLSocket)listener.accept()){
                    socket.setSoTimeout(5000);socket.startHandshake();
                    result.authenticated=socket.getSession().getPeerPrincipal().getName().equals("CN=owned-client");
                    result.protocol=socket.getSession().getProtocol();
                    if(!result.authenticated)throw new SSLPeerUnverifiedException("CLIENT_IDENTITY");
                    result.received=OwnedHttp.readMessage(socket.getInputStream());
                    socket.getOutputStream().write(result.response.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                }catch(Throwable error){result.serverError=error;}
            },"owned-mtls-server");server.start();
            try{result.returned=new OwnedHttp(clientContext,listener.getLocalPort()).exchange(result.request);}catch(Exception error){result.clientError=error;}
            server.join(8000);if(server.isAlive()){listener.close();server.join(1000);throw new IllegalStateException("SERVER_TIMEOUT");}
        }
        return result;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private MtlsChecks(){}
}
