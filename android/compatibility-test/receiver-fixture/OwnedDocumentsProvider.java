package dev.tripoder.closurefixture;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;

/** A private publication whose real SDK superclass requires instance configuration. */
public class OwnedDocumentsProvider extends DocumentsProvider {
    public static final class Forged extends OwnedDocumentsProvider {}
    static final class Counts {
        final AtomicInteger constructors=new AtomicInteger(),attachments=new AtomicInteger(),creates=new AtomicInteger();
    }
    static final Counts normal=new Counts(),forged=new Counts();
    static OwnedDocumentsProvider original;
    private final Counts counts;
    public OwnedDocumentsProvider(){counts=getClass()==Forged.class?forged:normal;counts.constructors.incrementAndGet();if(counts==normal)original=this;}
    @Override public void attachInfo(Context context,ProviderInfo info){
        counts.attachments.incrementAndGet();info.grantUriPermissions=true;info.exported=true;
        info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;
        if(counts==forged)info.authority+=".foreign";
        super.attachInfo(context,info);
    }
    @Override public boolean onCreate(){counts.creates.incrementAndGet();return true;}
    @Override public Cursor queryRoots(String[] projection){return new MatrixCursor(projection==null?new String[]{"root_id"}:projection);}
    @Override public Cursor queryDocument(String id,String[] projection){return new MatrixCursor(projection==null?new String[]{"document_id"}:projection);}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String order){return new MatrixCursor(projection==null?new String[]{"document_id"}:projection);}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{throw new FileNotFoundException("OWNED_NO_DOCUMENTS");}
}
