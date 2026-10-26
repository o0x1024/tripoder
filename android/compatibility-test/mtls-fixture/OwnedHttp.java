package dev.tripoder.mtlsfixture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.*;

/** Owned custom HTTP/1.1 sender. Observation attaches externally to exchange; no telemetry here. */
public final class OwnedHttp {
    private final SSLContext context;
    private final int port;
    public OwnedHttp(SSLContext context,int port){this.context=context;this.port=port;}
    public String exchange(String request)throws Exception{
        try(SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket("127.0.0.1",port)){
            socket.setSoTimeout(5000);socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
            socket.startHandshake();
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            return readMessage(socket.getInputStream());
        }
    }
    static String readMessage(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int matched=0;
        byte[] ending={13,10,13,10};
        while(matched<4){
            int value=input.read();if(value<0)throw new EOFException("HTTP_HEADER_EOF");
            bytes.write(value);if(bytes.size()>8192)throw new IOException("HTTP_HEADER_LIMIT");
            matched=value==ending[matched]?matched+1:(value==13?1:0);
        }
        String header=bytes.toString(StandardCharsets.US_ASCII);int length=-1;
        for(String line:header.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")){
            if(length!=-1)throw new IOException("HTTP_DUPLICATE_LENGTH");length=Integer.parseInt(line.substring(15).trim());
        }
        if(length<0||length>8192)throw new IOException("HTTP_LENGTH_REQUIRED");
        byte[] body=input.readNBytes(length);if(body.length!=length)throw new EOFException("HTTP_BODY_EOF");bytes.write(body);
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}
