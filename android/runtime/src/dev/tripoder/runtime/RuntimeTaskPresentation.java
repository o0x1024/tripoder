package dev.tripoder.runtime;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import java.util.IdentityHashMap;
import java.util.Map;

/** Per-process guest identity for the physical task. No APK parsing or broker calls. */
final class RuntimeTaskPresentation {
    private final RuntimeEngine engine;
    private boolean loaded;
    private String label;
    private Bitmap icon;
    private final Map<Bitmap,Bitmap> marked=new IdentityHashMap<>();

    RuntimeTaskPresentation(RuntimeEngine engine){this.engine=engine;}

    void attached(Activity activity){
        try{
            if(!loaded){load();loaded=true;}
            // Set the initial identity before guest onCreate. The setTaskDescription
            // adapter also badges subsequent guest icons without replacing their title.
            // A bitmap is self-contained: guest resource IDs cannot be resolved against
            // the host package by System UI. This constructor also supports API 36.
            activity.setTaskDescription(new ActivityManager.TaskDescription(label,icon));
        }catch(RuntimeException error){
            // A missing display resource must not prevent an otherwise valid launch.
            android.util.Log.w("TripoderActivities","guest-task-presentation",error);
        }
    }

    ActivityManager.TaskDescription decorate(ActivityManager.TaskDescription value){
        if(value==null)return null; // Preserve the framework's null-argument behavior.
        if(!loaded){load();loaded=true;}
        Bitmap supplied=value.getIcon();
        if(supplied==null||supplied.isRecycled())supplied=icon;
        Bitmap badged=marked.get(supplied);
        if(badged==null){
            badged=badge(supplied);
            if(marked.size()>=8)marked.clear();
            marked.put(supplied,badged);marked.put(badged,badged);
        }
        return new BadgedDescription(value,badged);
    }

    /** Activity.setTaskDescription scales getIcon() into its own description before IPC.
     * Copying first preserves every public/hidden task property rather than rebuilding
     * just the title and colors. The original guest description/bitmap is never changed. */
    private static final class BadgedDescription extends ActivityManager.TaskDescription {
        private final Bitmap bitmap;
        BadgedDescription(ActivityManager.TaskDescription source,Bitmap bitmap){super(source);this.bitmap=bitmap;}
        @Override public Bitmap getIcon(){return bitmap;}
    }

    private static Bitmap badge(Bitmap source){
        int size=Math.max(48,Math.min(256,Math.max(source.getWidth(),source.getHeight())));
        Bitmap output=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        Bitmap readable=source.getConfig()==Bitmap.Config.HARDWARE?source.copy(Bitmap.Config.ARGB_8888,false):source;
        try{canvas.drawBitmap(readable,null,new Rect(0,0,size,size),paint);}
        finally{if(readable!=source&&readable!=null)readable.recycle();}
        float diameter=size*.44f,right=size*.94f,bottom=size*.94f,left=right-diameter,top=bottom-diameter,r=diameter*.25f;
        // White separation keeps the Tripoder mark legible on any guest icon.
        paint.setColor(Color.WHITE);canvas.drawRoundRect(new RectF(left-size*.025f,top-size*.025f,right+size*.01f,bottom+size*.01f),r,r,paint);
        paint.setColor(0xff5964dd);canvas.drawRoundRect(new RectF(left,top,right,bottom),r,r,paint);
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(diameter*.065f);
        float inset=diameter*.23f,cell=diameter*.22f,gap=diameter*.1f;
        for(int x=0;x<2;x++)for(int y=0;y<2;y++){
            float px=left+inset+x*(cell+gap),py=top+inset+y*(cell+gap);
            canvas.drawRoundRect(new RectF(px,py,px+cell,py+cell),cell*.25f,cell*.25f,paint);
        }
        return output;
    }

    private void load(){
        label=engine.packageName;
        if(engine.info.nonLocalizedLabel!=null&&!engine.info.nonLocalizedLabel.toString().isBlank())label=engine.info.nonLocalizedLabel.toString();
        else if(engine.info.labelRes!=0)try{String value=engine.resources.getText(engine.info.labelRes).toString();if(!value.isBlank())label=value;}catch(RuntimeException ignored){}
        Drawable drawable=null;
        if(engine.info.icon!=0)try{drawable=engine.resources.getDrawable(engine.info.icon,null);}catch(RuntimeException ignored){}
        if(drawable==null)drawable=engine.host.getPackageManager().getDefaultActivityIcon();
        int size=Math.max(48,Math.min(256,Math.round(48*engine.resources.getDisplayMetrics().density)));
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        Rect bounds=new Rect(drawable.getBounds());
        try{drawable.setBounds(0,0,size,size);drawable.draw(new Canvas(bitmap));icon=bitmap;}
        catch(RuntimeException error){bitmap.recycle();throw error;}
        finally{drawable.setBounds(bounds);}
    }
}
