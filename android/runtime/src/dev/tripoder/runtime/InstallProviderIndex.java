package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Install-time declaration lookup, never a grant to execute unverified artifact bytes. */
final class InstallProviderIndex {
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE install_provider_authorities(space TEXT NOT NULL,authority TEXT NOT NULL,generation TEXT NOT NULL REFERENCES installs(generation) ON DELETE CASCADE,PRIMARY KEY(space,authority))");
        db.execSQL("CREATE INDEX install_provider_generation ON install_provider_authorities(generation)");
    }
    static Set<String> authorities(ArtifactSet set){
        Set<String> out=new LinkedHashSet<>();
        // Reserve disabled declarations too, matching installation conflict semantics.
        for(var component:set.components)if(component.type.equals("provider"))out.addAll(RuntimeProviderBroker.aliases(component.authorities));
        return out;
    }
    static void publish(SQLiteDatabase db,String space,String generation,Set<String> authorities){
        for(String authority:authorities){ContentValues row=new ContentValues();row.put("space",space);row.put("authority",authority);row.put("generation",generation);db.insertOrThrow("install_provider_authorities",null,row);}
    }
    static Target find(SQLiteDatabase db,String space,String authority){
        try(var c=db.rawQuery("SELECT i.package,i.generation FROM install_provider_authorities p JOIN installs i ON i.generation=p.generation AND i.space=p.space WHERE p.space=? AND p.authority=?",new String[]{space,authority})){
            return c.moveToFirst()?new Target(c.getString(0),c.getString(1)):null;
        }
    }
    static void requireAvailable(SQLiteDatabase db,String space,String pkg,Set<String> authorities){
        for(String authority:authorities){Target target=find(db,space,authority);if(target!=null&&!pkg.equals(target.pkg))throw new IllegalStateException("PROVIDER_AUTHORITY_CONFLICT");}
    }
    static final class Target {
        final String pkg,generation;
        Target(String pkg,String generation){this.pkg=pkg;this.generation=generation;}
        boolean same(Target other){return other!=null&&pkg.equals(other.pkg)&&generation.equals(other.generation);}
    }
    /** One transactional schema migration. Read only authenticated metadata, not every APK. */
    static void migrate(SQLiteDatabase db){
        create(db);
        try(var c=db.rawQuery("SELECT space,package,generation,apk,descriptorSha FROM installs",null)){
            while(c.moveToNext())publish(db,c.getString(0),c.getString(2),readDescriptor(new File(new File(c.getString(3)).getParentFile(),ArtifactSet.DESCRIPTOR),c.getString(1),c.getString(4)));
        }catch(Exception error){throw new IllegalStateException("PROVIDER_INDEX_MIGRATION_FAILED",error);}
    }
    static Set<String> readDescriptor(File file,String pkg,String digest)throws Exception{
        if(digest==null||!digest.matches("[a-f0-9]{64}")||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("PROVIDER_INDEX_DESCRIPTOR_INVALID");
        byte[] bytes;try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(16*1024*1024+1);}
        if(bytes.length>16*1024*1024||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest))throw new SecurityException("PROVIDER_INDEX_DESCRIPTOR_DIGEST_MISMATCH");
        Set<String> out=new LinkedHashSet<>();String declaredPackage=null;int schema=-1;boolean components=false;
        try(JsonReader r=new JsonReader(new StringReader(new String(bytes,StandardCharsets.UTF_8)))){
            r.beginObject();while(r.hasNext())switch(r.nextName()){
                case "schema"->schema=r.nextInt();
                case "packageName"->declaredPackage=r.nextString();
                case "components"->{components=true;r.beginArray();while(r.hasNext()){
                    String type=null,authorities=null;r.beginObject();while(r.hasNext())switch(r.nextName()){
                        case "type"->type=r.nextString();case "authorities"->authorities=r.nextString();default->r.skipValue();
                    }r.endObject();if("provider".equals(type)){if(authorities==null)throw new SecurityException("PROVIDER_INDEX_AUTHORITIES_MISSING");out.addAll(RuntimeProviderBroker.aliases(authorities));}
                }r.endArray();}
                default->r.skipValue();
            }r.endObject();if(r.peek()!=android.util.JsonToken.END_DOCUMENT)throw new SecurityException("PROVIDER_INDEX_DESCRIPTOR_TRAILING_DATA");
        }
        if(schema!=1||!pkg.equals(declaredPackage)||!components)throw new SecurityException("PROVIDER_INDEX_DESCRIPTOR_METADATA_MISMATCH");return out;
    }
    private InstallProviderIndex(){}
}
