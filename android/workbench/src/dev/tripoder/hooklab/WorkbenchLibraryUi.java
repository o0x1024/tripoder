package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Native library surfaces: edge-to-edge three-column tiles and explicit selection. */
final class WorkbenchLibraryUi {
    static final int BG=0xff28354b,TILE=0xff2e4053,INK=0xfff2f5fa,MUTED=0xffafbdcf,ACCENT=0xff00ade6,LINE=0xff243348;
    static int dp(Context c,int n){return ProductUi.dp(c,n);}
    static TextView text(Context c,String value,int size,int color){return ProductUi.text(c,value,size,color,false);}
    static LinearLayout root(Activity activity){
        LinearLayout root=ProductUi.column(activity);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{var bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            if(view.getWindowInsetsController()!=null)view.getWindowInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);return insets;});
        activity.setContentView(root);return root;
    }
    static TextView action(Context c,String label,Runnable click){TextView view=text(c,label,15,INK);view.setGravity(Gravity.CENTER);view.setMinHeight(dp(c,52));view.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3000ade6),null,null));view.setOnClickListener(v->click.run());view.setFocusable(true);return view;}
    static LinearLayout toolbar(Activity activity,String title,Runnable back){LinearLayout row=ProductUi.row(activity);if(back!=null){TextView button=action(activity,"‹",back);button.setTextSize(36);button.setContentDescription("返回");row.addView(button,new LinearLayout.LayoutParams(dp(activity,56),dp(activity,60)));}
        TextView name=text(activity,title,21,INK);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);if(back==null)name.setPadding(dp(activity,20),0,0,0);row.addView(name,new LinearLayout.LayoutParams(0,dp(activity,60),1));name.setGravity(Gravity.CENTER_VERTICAL);return row;}
    static LinearLayout content(Context c,LinearLayout root){ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);LinearLayout body=ProductUi.column(c);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return body;}
    static void copy(LinearLayout parent,String value){TextView text=text(parent.getContext(),value,13,MUTED);int p=dp(parent.getContext(),20);text.setPadding(p,p,p,p);ProductUi.add(parent,text,0);}
    static TextView primary(Context c,String label,Runnable click){TextView button=action(c,label,click);button.setTextSize(18);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),ProductUi.shape(0xff008dbb,6,c),null));return button;}
    static void grid(LinearLayout parent,java.util.List<? extends View> tiles){Context c=parent.getContext();for(int i=0;i<tiles.size();i+=3){LinearLayout row=ProductUi.row(c);row.setGravity(Gravity.TOP);for(int j=0;j<3;j++){View tile=i+j<tiles.size()?tiles.get(i+j):new View(c);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,i+j<tiles.size()?-2:1,1);if(j<2)lp.rightMargin=dp(c,1);row.addView(tile,lp);}LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(c,1);parent.addView(row,rp);}}
    static final class Tile extends android.widget.FrameLayout {
        final ImageView icon;final TextView name,detail;private final Corner corner;
        Tile(Context c,String title,String subtitle,Drawable drawable,boolean picker,Runnable click){super(c);setMinimumHeight(dp(c,174));setBackground(new RippleDrawable(ColorStateList.valueOf(0x2600ade6),new ColorDrawable(TILE),null));setFocusable(true);
            LinearLayout column=ProductUi.column(c);column.setGravity(Gravity.CENTER);column.setPadding(dp(c,8),dp(c,30),dp(c,8),dp(c,18));
            icon=new ImageView(c);icon.setImageDrawable(drawable==null?new ProductUi.Icon("grid",MUTED):drawable);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);column.addView(icon,new LinearLayout.LayoutParams(dp(c,54),dp(c,54)));
            name=text(c,title,14,INK);name.setGravity(Gravity.CENTER);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);ProductUi.add(column,name,17);
            detail=text(c,subtitle,11,MUTED);detail.setGravity(Gravity.CENTER);detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.END);ProductUi.add(column,detail,10);addView(column,new FrameLayout.LayoutParams(-1,-2));
            corner=new Corner(c);corner.setVisibility(picker?VISIBLE:GONE);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(c,28),dp(c,28),Gravity.TOP|Gravity.END);addView(corner,cp);setOnClickListener(v->click.run());}
        void selection(boolean checked){setSelected(checked);corner.checked=checked;corner.invalidate();setAlpha(isEnabled()?1f:.45f);}
    }
    private static final class Corner extends View {final Paint paint=new Paint(3);boolean checked;Corner(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();paint.setColor(checked?ACCENT:0xff60758a);paint.setStyle(Paint.Style.FILL);Path triangle=new Path();triangle.moveTo(0,0);triangle.lineTo(w,0);triangle.lineTo(w,h);triangle.close();c.drawPath(triangle,paint);paint.setColor(BG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),2));Path tick=new Path();tick.moveTo(w*.48f,h*.28f);tick.lineTo(w*.63f,h*.43f);tick.lineTo(w*.88f,h*.13f);c.drawPath(tick,paint);}}
    private WorkbenchLibraryUi(){}
}
