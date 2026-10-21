package dev.tripoder.runtime;

import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** A current in-process file-map observation. It grants no symbol/Hook authority. */
final class RuntimeNativeMappingEvidence {
    private static final String CORE="libtripoderselfhook.so";
    private static final int MAP_LIMIT=2*1024*1024, ROW_LIMIT=32000;
    private final File core;
    private final String generation;
    private static native byte[] relevantRowsNative(byte[] raw,long[] counts);
    RuntimeNativeMappingEvidence(File core,String generation)throws Exception {
        this.core=core.getCanonicalFile();this.generation=Objects.requireNonNull(generation);
        if(!this.core.getName().equals(CORE)||!this.core.toString().startsWith("/data/app/"))throw new SecurityException("RUNTIME_CORE_PHYSICAL_PATH");
    }
    Map<String,Object> snapshot()throws Exception {
        StructStat before=Os.stat(core.toString());
        if(before.st_size<=0||before.st_size>64*1024*1024)throw new SecurityException("RUNTIME_CORE_FILE_SIZE");
        byte[] maps=bounded(new File("/proc/self/maps"),MAP_LIMIT);
        long[] counts=new long[1];byte[] selected=relevantRowsNative(maps,counts);
        if(selected==null||counts[0]<1)throw new SecurityException("RUNTIME_MAPS_SCAN_FAILED");
        Map<String,Object> observed=parse(selected,core.toString(),before.st_ino,before.st_dev);
        observed.put("mapsSha256",hex(MessageDigest.getInstance("SHA-256").digest(maps)));observed.put("mapsBytes",maps.length);observed.put("mapsRowCount",counts[0]);
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
            // Validate the same complete row grammar with bounded delimiter
            // scans. A regex Matcher and lower-case copy for every anonymous
            // DEX mapping made the first THICK status spend seconds here.
            int dash=line.indexOf('-'),space=line.indexOf(' ',dash+1);
            if(!hex(line,0,dash)||!hex(line,dash+1,space))throw new SecurityException("RUNTIME_MAPS_ROW");
            int permissions=space+1;
            if(permissions+4>=line.length()||(line.charAt(permissions)!='r'&&line.charAt(permissions)!='-')
                ||(line.charAt(permissions+1)!='w'&&line.charAt(permissions+1)!='-')
                ||(line.charAt(permissions+2)!='x'&&line.charAt(permissions+2)!='-')
                ||(line.charAt(permissions+3)!='p'&&line.charAt(permissions+3)!='s')||line.charAt(permissions+4)!=' ')throw new SecurityException("RUNTIME_MAPS_ROW");
            int offset=permissions+5,offsetEnd=line.indexOf(' ',offset),majorStart=offsetEnd+1,colon=line.indexOf(':',majorStart),minorEnd=line.indexOf(' ',colon+1);
            if(!hex(line,offset,offsetEnd)||!hex(line,majorStart,colon)||!hex(line,colon+1,minorEnd))throw new SecurityException("RUNTIME_MAPS_ROW");
            int inodeStart=minorEnd+1,inodeEnd=line.indexOf(' ',inodeStart);if(inodeEnd<0)inodeEnd=line.length();
            if(inodeStart>=inodeEnd)throw new SecurityException("RUNTIME_MAPS_ROW");
            for(int at=inodeStart;at<inodeEnd;at++)if(line.charAt(at)<'0'||line.charAt(at)>'9')throw new SecurityException("RUNTIME_MAPS_ROW");
            int pathStart=inodeEnd;while(pathStart<line.length()&&line.charAt(pathStart)==' ')pathStart++;
            String path=line.substring(pathStart);count++;
            for(int at=0;at<path.length();at++){char c=path.charAt(at);if(c=='\r'||c=='\u0085'||c=='\u2028'||c=='\u2029')throw new SecurityException("RUNTIME_MAPS_ROW");}
            if(containsIgnoreCase(path,"frida")||containsIgnoreCase(path,"gadget")||containsIgnoreCase(path,"tripoderio"))foreignRows.add(line);
            if(path.endsWith(".so")||path.endsWith(".so (deleted)")){nativeRows.append(line).append('\n');nativePaths.add(path);}
            if(path.endsWith("/"+CORE)||path.endsWith("/"+CORE+" (deleted)")){
                if(!path.equals(core)||Long.parseUnsignedLong(line,inodeStart,inodeEnd,10)!=inode||Long.parseUnsignedLong(line,majorStart,colon,16)!=major||Long.parseUnsignedLong(line,colon+1,minorEnd,16)!=minor)throw new SecurityException("RUNTIME_CORE_MAPPING_IDENTITY");
                if(line.charAt(permissions+1)=='w'&&line.charAt(permissions+2)=='x')throw new SecurityException("RUNTIME_CORE_MAPPING_WX");
                executable|=line.charAt(permissions+2)=='x';coreRows.add(line);
            }
        }
        if(coreRows.isEmpty()||!executable||coreRows.size()>16)throw new SecurityException("RUNTIME_CORE_MAPPING_MISSING");
        // Inspect every row before applying an output budget. Diagnostic volume
        // must not disable control, and truncation must never hide foreign rows.
        if(nativePaths.size()>1024||foreignRows.size()>32)throw new SecurityException("RUNTIME_NATIVE_EVIDENCE_SIZE:paths="+nativePaths.size()+":foreign="+foreignRows.size());
        String allPaths=String.join("\n",nativePaths);StringBuilder displayed=new StringBuilder(core);int displayedCount=1;
        for(String path:nativePaths){if(path.equals(core))continue;if(displayed.length()+1+path.length()>ROW_LIMIT)continue;displayed.append('\n').append(path);displayedCount++;}
        Map<String,Object> out=new LinkedHashMap<>();out.put("corePath",core);out.put("coreInode",Long.toUnsignedString(inode));out.put("coreRows",coreRows);
        out.put("nativePaths",displayed.toString());out.put("nativePathCount",nativePaths.size());out.put("nativePathsReported",displayedCount);out.put("nativePathsTruncated",displayedCount<nativePaths.size());
        out.put("nativePathsSha256",hex(MessageDigest.getInstance("SHA-256").digest(allPaths.getBytes(StandardCharsets.UTF_8))));out.put("nativeRowsSha256",hex(MessageDigest.getInstance("SHA-256").digest(nativeRows.toString().getBytes(StandardCharsets.UTF_8))));out.put("foreignBackendRows",foreignRows);out.put("mapsSha256",hex(MessageDigest.getInstance("SHA-256").digest(bytes)));out.put("mapsBytes",bytes.length);out.put("mapsRowCount",count);
        return out;
    }
    private static boolean hex(String value,int from,int to){
        if(from<0||to<=from||to>value.length())return false;
        for(int at=from;at<to;at++){char c=value.charAt(at);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}
        return true;
    }
    private static boolean containsIgnoreCase(String value,String token){
        char first=token.charAt(0),upper=(char)(first-'a'+'A');
        for(int from=0;from<=value.length()-token.length();){
            int lowerAt=value.indexOf(first,from),upperAt=value.indexOf(upper,from),at=lowerAt<0?upperAt:upperAt<0?lowerAt:Math.min(lowerAt,upperAt);
            if(at<0)return false;if(value.regionMatches(true,at,token,0,token.length()))return true;from=at+1;
        }return false;
    }
    private static byte[] bounded(File file,int limit)throws Exception {
        if(file.getPath().startsWith("/proc/self/")&&RuntimeEngine.current!=null&&RuntimeEngine.adapterReady)
            return RuntimeSelfHookStorage.readPhysicalProc(file.getPath(),limit);
        try(InputStream input=new FileInputStream(file)){byte[] result=input.readNBytes(limit+1);if(result.length>limit)throw new IOException("RUNTIME_EVIDENCE_READ_BUDGET");return result;}
    }
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
}
