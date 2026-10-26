package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/**
 * Per-process SDK attribution catalog and consent timeline. Deliberately independent of the
 * single Hook publication slot (32-selector activation budget, exclusive observe/prepare/activate
 * ownership): hooking framework privacy APIs directly is architecturally blocked (self-hook only
 * covers classes the guest's own ClassLoader owns), so this probe observes the GUEST's own code
 * (its statically-linked SDK classes) via the normal Hook mechanism, and only adds attribution
 * lookup + a consent-stage timeline that a Hook script or the agent can read independently of
 * whether a Hook is currently active.
 */
public final class RuntimePrivacyProbeState {
    private static final int MAX_ATTRIBUTION=256,MAX_TIMELINE=64;
    private final Object lock=new Object();
    private List<Map.Entry<String,String>> attribution=List.of(); // longest-prefix-first
    private final List<Map<String,Object>> timeline=new ArrayList<>();
    private long seq;

    public Map<String,Object> configureAttribution(List<Map<String,Object>> entries){
        require(entries.size()<=MAX_ATTRIBUTION,"PRIVACY_ATTRIBUTION_COUNT");
        List<Map.Entry<String,String>> next=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Map<String,Object> entry:entries){
            Object rawPrefix=entry.get("packagePrefix"),rawName=entry.get("sdkName");
            require(rawPrefix instanceof String prefix&&prefix.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*")&&prefix.length()<=256,"PRIVACY_ATTRIBUTION_PREFIX");
            require(rawName instanceof String name&&!name.isEmpty()&&name.length()<=128,"PRIVACY_ATTRIBUTION_NAME");
            String prefix=(String)rawPrefix;require(seen.add(prefix),"PRIVACY_ATTRIBUTION_DUPLICATE_PREFIX");
            next.add(Map.entry(prefix,(String)rawName));
        }
        next.sort((a,b)->b.getKey().length()-a.getKey().length());
        synchronized(lock){attribution=List.copyOf(next);}
        return Map.of("entries",(double)next.size());
    }

    public Map<String,Object> attributionOf(String className){
        require(className!=null&&!className.isEmpty()&&className.length()<=512,"PRIVACY_CLASS_NAME");
        List<Map.Entry<String,String>> current;synchronized(lock){current=attribution;}
        for(Map.Entry<String,String> entry:current){
            String prefix=entry.getKey();
            if(className.equals(prefix)||className.startsWith(prefix+".")){
                Map<String,Object> out=new LinkedHashMap<>();out.put("className",className);out.put("matchedPrefix",prefix);out.put("sdkName",entry.getValue());return Collections.unmodifiableMap(out);
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("className",className);out.put("matchedPrefix",null);out.put("sdkName",null);return Collections.unmodifiableMap(out);
    }

    public Map<String,Object> markConsentStage(String stage,String note,long elapsedRealtimeMillis){
        require(stage!=null&&stage.matches("[A-Z][A-Z0-9_]{0,63}"),"PRIVACY_CONSENT_STAGE");
        require(note!=null&&note.length()<=256,"PRIVACY_CONSENT_NOTE");
        Map<String,Object> row;
        synchronized(lock){
            row=new LinkedHashMap<>();row.put("seq",(double)++seq);row.put("stage",stage);row.put("note",note);
            row.put("elapsedRealtimeMillis",(double)elapsedRealtimeMillis);row.put("wallClockIso",java.time.Instant.now().toString());
            row=Collections.unmodifiableMap(row);timeline.add(row);
            while(timeline.size()>MAX_TIMELINE)timeline.remove(0);
        }
        return row;
    }

    /** Most recent marked stage, or an explicit empty state before any mark. */
    public Map<String,Object> currentStage(){
        synchronized(lock){
            if(timeline.isEmpty())return Map.of("stage",(Object)null,"seq",0d);
            return timeline.get(timeline.size()-1);
        }
    }

    public Map<String,Object> timelinePage(long sinceSeq,int limit){
        require(sinceSeq>=0&&limit>=1&&limit<=MAX_TIMELINE,"PRIVACY_TIMELINE_CURSOR_OR_LIMIT");
        List<Map<String,Object>> rows=new ArrayList<>();long next=sinceSeq,last;
        synchronized(lock){
            last=seq;
            // Entries evicted beyond MAX_TIMELINE are gone; a gap between sinceSeq and the oldest retained entry is explicit via droppedBeforeSeq.
            for(Map<String,Object> row:timeline){long s=((Double)row.get("seq")).longValue();if(s<=sinceSeq)continue;if(rows.size()>=limit)break;rows.add(row);next=s;}
        }
        long oldestRetained=sinceSeq;synchronized(lock){if(!timeline.isEmpty())oldestRetained=((Double)timeline.get(0).get("seq")).longValue();}
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("entries",List.copyOf(rows));out.put("nextCursor",(double)next);out.put("lastSeq",(double)last);out.put("hasMore",next<last);
        out.put("droppedBeforeSeq",sinceSeq<oldestRetained-1&&oldestRetained>1);
        return Collections.unmodifiableMap(out);
    }
}
