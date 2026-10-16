package dev.tripoder.p2;

import android.content.Context;
import android.database.sqlite.*;
import java.io.File;
import java.nio.file.Files;

/** Absolute no-backup paths used by Room/WorkManager, exercised through real SQLiteOpenHelper. */
final class DatabasePaths {
    static void run(Storage.Main test,String token,int round)throws Exception{
        File path=new File(test.getNoBackupFilesDir(),"absolute-room.db");
        test.check("sqlite-absolute-path-keeps-install-root",test.getDatabasePath(path.toString()).equals(path),path);
        try(SQLiteOpenHelper helper=new SQLiteOpenHelper(test,path.toString(),null,1){
            @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE owned(id INTEGER PRIMARY KEY, token TEXT)");}
            @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new AssertionError("UNEXPECTED_DATABASE_UPGRADE");}
        }){
            helper.setWriteAheadLoggingEnabled(true);SQLiteDatabase db=helper.getWritableDatabase();
            try(var cursor=db.rawQuery("SELECT token FROM owned WHERE id=1",null)){test.check("sqlite-absolute-retained-and-space-specific",round==0?!cursor.moveToFirst():cursor.moveToFirst()&&token.equals(cursor.getString(0)),round);}
            db.execSQL("INSERT OR REPLACE INTO owned VALUES(1,?)",new Object[]{token});
            try(var cursor=db.rawQuery("PRAGMA journal_mode",null)){cursor.moveToFirst();test.check("sqlite-absolute-real-wal",cursor.getString(0).equals("wal"),cursor.getString(0));}
        }
        String suffix="/no-backup/absolute-room.db";
        for(String input:new String[]{path.getCanonicalPath(),"/data/data/"+test.getPackageName()+suffix,"/data/user/0/"+test.getPackageName()+suffix}){
            test.check("sqlite-owned-absolute-alias",test.getDatabasePath(input).getCanonicalFile().equals(path.getCanonicalFile()),input);
            try(var db=test.openOrCreateDatabase(input,Context.MODE_PRIVATE,null);var cursor=db.rawQuery("SELECT token FROM owned WHERE id=1",null)){test.check("sqlite-owned-absolute-alias-same-bytes",cursor.moveToFirst()&&token.equals(cursor.getString(0)),input);}
        }
        for(String rejected:new String[]{test.getDataDir()+"/no-backup/../escape.db",test.getDataDir().getParent()+"/other-incarnation/escape.db","/data/data/not.this.package/escape.db"}){
            boolean blocked=false;try{test.getDatabasePath(rejected);}catch(SecurityException expected){blocked=true;}test.check("sqlite-absolute-foreign-or-traversal-rejected",blocked,rejected);
        }
        File target=new File(test.getCacheDir(),"database-link-target");Files.writeString(target.toPath(),"unchanged");File link=new File(test.getNoBackupFilesDir(),"linked.db-wal");NativeStorage.rawSymlink(target.toString(),link.toString());
        boolean blocked=false;try{test.getDatabasePath(new File(test.getNoBackupFilesDir(),"linked.db").toString());}catch(SecurityException expected){blocked=true;}
        test.check("sqlite-absolute-companion-symlink-rejected",blocked&&Files.readString(target.toPath()).equals("unchanged"),blocked);Files.delete(link.toPath());
    }
    private DatabasePaths(){}
}
