package dev.tripoder.closurefixture;

/** Real owned provider: returns process identity and its actual onCreate count. */
public class ProbeProvider extends android.content.ContentProvider {
    private static final java.util.concurrent.atomic.AtomicInteger creates=new java.util.concurrent.atomic.AtomicInteger();
    @Override public boolean onCreate(){creates.incrementAndGet();return true;}
    @Override public android.os.Bundle call(String method,String argument,android.os.Bundle extras){android.os.Bundle out=new android.os.Bundle();out.putInt("pid",android.os.Process.myPid());out.putInt("creates",creates.get());return out;}
    @Override public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] arguments,String order){throw new UnsupportedOperationException();}
    @Override public String getType(android.net.Uri uri){return null;}
    @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(android.net.Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException();}
    @Override public int update(android.net.Uri uri,android.content.ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException();}
}
