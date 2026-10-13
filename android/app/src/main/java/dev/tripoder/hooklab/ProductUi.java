package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** Shared native components; sizes are dp and text remains in sp. */
final class ProductUi {
    static final int BG=0xfff5f6fa, INK=0xff182339, MUTED=0xff728096, ACCENT=0xff5964dd,
        TINT=0xffeceefe, LINE=0xffe6e9f1, GREEN=0xff178571, WHITE=0xffffffff;
    static int dp(Context c, float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, float radius, Context c) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
    }
    static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static TextView text(Context c,String s,int size,int color,boolean bold) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false); t.setLineSpacing(dp(c,3),1);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t;
    }
    static void add(LinearLayout parent,View child,int top) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(parent.getContext(),top); parent.addView(child,lp);
    }
    static void space(LinearLayout parent,int height) { View v=new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height))); }
    static LinearLayout card(Context c) {
        LinearLayout l=column(c); l.setBackground(shape(WHITE,20,c)); int n=dp(c,18); l.setPadding(n,n,n,n); return l;
    }
    static TextView label(Context c,String value) { TextView t=text(c,value,12,MUTED,true); t.setLetterSpacing(.06f); return t; }
    static TextView chip(Context c,String value,int color,int background) {
        TextView t=text(c,value,11,color,true); t.setPadding(dp(c,9),dp(c,5),dp(c,9),dp(c,5)); t.setBackground(shape(background,20,c)); return t;
    }
    static Button button(Context c,String title,boolean primary,Runnable action) {
        Button b=new Button(c); b.setText(title); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));
        int normal=primary?ACCENT:TINT, fg=primary?WHITE:ACCENT;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff9ca5b7,fg}));
        StateListDrawable states=new StateListDrawable(); states.addState(new int[]{-android.R.attr.state_enabled},shape(0xffe9ecf2,13,c)); states.addState(new int[]{},shape(normal,13,c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225964dd),states,null)); b.setStateListAnimator(null);
        b.setOnClickListener(v->action.run()); return b;
    }
    static void pair(LinearLayout parent,View a,View b) {
        LinearLayout r=row(parent.getContext()); LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1),right=new LinearLayout.LayoutParams(0,-2,1);
        left.rightMargin=dp(parent.getContext(),8); r.addView(a,left); r.addView(b,right); add(parent,r,10);
    }
    static EditText field(LinearLayout parent,String label,String hint,String value,boolean multiline) {
        Context c=parent.getContext(); add(parent,text(c,label,12,MUTED,true),14); EditText e=new EditText(c);
        e.setTextSize(14); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setHint(hint); e.setText(value);
        e.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12)); GradientDrawable bg=shape(BG,12,c); bg.setStroke(dp(c,1),LINE); e.setBackground(bg);
        e.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        e.setSingleLine(!multiline); e.setGravity(Gravity.TOP|Gravity.START); e.setMinHeight(dp(c,48)); if(multiline)e.setMinLines(3);
        add(parent,e,7); return e;
    }
    static LinearLayout screen(Activity activity,String title,String subtitle) {
        LinearLayout root=column(activity); root.setBackgroundColor(BG); UiInsets.apply(root);
        LinearLayout toolbar=row(activity); toolbar.setPadding(dp(activity,12),dp(activity,8),dp(activity,20),dp(activity,8));
        ImageButton back=new ImageButton(activity); back.setImageDrawable(new Icon("back",INK)); back.setContentDescription("返回"); back.setBackground(shape(BG,24,activity)); back.setOnClickListener(v->activity.finish());
        toolbar.addView(back,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48))); TextView name=text(activity,title,18,INK,true); name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);toolbar.addView(name,new LinearLayout.LayoutParams(0,-2,1)); root.addView(toolbar);
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=column(activity); content.setPadding(dp(activity,20),dp(activity,6),dp(activity,20),dp(activity,32));
        if(subtitle!=null)add(content,text(activity,subtitle,13,MUTED,false),0);
        scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); activity.setContentView(root); return content;
    }
    static View icon(Context c,String name,int color,int background,int size) {
        ImageView image=new ImageView(c); image.setImageDrawable(new Icon(name,color)); image.setBackground(shape(background,14,c));
        int pad=dp(c,size>=44?12:6); image.setPadding(pad,pad,pad,pad); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image;
    }
    static LinearLayout entry(Context c,String icon,String title,String detail,Runnable action) {
        LinearLayout r=row(c); r.setMinimumHeight(dp(c,76)); r.setPadding(0,dp(c,8),0,dp(c,8));
        r.addView(icon(c,icon,ACCENT,TINT,44),new LinearLayout.LayoutParams(dp(c,44),dp(c,44)));
        LinearLayout copy=column(c); add(copy,text(c,title,15,INK,true),0); add(copy,text(c,detail,12,MUTED,false),5);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.leftMargin=dp(c,13); lp.rightMargin=dp(c,8); r.addView(copy,lp);
        r.addView(icon(c,"arrow",MUTED,WHITE,24),new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,null)); r.setOnClickListener(v->action.run()); return r;
    }
    static void divider(LinearLayout p) { View v=new View(p.getContext()); v.setBackgroundColor(LINE); p.addView(v,new LinearLayout.LayoutParams(-1,dp(p.getContext(),1))); }
    static void notice(Activity a,String title,String body) { new android.app.AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("知道了",null).show(); }
    static final class Icon extends Drawable {
        final String name; final Paint p=new Paint(3); final int color;
        Icon(String name,int color){this.name=name;this.color=color;}
        public void draw(Canvas original) {
            Canvas c=original; c.save(); android.graphics.Rect b=getBounds(); float size=Math.min(b.width(),b.height());c.translate(b.left+(b.width()-size)/2,b.top+(b.height()-size)/2); c.scale(size/24f,size/24f);
            p.setColor(color); p.setStrokeWidth(1.7f); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch(name) {
                case "grid": for(int x=0;x<2;x++)for(int y=0;y<2;y++)c.drawRoundRect(3+x*11,3+y*11,10+x*11,10+y*11,2,2,p); break;
                case "spark": path(c,12,2,14.8f,9.2f,22,12,14.8f,14.8f,12,22,9.2f,14.8f,2,12,9.2f,9.2f,12,2); break;
                case "tools": c.drawRoundRect(3,6,21,21,3,3,p); c.drawRoundRect(8,2,16,7,2,2,p); c.drawLine(3,12,21,12,p); c.drawLine(10,11,10,14,p); c.drawLine(14,11,14,14,p); break;
                case "settings": c.drawCircle(12,12,7,p); c.drawCircle(12,12,2.5f,p); for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,4,p);c.restore();} break;
                case "arrow": path(c,9,6,15,12,9,18); break;
                case "back": path(c,14,5,7,12,14,19); c.drawLine(7,12,21,12,p); break;
                case "plus": c.drawLine(12,5,12,19,p); c.drawLine(5,12,19,12,p); break;
                case "play": path(c,8,4,20,12,8,20,8,4); break;
                case "file": path(c,6,2,14,2,19,7,19,22,5,22,5,2,6,2); path(c,14,2,14,8,19,8); c.drawLine(8,12,15,12,p); c.drawLine(8,16,15,16,p); break;
                case "shield": path(c,12,2,21,6,20,16,12,22,4,16,3,6,12,2); path(c,8,12,11,15,16,9); break;
                case "code": path(c,7,6,2,12,7,18);path(c,17,6,22,12,17,18);c.drawLine(14,4,10,20,p);break;
                case "gamepad": c.drawRoundRect(2,6,22,19,5,5,p);c.drawLine(5,12,11,12,p);c.drawLine(8,9,8,15,p);c.drawCircle(16,10,1,p);c.drawCircle(19,14,1,p);break;
                case "close": c.drawLine(6,6,18,18,p);c.drawLine(18,6,6,18,p);break;
                default: path(c,12,2,22,20,2,20,12,2);path(c,12,9,17,18,7,18,12,9);break;
            } c.restore();
        }
        private void path(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
        public void setAlpha(int a){p.setAlpha(a);} public void setColorFilter(ColorFilter f){p.setColorFilter(f);} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
