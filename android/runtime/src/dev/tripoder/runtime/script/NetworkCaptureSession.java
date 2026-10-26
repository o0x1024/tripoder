package dev.tripoder.runtime.script;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.BiFunction;

/** One process/session, bounded connection lifetimes and honest completeness. */
public final class NetworkCaptureSession {
    private static final int CONNECTIONS=16,STREAM_BYTES=4096,TOTAL_BYTES=65536;
    private final class Connection {
        final String id;final ByteArrayOutputStream read=new ByteArrayOutputStream(),write=new ByteArrayOutputStream();
        final Set<String> reasons=new LinkedHashSet<>();final List<Map<String,Object>> segments=new ArrayList<>();int readers,writers;long chunks,retries,readOffset,writeOffset;
        final NetworkHttpConversation http;
        Connection(String id){this.id=id;http=new NetworkHttpConversation(id,NetworkCaptureSession.this::message);}
    }
    public final String id;
    private final Map<String,Connection> live=new HashMap<>();
    private final List<Map<String,Object>> completed=new ArrayList<>(),messages=new ArrayList<>();
    private final BiFunction<String,Object,Map<String,Object>> persist;
    private long sequence,created,untracked,droppedConnections,total,events;
    private boolean stopped;private String captureError="";
    public NetworkCaptureSession(String id,BiFunction<String,Object,Map<String,Object>> persist){this.id=id;this.persist=persist;}
    public synchronized void event(Map<String,Object> event){
        if(stopped)return;events++;
        try{
            String tag=(String)event.get("tag");Object payload=event.get("payload");
            if(tag.equals("network.open")){
                String pointer=(String)payload;if(pointer.equals("0"))return;
                Connection prior=live.remove(pointer);if(prior!=null){prior.reasons.add("POINTER_REUSED_WITHOUT_CLOSE");finish(prior);}
                if(created>=CONNECTIONS){droppedConnections++;return;}
                live.put(pointer,new Connection(id+"-c"+(++created)));return;
            }
            if(tag.equals("network.close")){
                Connection connection=live.remove((String)payload);if(connection==null){untracked++;return;}
                if(connection.readers!=0||connection.writers!=0)connection.reasons.add("CLOSED_WITH_IO_IN_FLIGHT");finish(connection);return;
            }
            @SuppressWarnings("unchecked") Map<String,Object> data=(Map<String,Object>)payload;
            Connection connection=live.get(data.get("connection"));if(connection==null){untracked++;return;}
            String direction=(String)data.get("direction");boolean read=direction.equals("read");
            if(!read&&!direction.equals("write"))throw new IllegalArgumentException("DIRECTION");
            if(tag.equals("network.begin")){
                int count=read?++connection.readers:++connection.writers;if(count>1){connection.reasons.add("CONCURRENT_SAME_DIRECTION");connection.http.invalidate("CONCURRENT_SAME_DIRECTION");}return;
            }
            if(!tag.equals("network.bytes"))throw new IllegalArgumentException("EVENT_TAG");
            if((read?connection.readers:connection.writers)<=0)connection.reasons.add("IO_BEGIN_MISSING");
            else if(read)connection.readers--;else connection.writers--;
            @SuppressWarnings("unchecked") Map<String,Object> buffer=(Map<String,Object>)data.get("buffer");
            String state=(String)buffer.get("state");connection.chunks++;
            if(state.equals("NO_DATA")){connection.retries++;return;}
            long actual=Long.parseLong((String)buffer.get("result"));long offset=read?connection.readOffset:connection.writeOffset;
            if(actual>0){if(read)connection.readOffset+=actual;else connection.writeOffset+=actual;}
            if(connection.segments.size()>=64){connection.reasons.add("SEGMENT_LIMIT");connection.http.invalidate("SEGMENT_LIMIT");return;}
            if(!state.equals("COMPLETE")){connection.reasons.add(state);connection.http.invalidate(state);connection.segments.add(Map.of("direction",direction,"offset",Long.toString(offset),"actualBytes",Long.toString(actual),"capturedBytes",0d,"state",state));return;}
            byte[] bytes=HexFormat.of().parseHex((String)buffer.get("hex"));
            if(Boolean.TRUE.equals(buffer.get("truncated")))connection.reasons.add("CHUNK_TRUNCATED");
            ByteArrayOutputStream stream=read?connection.read:connection.write;
            int allowed=(int)Math.min(bytes.length,Math.min(STREAM_BYTES-stream.size(),TOTAL_BYTES-total));
            if(allowed<bytes.length)connection.reasons.add("CAPTURE_BYTE_LIMIT");
            stream.write(bytes,0,allowed);total+=allowed;
            connection.segments.add(Map.of("direction",direction,"offset",Long.toString(offset),"actualBytes",Long.toString(actual),"capturedBytes",(double)allowed,"state",allowed==actual?"COMPLETE":"TRUNCATED"));
            if(!connection.reasons.isEmpty())connection.http.invalidate(connection.reasons.iterator().next());
            connection.http.drain(connection.write.toByteArray(),connection.read.toByteArray(),false);
        }catch(RuntimeException failure){captureError="CAPTURE_EVENT_INVALID";for(Connection connection:live.values())connection.http.invalidate(captureError);}
    }
    private void finish(Connection connection){
        byte[] request=connection.write.toByteArray(),response=connection.read.toByteArray();
        boolean complete=connection.reasons.isEmpty();
        if(!complete)connection.http.invalidate(connection.reasons.iterator().next());
        connection.http.drain(request,response,true);Map<String,Object> http=connection.http.status();
        Map<String,Object> row=new LinkedHashMap<>();row.put("seq",(double)++sequence);row.put("captureId",id);row.put("connectionId",connection.id);
        row.put("byteState",complete?"COMPLETE":"INCOMPLETE");row.put("reasons",List.copyOf(connection.reasons));row.put("chunks",(double)connection.chunks);row.put("noDataResults",(double)connection.retries);
        row.put("requestHex",HexFormat.of().formatHex(request));row.put("responseHex",HexFormat.of().formatHex(response));
        row.put("segments",List.copyOf(connection.segments));
        row.put("httpState",http.get("state"));row.put("http",http);
        // Persist just raw bytes and integrity metadata: avoids duplicating large HTTP bodies in the artifact.
        Map<String,Object> snapshot=new LinkedHashMap<>(row);
        try{row.put("artifact",persist.apply("network.connection",snapshot));}catch(RuntimeException failure){row.put("artifact",Map.of("state","FAILED","reason","NETWORK_ARTIFACT_WRITE_FAILED"));captureError="NETWORK_ARTIFACT_WRITE_FAILED";}
        completed.add(Collections.unmodifiableMap(row));
    }
    private boolean message(Map<String,Object> exchange){
        if(messages.size()>=32)return false;
        Map<String,Object> row=new LinkedHashMap<>(exchange);row.put("captureId",id);row.put("seq",(double)(messages.size()+1));
        try{row.put("artifact",persist.apply("network.http",new LinkedHashMap<>(row)));}
        catch(RuntimeException failure){row.put("artifact",Map.of("state","FAILED","reason","NETWORK_ARTIFACT_WRITE_FAILED"));captureError="NETWORK_ARTIFACT_WRITE_FAILED";}
        messages.add(Collections.unmodifiableMap(row));return true;
    }
    public synchronized Map<String,Object> messages(long since,int limit){
        if(since<0||since>messages.size()||limit<1||limit>16)throw new HookPreflightRejected("NETWORK_HTTP_CURSOR_OR_LIMIT");
        List<Map<String,Object>> rows=new ArrayList<>();int next=(int)since,bytes=0;
        while(next<messages.size()&&rows.size()<limit){var row=messages.get(next);int size=HookWire.encode(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(!rows.isEmpty()&&bytes+size>40000)break;rows.add(row);bytes+=size;next++;}
        return Map.of("captureId",id,"origin","GUEST_PASSIVE_HTTP1","exchanges",rows,"nextCursor",(double)next,"lastSeq",(double)messages.size(),"hasMore",next<messages.size(),"status",status());
    }
    public synchronized void fail(String reason){captureError=reason;stop();}
    public synchronized void stop(){if(stopped)return;for(Connection connection:live.values()){connection.reasons.add("CAPTURE_STOPPED_BEFORE_CLOSE");finish(connection);}live.clear();stopped=true;}
    public synchronized Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>(Map.of("captureId",id,"captureState",stopped?"STOPPED":"ACTIVE","liveConnections",(double)live.size(),"completedConnections",(double)completed.size(),"capturedBytes",(double)total,
            "untrackedEvents",(double)untracked,"droppedConnections",(double)droppedConnections,"events",(double)events,"captureError",captureError,"coverage",untracked==0&&droppedConnections==0&&captureError.isEmpty()?"OBSERVED_LIFETIMES_ONLY":"INCOMPLETE"));
        result.put("messages",(double)messages.size());result.put("liveHttp",live.values().stream().map(connection->Map.of("connectionId",connection.id,"http",connection.http.status())).toList());
        result.put("limits",Map.of("connections",(double)CONNECTIONS,"bytesPerDirection",(double)STREAM_BYTES,"totalBytes",(double)TOTAL_BYTES,"segmentsPerConnection",64d,"messagesPerCapture",32d,"requestsPerConnection",16d));return result;
    }
    public synchronized Map<String,Object> query(long since,int limit){
        if(since<0||since>sequence||limit<1||limit>16)throw new HookPreflightRejected("NETWORK_CURSOR_OR_LIMIT");
        List<Map<String,Object>> rows=new ArrayList<>();long next=since;int bytes=0;
        for(Map<String,Object> row:completed){long seq=((Double)row.get("seq")).longValue();if(seq<=since)continue;
            int size=HookWire.encode(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(!rows.isEmpty()&&bytes+size>40000)break;
            rows.add(row);bytes+=size;next=seq;if(rows.size()>=limit)break;
        }
        return Map.of("captureId",id,"origin","GUEST_PASSIVE_TLS","connections",List.copyOf(rows),"nextCursor",(double)next,"lastSeq",(double)sequence,"hasMore",next<sequence,"status",status());
    }
}
