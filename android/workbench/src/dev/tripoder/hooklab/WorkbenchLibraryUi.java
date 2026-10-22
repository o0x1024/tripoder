package dev.tripoder.hooklab;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Library surfaces: application tiles in an adaptive grid and the bottom navigation, drawn with the shared palette. */
final class WorkbenchLibraryUi {
    /** Accessibility extra carrying the package of a tile, so tools can find a tile while people hear its name. */
    static final String PACKAGE_EXTRA="dev.tripoder.package";
    static int dp(Context c,int n){return ProductUi.dp(c,n);}
    static TextView text(Context c,String value,int size,int color){return ProductUi.text(c,value,size,color,false);}
    static LinearLayout content(Context c,LinearLayout root){ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);LinearLayout body=ProductUi.column(c);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return body;}
    static void copy(LinearLayout parent,String value){TextView text=text(parent.getContext(),value,13,ProductUi.MUTED);int p=dp(parent.getContext(),20);text.setPadding(p,dp(parent.getContext(),12),p,dp(parent.getContext(),12));ProductUi.add(parent,text,0);}
    static TextView section(LinearLayout parent,String value){TextView t=ProductUi.label(parent.getContext(),value);t.setAccessibilityHeading(true);int p=dp(parent.getContext(),20);t.setPadding(p,dp(parent.getContext(),22),p,dp(parent.getContext(),8));ProductUi.add(parent,t,0);return t;}
    /** Grid columns follow the available width instead of a fixed three. */
    static void grid(LinearLayout parent,java.util.List<? extends View> tiles){
        Context c=parent.getContext();float width=c.getResources().getConfiguration().screenWidthDp;int columns=Math.max(3,Math.min(8,(int)(width/120)));int gap=dp(c,10);
        LinearLayout box=ProductUi.column(c);box.setPadding(dp(c,14),dp(c,4),dp(c,14),dp(c,8));
        for(int i=0;i<tiles.size();i+=columns){LinearLayout row=ProductUi.row(c);row.setGravity(Gravity.TOP);for(int j=0;j<columns;j++){View tile=i+j<tiles.size()?tiles.get(i+j):new View(c);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,i+j<tiles.size()?-2:1,1);if(j<columns-1)lp.rightMargin=gap;row.addView(tile,lp);}
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=gap;box.addView(row,rp);}
        ProductUi.add(parent,box,0);
    }
    /** One bottom navigation destination with an icon and a label; selection is drawn and announced. */
    static LinearLayout navItem(Context c,String icon,String label,boolean selected,Runnable click){
        LinearLayout item=ProductUi.column(c);item.setGravity(Gravity.CENTER);item.setMinimumHeight(dp(c,60));item.setPadding(0,dp(c,6),0,dp(c,6));
        int color=selected?ProductUi.ACCENT:ProductUi.MUTED;ImageView image=new ImageView(c);image.setImageDrawable(new ProductUi.Icon(icon,color));image.setBackground(selected?ProductUi.shape(ProductUi.TINT,14,c):null);int p=dp(c,4);image.setPadding(dp(c,16),p,dp(c,16),p);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(image,new LinearLayout.LayoutParams(dp(c,60),dp(c,30)));TextView name=ProductUi.text(c,label,12,color,selected);name.setGravity(Gravity.CENTER);ProductUi.add(item,name,4);
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),null,null));item.setFocusable(true);item.setSelected(selected);item.setOnClickListener(v->click.run());return item;
    }
    static final class Tile extends android.widget.FrameLayout {
        final ImageView icon;final TextView name,detail;private final Corner corner;private final TextView modeBadge;private final LinearLayout column;private String pkg;
        Tile(Context c,String title,String subtitle,Drawable drawable,boolean picker,Runnable click){super(c);setMinimumHeight(dp(c,picker?132:144));
            GradientDrawable bg=ProductUi.shape(ProductUi.SURFACE,16,c);if(ProductUi.night)bg.setStroke(dp(c,1),ProductUi.LINE);setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),bg,null));setFocusable(true);
            column=ProductUi.column(c);column.setGravity(Gravity.CENTER);column.setPadding(dp(c,8),dp(c,picker?18:34),dp(c,8),dp(c,14));
            icon=new ImageView(c);icon.setImageDrawable(drawable==null?new ProductUi.Icon("grid",ProductUi.MUTED):drawable);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);column.addView(icon,new LinearLayout.LayoutParams(dp(c,50),dp(c,50)));
            name=text(c,title,13,ProductUi.INK);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);ProductUi.add(column,name,12);
            detail=text(c,subtitle,11,ProductUi.MUTED);detail.setGravity(Gravity.CENTER);detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.END);ProductUi.add(column,detail,6);detail.setVisibility(subtitle.isEmpty()?GONE:VISIBLE);addView(column,new FrameLayout.LayoutParams(-1,-2));
            corner=new Corner(c);corner.setVisibility(picker?VISIBLE:GONE);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(c,24),dp(c,24),Gravity.TOP|Gravity.END);cp.setMargins(0,dp(c,8),dp(c,8),0);addView(corner,cp);
            modeBadge=ProductUi.text(c,"",9,ProductUi.ACCENT,true);modeBadge.setSingleLine();modeBadge.setIncludeFontPadding(false);modeBadge.setPadding(dp(c,6),dp(c,3),dp(c,6),dp(c,3));modeBadge.setVisibility(GONE);modeBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);mp.setMargins(dp(c,8),dp(c,8),dp(c,8),0);addView(modeBadge,mp);setOnClickListener(v->click.run());}
        void mode(String value){
            modeBadge.setText(value);modeBadge.setVisibility(value.isEmpty()?GONE:VISIBLE);boolean thick=value.equals("THICK"),mixed=value.contains("/")||value.equals("未知模式");
            modeBadge.setTextColor(mixed?ProductUi.WARN:thick?ProductUi.MUTED:ProductUi.ACCENT);modeBadge.setBackground(ProductUi.shape(mixed?ProductUi.WARN_BG:thick?(ProductUi.night?0xff303646:0xffe9edf3):ProductUi.TINT,8,getContext()));
            int top=Math.max(dp(getContext(),34),modeBadge.getPaint().getFontMetricsInt(null)+modeBadge.getPaddingTop()+modeBadge.getPaddingBottom()+dp(getContext(),16));column.setPadding(column.getPaddingLeft(),top,column.getPaddingRight(),column.getPaddingBottom());
        }
        void detail(String value){detail.setText(value);detail.setVisibility(value.isEmpty()?GONE:VISIBLE);}
        void packageName(String value){pkg=value;}
        void selection(boolean checked){setSelected(checked);corner.checked=checked;corner.invalidate();setAlpha(isEnabled()?1f:.45f);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);if(pkg!=null)info.getExtras().putString(PACKAGE_EXTRA,pkg);if(isLongClickable())info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"管理应用"));if(corner.getVisibility()==VISIBLE){info.setCheckable(true);info.setChecked(corner.checked);}}
    }
    /** Round selection mark in the tile corner: empty ring when unselected, filled check when selected. */
    private static final class Corner extends View {final Paint paint=new Paint(3);boolean checked;Corner(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),r=Math.min(w,h)/2-dp(getContext(),1);paint.setStrokeWidth(dp(getContext(),2));
            if(checked){paint.setStyle(Paint.Style.FILL);paint.setColor(ProductUi.ACCENT);c.drawCircle(w/2,h/2,r,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(ProductUi.ON_ACCENT);Path tick=new Path();tick.moveTo(w*.3f,h*.52f);tick.lineTo(w*.45f,h*.66f);tick.lineTo(w*.72f,h*.36f);c.drawPath(tick,paint);}
            else{paint.setStyle(Paint.Style.STROKE);paint.setColor(ProductUi.MUTED);c.drawCircle(w/2,h/2,r,paint);}}}
    private WorkbenchLibraryUi(){}
}
