package dev.tripoder.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded ZIP inspection, including Unix symlink types that java.util.zip hides. */
final class ArtifactSetZip {
    static final int MAX_ENTRIES=20000;
    static final long MAX_EXPANDED=3L*1024*1024*1024,MAX_ENTRY=512L*1024*1024;
    static final class Scan {
        final List<ArtifactSet.FileRecord> entries=new ArrayList<>();
        final Map<String,byte[]> manifests=new HashMap<>();
        final Map<String,ArtifactSet.FileRecord> libraries=new TreeMap<>();
        long expanded;
        boolean containsNative;
    }
    static Scan scan(File apk)throws Exception {
        Set<String> central=centralNames(apk);Scan out=new Scan();Set<String> seen=new HashSet<>();
        try(ZipFile zip=new ZipFile(apk)){
            Enumeration<? extends ZipEntry> it=zip.entries();
            while(it.hasMoreElements()){
                ZipEntry e=it.nextElement();String path=e.getName();safePath(path,e.isDirectory());
                if(!central.contains(path)||!seen.add(path))throw fail("ZIP_ENTRY_MISMATCH");
                if(e.isDirectory()){if(e.getSize()!=0)throw fail("ZIP_DIRECTORY_CONTENT");continue;}
                if(e.getSize()<0||e.getSize()>MAX_ENTRY||e.getCompressedSize()<0)throw fail("ZIP_ENTRY_SIZE");
                boolean manifest=path.equals("AndroidManifest.xml");if(manifest&&e.getSize()>4*1024*1024)throw fail("MANIFEST_SIZE");
                ByteArrayOutputStream bytes=manifest?new ByteArrayOutputStream():null;
                MessageDigest md=MessageDigest.getInstance("SHA-256");CRC32 crc=new CRC32();long size=0;
                try(InputStream in=zip.getInputStream(e)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){size+=n;out.expanded+=n;if(size>MAX_ENTRY||out.expanded>MAX_EXPANDED)throw fail("ZIP_EXPANDED_LIMIT");md.update(b,0,n);crc.update(b,0,n);if(bytes!=null)bytes.write(b,0,n);}}
                if(size!=e.getSize()||crc.getValue()!=e.getCrc())throw fail("ZIP_CONTENT_MISMATCH");
                ArtifactSet.FileRecord record=new ArtifactSet.FileRecord(path,ArtifactSet.hex(md.digest()),size);out.entries.add(record);
                if(bytes!=null)out.manifests.put(path,bytes.toByteArray());
                if(path.startsWith("lib/")){
                    if(!path.matches("lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/lib[a-zA-Z0-9_+.$-]+\\.so"))throw fail("ABI_OR_LIBRARY_UNSUPPORTED");
                    out.containsNative=true;
                    // All entries remain signature/CRC/digest checked. Execution
                    // selects ARM64 once; other ABIs are never extracted or loaded.
                    if(!path.startsWith("lib/arm64-v8a/"))continue;
                    String name=path.substring("lib/arm64-v8a/".length());out.libraries.put(name,record);
                    // A selected ABI directory can also contain application-owned
                    // compressed code or metadata with a .so suffix. Keep these as
                    // authenticated opaque files; only actual ELF claims have ABI semantics.
                    try(InputStream in=zip.getInputStream(e)){byte[] header=in.readNBytes(20);boolean elf=header.length>=4&&header[0]==127&&header[1]=='E'&&header[2]=='L'&&header[3]=='F';if(elf&&(header.length<20||header[4]!=2||header[5]!=1||header[16]!=3||header[17]!=0||(header[18]&255)!=183||header[19]!=0))throw fail("NATIVE_ELF_NOT_ARM64_SHARED");}
                }
            }
        }
        if(!seen.equals(central)||!out.manifests.containsKey("AndroidManifest.xml"))throw fail("ZIP_MANIFEST_MISSING");
        out.entries.sort(Comparator.comparing(r->r.path));return out;
    }
    static void extract(File apk,String zipPath,Path target,ArtifactSet.FileRecord expected)throws Exception{
        try(ZipFile zip=new ZipFile(apk);InputStream in=zip.getInputStream(zip.getEntry(zipPath));OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            byte[] b=new byte[65536];long size=0;int n;while((n=in.read(b))!=-1){size+=n;if(size>expected.size)throw fail("LIB_CHANGED_DURING_EXTRACTION");out.write(b,0,n);}if(size!=expected.size)throw fail("LIB_CHANGED_DURING_EXTRACTION");
        }
        if(!ArtifactSet.digest(target.toFile()).equals(expected.sha256))throw fail("LIB_DIGEST_MISMATCH");
        ArtifactSet.syncFile(target);
    }
    static void safePath(String path,boolean directory)throws IOException{
        if(path==null||path.isEmpty()||path.length()>512||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf(':')>=0||path.indexOf('\0')>=0)throw fail("UNSAFE_ZIP_PATH");
        String value=directory&&path.endsWith("/")?path.substring(0,path.length()-1):path;
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw fail("UNSAFE_ZIP_PATH");
    }
    private static Set<String> centralNames(File file)throws Exception{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            long length=f.length();if(length<22)throw fail("ZIP_EOCD");int tailLength=(int)Math.min(length,65557);byte[] tail=new byte[tailLength];f.seek(length-tailLength);f.readFully(tail);ByteBuffer b=ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);int e=-1;
            for(int i=tailLength-22;i>=0;i--)if(b.getInt(i)==0x06054b50&&i+22+u16(b,i+20)==tailLength){e=i;break;}
            if(e<0)throw fail("ZIP_EOCD");int count=u16(b,e+10);long size=u32(b,e+12),start=u32(b,e+16),eocd=length-tailLength+e;
            if(u16(b,e+4)!=0||u16(b,e+6)!=0||u16(b,e+8)!=count||count==65535||count>MAX_ENTRIES||size==0xffffffffL||start==0xffffffffL||start+size!=eocd)throw fail("ZIP64_OR_MULTIDISK_UNSUPPORTED");
            Set<String> names=new HashSet<>();long position=start;
            for(int i=0;i<count;i++){
                if(position+46>start+size)throw fail("ZIP_CENTRAL_TRUNCATED");byte[] fixed=new byte[46];f.seek(position);f.readFully(fixed);ByteBuffer h=ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);if(h.getInt(0)!=0x02014b50)throw fail("ZIP_CENTRAL_HEADER");
                int nameLength=u16(h,28),extra=u16(h,30),comment=u16(h,32),flags=u16(h,8),method=u16(h,10);long local=u32(h,42);
                if(nameLength==0||nameLength>2048||(flags&1)!=0||(method!=0&&method!=8)||u16(h,34)!=0||u32(h,20)==0xffffffffL||u32(h,24)==0xffffffffL||local>=start||position+46+nameLength+extra+comment>start+size)throw fail("ZIP_CENTRAL_UNSUPPORTED");
                byte[] nameBytes=new byte[nameLength];f.readFully(nameBytes);String name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();safePath(name,name.endsWith("/"));if(!names.add(name))throw fail("ZIP_DUPLICATE_ENTRY");
                int madeBy=u16(h,4)>>8,mode=(int)(u32(h,38)>>16),type=mode&0170000;
                if(type!=0&&type!=0100000&&type!=0040000)throw fail("ZIP_LINK_OR_SPECIAL_FILE");
                if(madeBy==3&&((type==0040000&&!name.endsWith("/"))||(type==0100000&&name.endsWith("/"))))throw fail("ZIP_TYPE_MISMATCH");
                position+=46L+nameLength+extra+comment;
            }
            if(position!=start+size)throw fail("ZIP_CENTRAL_TRAILING_DATA");return names;
        }
    }
    private static int u16(ByteBuffer b,int at){return Short.toUnsignedInt(b.getShort(at));}
    private static long u32(ByteBuffer b,int at){return Integer.toUnsignedLong(b.getInt(at));}
    static IOException fail(String reason){return new IOException(reason);}
}
