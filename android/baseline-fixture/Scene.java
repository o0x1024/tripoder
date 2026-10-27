package dev.tripoder.baseline.fixture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The exact same DEX scene runs under native installation and the Level 0 host. */
public final class Scene extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int frame;
    public Scene(Context context){super(context);setBackgroundColor(0xff112233);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setColor(0xffb9e8ff);paint.setTextSize(42);
        canvas.drawText("Tripoder Level 0 fixture",32,100,paint);
        for(int i=0;i<40;i++)canvas.drawRect(24+(i%4)*150,150+(i/4)*90+frame%60,148+(i%4)*150,210+(i/4)*90+frame%60,paint);
        frame++;postInvalidateOnAnimation();
    }
}
