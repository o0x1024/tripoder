package dev.tripoder.runtime;

import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** A current in-process file-map observation. It grants no symbol/Hook authority. */
final class RuntimeNativeMappingEvidence {
    private static final String CORE="libtripoderselfhook.so";
    private static final int MAP_LIMIT=2*1024*1024, ROW_LIMIT=32000;
    private static final Pattern ROW=Pattern.compile("^([0-9a-f]+)-([0-9a-f]+) ([r-][w-][x-][ps]) ([0-9a-f]+) ([0-9a-f]+):([0-9a-f]+) ([0-9]+)(?: +(.*))?$");
    private final File core;
    private final String generation;
    RuntimeNativeMappingEvidence(File core,String generation)throws Exception {
        this.core=core.getCanonicalFile();this.generation=Objects.requireNonNull(generation);
        if(!this.core.getName().equals(CORE)||!this.core.toString().startsWith("/data/app/"))throw new SecurityException("RUNTIME_CORE_PHYSICAL_PATH");
    }
    Map<String,Object> snapshot()throws Exception {
        StructStat before=Os.stat(core.toString());
        if(before.st_size<=0||before.st_size>64*1024*1024)throw new SecurityException("RUNTIME_CORE_FILE_SIZE");
        byte[] maps=bounded(new File("/proc/self/maps"),MAP_LIMIT);
        Map<String,Object> observed=parse(maps,core.toString(),before.st_ino,before.st_dev);
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(bounded(core,64*1024*1024)));
        StructStat after=Os.stat(core.toString());
        if(before.st_ino!=after.st_ino||before.st_dev!=after.st_dev||before.st_size!=after.st_size||before.st_mtime!=after.st_mtime)throw new SecurityException("RUNTIME_CORE_FILE_CHANGED");
        observed.put("schema",1);observed.put("pid",android.os.Process.myPid());observed.put("uid",android.os.Process.myUid());
        observed.put("processGeneration",generation);observed.put("coreSha256",digest);observed.put("coreSize",before.st_size);
        observed.put("physicalProcess",new String(bounded(new File("/proc/self/cmdline"),4096),StandardCharsets.UTF_8).split("\\u0000",-1)[0]);
        return observed;
    }
    static Map<String,Object> parse(byte[] bytes,String core,long inode,long device)throws Exception {
        if(bytes.length==0||bytes.length>MAP_LIMIT)throw new SecurityException("RUNTIME_MAPS_SIZE");
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(!text.endsWith("\n"))throw new SecurityException("RUNTIME_MAPS_INCOMPLETE");
        List<String> coreRows=new ArrayList<>(),foreignRows=new ArrayList<>();StringBuilder nativeRows=new StringBuilder();Set<String> nativePaths=new LinkedHashSet<>();
        boolean executable=false;int count=0;
        long major=((device>>>8)&0xfff)|((device>>>32)&0xfffff000L),minor=(device&0xff)|((device>>>12)&0xffffff00L);
        for(String line:text.split("\n")){
            Matcher row=ROW.matcher(line);if(!row.matches())throw new SecurityException("RUNTIME_MAPS_ROW");count++;
            String path=row.group(8)==null?"":row.group(8),lower=path.toLowerCase(Locale.ROOT);
            if(lower.contains("frida")||lower.contains("gadget")||lower.contains("tripoderio"))foreignRows.add(line);
            if(path.endsWith(".so")||path.endsWith(".so (deleted)")){nativeRows.append(line).append('\n');nativePaths.add(path);}
            if(path.endsWith("/"+CORE)||path.endsWith("/"+CORE+" (deleted)")){
                if(!path.equals(core)||Long.parseUnsignedLong(row.group(7))!=inode||Long.parseUnsignedLong(row.group(5),16)!=major||Long.parseUnsignedLong(row.group(6),16)!=minor)throw new SecurityException("RUNTIME_CORE_MAPPING_IDENTITY");
                if(row.group(3).charAt(1)=='w'&&row.group(3).charAt(2)=='x')throw new SecurityException("RUNTIME_CORE_MAPPING_WX");
                executable|=row.group(3).charAt(2)=='x';coreRows.add(line);
            }
        }
        if(coreRows.isEmpty()||!executable||coreRows.size()>16)throw new SecurityException("RUNTIME_CORE_MAPPING_MISSING");
        // A real guest/engine process legitimately maps several hundred .so (ART, boot image, framework,
        // the fixed self-hook core, plus per-generation native Hook test modules and their (deleted) variants;
        // p4 observes 436). The serialised report size is bounded by ROW_LIMIT on the joined path text, and the
        // security-relevant bound is foreignRows (frida/gadget/tripoderio); the path count is a sanity ceiling
        // consistent with the framework member budget (1024). Counts are reported for future evidence-based sizing.
        if(nativePaths.size()>1024||String.join("\n",nativePaths).length()>ROW_LIMIT||foreignRows.size()>32)throw new SecurityException("RUNTIME_NATIVE_EVIDENCE_SIZE:paths="+nativePaths.size()+":joined="+String.join("\n",nativePaths).length()+":foreign="+foreignRows.size());
        Map<String,Object> out=new LinkedHashMap<>();out.put("corePath",core);out.put("coreInode",Long.toUnsignedString(inode));out.put("coreRows",coreRows);
        // The unique .so paths are carried as one newline-joined string (bounded above by ROW_LIMIT), not a
        // per-element array: a real process maps several hundred of them and the fixed hook wire caps arrays at
        // 256. The consumer splits on '\n' and runs the same per-path checks (uniqueness, foreign substring,
        // core alias). Paths never contain '\n' (maps rows are single lines), so the join is lossless.
        out.put("nativePaths",String.join("\n",nativePaths));out.put("nativeRowsSha256",hex(MessageDigest.getInstance("SHA-256").digest(nativeRows.toString().getBytes(StandardCharsets.UTF_8))));out.put("foreignBackendRows",foreignRows);out.put("mapsSha256",hex(MessageDigest.getInstance("SHA-256").digest(bytes)));out.put("mapsBytes",bytes.length);out.put("mapsRowCount",count);
        return out;
    }
    private static byte[] bounded(File file,int limit)throws Exception {
        try(InputStream input=new FileInputStream(file)){byte[] result=input.readNBytes(limit+1);if(result.length>limit)throw new IOException("RUNTIME_EVIDENCE_READ_BUDGET");return result;}
    }
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
}
