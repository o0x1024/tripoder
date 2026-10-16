package dev.tripoder.p2;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Cursor methods deliberately consult Provider identity after query itself has returned. */
final class ProviderLazyCursor extends AbstractCursor {
    static final AtomicInteger reads=new AtomicInteger(),closes=new AtomicInteger(),wrong=new AtomicInteger(),requeries=new AtomicInteger(),fills=new AtomicInteger();
    private final ContentProvider owner;private final String caller;private final String payload="x".repeat(4096);
    ProviderLazyCursor(ContentProvider owner){this.owner=owner;caller=owner.getCallingPackage();}
    private void verify(){reads.incrementAndGet();if(caller==null||!Objects.equals(caller,owner.getCallingPackage())){wrong.incrementAndGet();throw new SecurityException("LAZY_CURSOR_IDENTITY_MISSING");}}
    @Override public int getCount(){verify();return 2000;}
    @Override public String[] getColumnNames(){verify();return new String[]{"row","payload"};}
    @Override public String getString(int column){verify();return column==0?String.valueOf(mPos):payload+":"+mPos;}
    @Override public short getShort(int column){return (short)getLong(column);}
    @Override public int getInt(int column){return (int)getLong(column);}
    @Override public long getLong(int column){verify();return mPos;}
    @Override public float getFloat(int column){return getLong(column);}
    @Override public double getDouble(int column){return getLong(column);}
    @Override public boolean isNull(int column){verify();return false;}
    @Override public boolean onMove(int oldPosition,int newPosition){verify();return true;}
    @Override public void fillWindow(int position,CursorWindow window){verify();fills.incrementAndGet();super.fillWindow(position,window);}
    @Override public Bundle getExtras(){verify();Bundle b=new Bundle();b.putString("caller",owner.getCallingPackage());return b;}
    @Override public Bundle respond(Bundle request){verify();Bundle b=getExtras();b.putString("echo",request.getString("echo"));return b;}
    @Override public boolean requery(){verify();requeries.incrementAndGet();return super.requery();}
    @Override public void close(){verify();closes.incrementAndGet();super.close();}
    static void run(Context context,ProviderWrites.Check test)throws Exception{
        ContentResolver r=context.getContentResolver();Uri uri=Uri.parse("content://dev.tripoder.p2.write/lazy");
        ContentProviderClient client=r.acquireContentProviderClient(uri);Cursor cursor=client.query(uri,null,null,null,null);client.close();
        try{
            test.check("provider-cursor-survives-explicit-client-close",cursor.getCount()==2000&&cursor.moveToPosition(1999)&&cursor.getString(1).endsWith(":1999"),cursor.getClass().getName());
            test.check("provider-lazy-cursor-window-refill",cursor.moveToPosition(0)&&cursor.getString(1).endsWith(":0")&&cursor.moveToPosition(1500)&&cursor.getString(1).endsWith(":1500"),cursor.getPosition());
            Bundle b=new Bundle();b.putString("echo","lazy-response");Bundle response=cursor.respond(b);test.check("provider-lazy-cursor-respond-identity",context.getPackageName().equals(response.getString("caller"))&&"lazy-response".equals(response.getString("echo")),response.toString());
            test.check("provider-lazy-cursor-requery",cursor.requery()&&cursor.moveToPosition(1000)&&cursor.getString(1).endsWith(":1000"),cursor.getPosition());
        }finally{cursor.close();cursor.close();}
        Bundle counts=r.call(uri,"counts",null,null);test.check("provider-lazy-cursor-scope-and-close-once",counts.getInt("lazyFills")==4&&counts.getInt("lazyReads")>0&&counts.getInt("lazyWrong")==0&&counts.getInt("lazyCloses")==1&&counts.getInt("lazyRequeries")==1,counts.toString());
    }
}
