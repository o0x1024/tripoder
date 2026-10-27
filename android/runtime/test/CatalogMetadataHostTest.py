#!/usr/bin/env python3
"""Run production catalog/binding logic against SQLite JDBC and a controllable APK loader.

Android adapters are host-only. This verifies metadata identity and lock behavior,
not Android PackageManager performance or Binder transport.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import urllib.request

repo = Path(__file__).resolve().parents[3]
cache = Path(tempfile.gettempdir()) / "tripoder-catalog-test-deps"
cache.mkdir(exist_ok=True)
jars = []
for artifact in ("org/json/json/20250517/json-20250517.jar",
                 "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar",
                 "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
                 "org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar"):
    jar = cache / artifact.rsplit("/", 1)[1]
    if not jar.exists():
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + artifact, jar)
    jars.append(str(jar))

adapters = {
"android/os/Bundle.java": '''package android.os;
public class Bundle extends java.util.HashMap<String,Object>{
 public void putString(String k,String v){put(k,v);} public String getString(String k){return (String)get(k);}
 public void putBundle(String k,Bundle v){put(k,v);} public Bundle getBundle(String k){return (Bundle)get(k);}
}''',
"android/os/SystemClock.java": '''package android.os; public final class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
"android/util/Log.java": '''package android.util; public final class Log {public static int i(String tag,String text){return 0;}}''',
"android/content/ContentValues.java": '''package android.content; public class ContentValues extends java.util.LinkedHashMap<String,Object>{public void put(String k,String v){super.put(k,v);}}''',
"android/database/Cursor.java": '''package android.database;
public final class Cursor implements AutoCloseable{
 private final java.util.List<Object[]> rows=new java.util.ArrayList<>();private int index=-1;
 public Cursor(java.sql.ResultSet result)throws java.sql.SQLException{while(result.next()){Object[] row=new Object[result.getMetaData().getColumnCount()];for(int i=0;i<row.length;i++)row[i]=result.getObject(i+1);rows.add(row);}}
 public boolean moveToFirst(){index=0;return !rows.isEmpty();}public boolean moveToNext(){return ++index<rows.size();}
 public String getString(int i){return (String)rows.get(index)[i];} public long getLong(int i){return ((Number)rows.get(index)[i]).longValue();}
 public void close(){}
}''',
"android/database/sqlite/SQLiteDatabase.java": '''package android.database.sqlite;
import android.content.ContentValues;import android.database.Cursor;import java.sql.*;import java.util.*;
public final class SQLiteDatabase{
 public static final int CONFLICT_IGNORE=4;private final Connection db;private boolean success;
 public SQLiteDatabase(){try{db=DriverManager.getConnection("jdbc:sqlite::memory:");}catch(Exception e){throw new RuntimeException(e);}}
 public void execSQL(String sql){try(var s=db.createStatement()){s.execute(sql);}catch(Exception e){throw new RuntimeException(e);}}
 public Cursor rawQuery(String sql,String[] args){try(var s=db.prepareStatement(sql)){bind(s,args);try(var r=s.executeQuery()){return new Cursor(r);}}catch(Exception e){throw new RuntimeException(e);}}
 private static void bind(PreparedStatement s,Object[] args)throws SQLException{if(args!=null)for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
 public long insertWithOnConflict(String t,String ignored,ContentValues v,int conflict){return insert(t,v,"INSERT OR IGNORE");}
 public long insertOrThrow(String t,String ignored,ContentValues v){return insert(t,v,"INSERT");}
 private long insert(String t,ContentValues v,String prefix){return change(prefix+" INTO "+t+" ("+String.join(",",v.keySet())+") VALUES ("+String.join(",",Collections.nCopies(v.size(),"?"))+")",v.values().toArray());}
 private int change(String sql,Object[] args){try(var s=db.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}catch(Exception e){throw new RuntimeException(e);}}
 public int update(String t,ContentValues v,String where,String[] args){List<Object> all=new ArrayList<>(v.values());if(args!=null)Collections.addAll(all,args);return change("UPDATE "+t+" SET "+String.join(",",v.keySet().stream().map(k->k+"=?").toList())+" WHERE "+where,all.toArray());}
 public int delete(String t,String where,String[] args){return change("DELETE FROM "+t+" WHERE "+where,args);}
 public void beginTransaction(){try{db.setAutoCommit(false);success=false;}catch(Exception e){throw new RuntimeException(e);}}
 public void setTransactionSuccessful(){success=true;}
 public void endTransaction(){try{if(success)db.commit();else db.rollback();db.setAutoCommit(true);}catch(Exception e){throw new RuntimeException(e);}}
}''',
"dev/tripoder/runtime/RuntimeFiles.java": '''package dev.tripoder.runtime;final class RuntimeFiles{static String id(String s){if(s==null||!s.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException();return s;}}''',
"dev/tripoder/runtime/RuntimeStore.java": '''package dev.tripoder.runtime;
final class RuntimeStore implements ExecutionContract.Store{
 final android.database.sqlite.SQLiteDatabase db=new android.database.sqlite.SQLiteDatabase();final java.util.Map<String,ExecutionContract.Operation> operations=new java.util.HashMap<>();
 android.database.sqlite.SQLiteDatabase getReadableDatabase(){return db;}android.database.sqlite.SQLiteDatabase getWritableDatabase(){return db;}
 public ExecutionContract.Operation read(String id){return operations.get(id);}public void write(ExecutionContract.Operation row){operations.put(row.id,row);}
 public java.util.List<ExecutionContract.Operation> incomplete(){return operations.values().stream().filter(r->r.state==ExecutionContract.OperationState.RUNNING||r.state==ExecutionContract.OperationState.ACCEPTED).toList();}
}''',
"dev/tripoder/runtime/ApkStore.java": '''package dev.tripoder.runtime;
final class ApkStore{
 final RuntimeBroker broker;volatile int loads;volatile Runnable loading=()->{};
 ApkStore(RuntimeBroker broker){this.broker=broker;}
 static String textDigest(String s)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 Row row(String space,String pkg){try(var c=broker.store.db.rawQuery("SELECT generation,dataId,state FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){return c.moveToFirst()?new Row(c.getString(0),c.getString(1),c.getString(2)):null;}}
 BundleInstall get(String space,String pkg){if(Thread.holdsLock(broker))throw new AssertionError("APK_LOAD_UNDER_GLOBAL_LOCK");loads++;Row row;synchronized(broker){row=row(space,pkg);}loading.run();return new BundleInstall(row.generation,row.dataId);}
 void evict(String space,String pkg){}android.os.Bundle integrityStats(){return new android.os.Bundle();}
 static final class Row{final String generation,dataId,state;Row(String g,String d,String s){generation=g;dataId=d;state=s;}}
 static final class BundleInstall{final String space="a";final String generation;final java.io.File data;final Info info=new Info();BundleInstall(String g,String d){generation=g;data=new java.io.File(d);}}
 static final class Info{String packageName="owned.app";}
}''',
"dev/tripoder/runtime/RuntimeBroker.java": '''package dev.tripoder.runtime;
final class RuntimeBroker{
 final RuntimeStore store=new RuntimeStore();final ExecutionContract contract=new ExecutionContract(store);final ApkStore apks=new ApkStore(this);
 final Catalog catalog=new Catalog();final Claims hookClaims=new Claims();java.util.List<Slot> processSlots(){return java.util.List.of();}
 RuntimeBroker(){contract.onRecover();store.db.execSQL("CREATE TABLE installs(space TEXT,package TEXT,generation TEXT,dataId TEXT,state TEXT,version INTEGER DEFAULT 1,PRIMARY KEY(space,package))");store.db.execSQL("CREATE TABLE sessions(space,package,installGeneration,processName,processKind,state)");ApplicationProjects.create(store.db);ApplicationProjects.createTargets(store.db);}
 final class Catalog{java.util.List<String> spaces(){try(var c=store.db.rawQuery("SELECT DISTINCT space FROM installs ORDER BY space",null)){var out=new java.util.ArrayList<String>();while(c.moveToNext())out.add(c.getString(0));return out;}}void invalidateProjects(String s,String p){}}
 static final class Claims{void requireUnclaimedBinding(String p,String o){}}static final class Slot{ApkStore.BundleInstall install;}
}'''
}

java_home = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home")
javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
with tempfile.TemporaryDirectory(prefix="tripoder-catalog-test-") as temporary:
    root = Path(temporary)
    sources = []
    for name, text in adapters.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(str(path))
    runtime = repo / "android/runtime/src/dev/tripoder/runtime"
    sources += [str(runtime / name) for name in ("ExecutionContract.java", "ApplicationProjects.java", "ProjectTargetBindings.java")]
    sources += [str(repo / "android/engine/src/dev/tripoder/runtime/RuntimeControllerCatalog.java"),
                str(repo / "android/runtime/test/dev/tripoder/runtime/CatalogMetadataTest.java")]
    classes = root / "classes"
    classes.mkdir()
    subprocess.run([javac, "--release", "17", "-cp", os.pathsep.join(jars), "-d", str(classes), *sources], check=True)
    subprocess.run([java, "-cp", os.pathsep.join([str(classes), *jars]), "dev.tripoder.runtime.CatalogMetadataTest"], check=True)
