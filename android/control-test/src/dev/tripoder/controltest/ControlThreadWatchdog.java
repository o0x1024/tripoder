package dev.tripoder.controltest;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Diagnostic snapshot only; never terminates a request or changes its acceptance deadline. */
final class ControlThreadWatchdog implements AutoCloseable {
    private final Thread thread;
    ControlThreadWatchdog(Context context,String run){
        thread=new Thread(()->{
            try{Thread.sleep(60000);}catch(InterruptedException done){return;}
            var stacks=Thread.getAllStackTraces();var threads=new ArrayList<>(stacks.keySet());threads.sort(Comparator.comparingLong(Thread::getId));
            File file=new File(context.getExternalFilesDir(null),run+"-thread-stall.txt");
            try(var out=new PrintWriter(file,StandardCharsets.UTF_8)){
                for(Thread item:threads){out.println("thread="+item.getId()+" name="+item.getName()+" state="+item.getState());for(var frame:stacks.get(item))out.println("  "+frame);}
            }catch(IOException failure){android.util.Log.e("TripoderAcceptance","Thread snapshot failed",failure);}
        },"owned-control-diagnostic");thread.setDaemon(true);thread.start();
    }
    @Override public void close(){thread.interrupt();}
}
