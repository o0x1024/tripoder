package dev.tripoder.hooklab;

import android.content.*;
import android.net.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** Explicit LAN address. HTTP connection export contains only its endpoint. */
final class AgentNetworkIdentity {
    static final int PORT=29731;
    final String ip;final Network network;
    private AgentNetworkIdentity(String ip,Network network){this.ip=ip;this.network=network;}
    static AgentNetworkIdentity create(Context context)throws Exception{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        List<Network> networks=new ArrayList<>();List<String> addresses=new ArrayList<>();
        // LAN availability is independent of Android's validated default Internet network.
        for(Network network:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);LinkProperties links=manager.getLinkProperties(network);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))continue;
            for(LinkAddress address:links.getLinkAddresses())if(address.getAddress() instanceof Inet4Address ip&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress()){networks.add(network);addresses.add(ip.getHostAddress());}
        }
        if(addresses.size()==1)return new AgentNetworkIdentity(addresses.get(0),networks.get(0));
        if(addresses.size()>1)throw new IOException("MCP_MULTIPLE_LAN_ADDRESSES_SELECT_NETWORK");
        throw new IOException("MCP_CONNECT_WIFI_OR_ETHERNET");
    }
    boolean addressStillPresent(Context context){LinkProperties links=context.getSystemService(ConnectivityManager.class).getLinkProperties(network);return links!=null&&links.getLinkAddresses().stream().anyMatch(a->ip.equals(a.getAddress().getHostAddress()));}
    String endpoint(){return "http://"+ip+":"+PORT+"/mcp";}
    JSONObject connection()throws Exception{return new JSONObject().put("url",endpoint());}
}
