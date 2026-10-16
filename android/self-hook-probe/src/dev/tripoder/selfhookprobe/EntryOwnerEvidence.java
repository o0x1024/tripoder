package dev.tripoder.selfhookprobe;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** One evidence sequence shared by standalone and loaded-class probes. */
final class EntryOwnerEvidence {
    private static void save(File file,byte[] bytes,int max)throws Exception{
        ProbeFiles.require(bytes!=null&&bytes.length>0&&bytes.length<=max,"COMPLETE_ENTRY_IMAGE");
        try(FileOutputStream stream=new FileOutputStream(file)){stream.write(bytes);stream.getFD().sync();}
    }
    static JSONObject run(File directory)throws Exception{
        byte[] before=EntryOwnerProbe.fixturePage(false);ProbeFiles.require(before!=null&&before.length==4096,"ENTRY_PAGE_BEFORE");
        save(new File(directory,"entry-before.bin"),before,4096);
        File trace=new File(directory,"entry-unwind.txt");ProbeFiles.require(trace.createNewFile(),"FRESH_ENTRY_TRACE");JSONObject entry;
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(trace,ParcelFileDescriptor.MODE_WRITE_ONLY)){
            entry=new JSONObject(EntryOwnerProbe.runNative(fd.getFd()));
        }
        ProbeFiles.write(new File(directory,"entry-owner.json"),entry.toString(2)+"\n");
        ProbeFiles.require(entry.getBoolean("passed"),"OWNED_ENTRY_PUBLICATION_FAILED");
        save(new File(directory,"entry-active.bin"),EntryOwnerProbe.fixturePage(true),4096);
        save(new File(directory,"entry-restored.bin"),EntryOwnerProbe.fixturePage(false),4096);
        save(new File(directory,"published-leaf.so"),EntryOwnerProbe.publishedElf(),65536);
        return entry;
    }
}
