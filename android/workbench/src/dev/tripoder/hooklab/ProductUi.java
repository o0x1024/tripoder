package dev.tripoder.hooklab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.function.IntConsumer;

/** Shared native components and the one palette every workbench page uses; sizes are dp and text remains in sp. */
final class ProductUi {
    /** Semantic tokens, resolved per page from the system light/dark setting by {@link #theme}. */
    static int BG,SURFACE,INK,MUTED,ACCENT,ON_ACCENT,TINT,LINE,GREEN,DANGER,WARN,WARN_BG,DISABLED,DISABLED_INK;
    static boolean night;
    static{palette(false);}
    private static void palette(boolean dark){
        night=dark;
        if(dark){BG=0xff10151f;SURFACE=0xff1a2130;INK=0xffeef1f7;MUTED=0xffa1acbf;ACCENT=0xff8c95ff;ON_ACCENT=0xff10142e;TINT=0xff272f4d;LINE=0xff2a3345;GREEN=0xff4cc3a3;DANGER=0xffff7a7a;WARN=0xffffc061;WARN_BG=0xff3a2f17;DISABLED=0xff262d3b;DISABLED_INK=0xff6c768a;}
        else{BG=0xfff5f6fa;SURFACE=0xffffffff;INK=0xff182339;MUTED=0xff5d6a80;ACCENT=0xff5964dd;ON_ACCENT=0xffffffff;TINT=0xffeceefe;LINE=0xffe3e7ef;GREEN=0xff137a67;DANGER=0xffc62f3c;WARN=0xff8a5a00;WARN_BG=0xfffff4dc;DISABLED=0xffe9ecf2;DISABLED_INK=0xff8f98aa;}
    }
    /** Call first in onCreate, before super.onCreate, so dialogs and pages follow the same light or dark palette. */
    static void theme(Activity activity){
        palette((activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        activity.setTheme(night?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
    }
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
        LinearLayout l=column(c); GradientDrawable bg=shape(SURFACE,18,c); if(night)bg.setStroke(dp(c,1),LINE); l.setBackground(bg); int n=dp(c,18); l.setPadding(n,n,n,n); return l;
    }
    static TextView label(Context c,String value) { TextView t=text(c,value,12,MUTED,true); t.setLetterSpacing(.06f); return t; }
    static TextView chip(Context c,String value,int color,int background) {
        TextView t=text(c,value,11,color,true); t.setPadding(dp(c,9),dp(c,5),dp(c,9),dp(c,5)); t.setBackground(shape(background,20,c)); return t;
    }
    static Button button(Context c,String title,boolean primary,Runnable action) { return button(c,title,primary?ACCENT:TINT,primary?ON_ACCENT:ACCENT,action); }
    static Button danger(Context c,String title,Runnable action) { return button(c,title,night?0xff3a1f26:0xfffde8ea,DANGER,action); }
    private static Button button(Context c,String title,int normal,int fg,Runnable action) {
        Button b=new Button(c); b.setText(title); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{DISABLED_INK,fg}));
        StateListDrawable states=new StateListDrawable(); states.addState(new int[]{-android.R.attr.state_enabled},shape(DISABLED,13,c)); states.addState(new int[]{},shape(normal,13,c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((ACCENT&0x00ffffff)|0x33000000),states,null)); b.setStateListAnimator(null);
        b.setOnClickListener(v->action.run()); return b;
    }
    /** Borderless text action for secondary commands next to a primary button. */
    static TextView link(Context c,String title,Runnable action){
        TextView t=text(c,title,14,ACCENT,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(c,44));t.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,shape(0xffffffff,10,c)));t.setFocusable(true);t.setOnClickListener(v->action.run());return t;
    }
    static void pair(LinearLayout parent,View a,View b) {
        LinearLayout r=row(parent.getContext()); LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1),right=new LinearLayout.LayoutParams(0,-2,1);
        left.rightMargin=dp(parent.getContext(),8); r.addView(a,left); r.addView(b,right); add(parent,r,10);
    }
    static EditText field(LinearLayout parent,String label,String hint,String value,boolean multiline) {
        Context c=parent.getContext(); add(parent,text(c,label,12,MUTED,true),14); EditText e=input(c,hint,value,multiline); add(parent,e,7); return e;
    }
    static EditText input(Context c,String hint,String value,boolean multiline){
        EditText e=new EditText(c);
        e.setTextSize(14); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setHint(hint); e.setText(value);
        e.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12)); GradientDrawable bg=shape(night?BG:BG,12,c); bg.setStroke(dp(c,1),LINE); e.setBackground(bg);
        e.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        e.setSingleLine(!multiline); e.setGravity(Gravity.TOP|Gravity.START); e.setMinHeight(dp(c,48)); if(multiline)e.setMinLines(3);
        return e;
    }
    /** Source and JSON are typed literally: no suggestions, autocorrect, capitalization, smart quotes or autofill. */
    static EditText code(EditText e){
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);e.setTypeface(Typeface.MONOSPACE);e.setTextSize(13);e.setHorizontallyScrolling(true);
        e.setVerticalScrollBarEnabled(true);e.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);return e;
    }
    /** Page toolbar: optional back, a single-line title and trailing actions. */
    static LinearLayout toolbar(Activity activity,String title,boolean back){
        LinearLayout toolbar=row(activity);toolbar.setPadding(dp(activity,back?6:20),dp(activity,6),dp(activity,8),dp(activity,6));toolbar.setMinimumHeight(dp(activity,60));
        if(back)toolbar.addView(iconButton(activity,"back","返回",activity::finish),new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));
        TextView name=text(activity,title,20,INK,true);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setAccessibilityHeading(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(activity,back?4:0);toolbar.addView(name,lp);return toolbar;
    }
    static TextView title(LinearLayout toolbar){for(int i=0;i<toolbar.getChildCount();i++)if(toolbar.getChildAt(i) instanceof TextView t&&!(t instanceof Button))return t;throw new IllegalStateException("TOOLBAR_TITLE_MISSING");}
    static ImageButton iconButton(Context c,String icon,String description,Runnable action){
        ImageButton b=new ImageButton(c);b.setImageDrawable(new Icon(icon,INK));b.setContentDescription(description);int p=dp(c,12);b.setPadding(p,p,p,p);b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,shape(0xffffffff,24,c)));b.setOnClickListener(v->action.run());return b;
    }
    static View icon(Context c,String name,int color,int background,int size) {
        ImageView image=new ImageView(c); image.setImageDrawable(new Icon(name,color)); image.setBackground(shape(background,14,c));
        int pad=dp(c,size>=44?12:6); image.setPadding(pad,pad,pad,pad); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image;
    }
    static LinearLayout entry(Context c,String icon,String title,String detail,Runnable action) {
        LinearLayout r=row(c); r.setMinimumHeight(dp(c,72)); r.setPadding(dp(c,16),dp(c,10),dp(c,12),dp(c,10));
        r.addView(icon(c,icon,ACCENT,TINT,40),new LinearLayout.LayoutParams(dp(c,40),dp(c,40)));
        LinearLayout copy=column(c); add(copy,text(c,title,15,INK,true),0); if(detail!=null&&!detail.isEmpty())add(copy,text(c,detail,12,MUTED,false),5);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.leftMargin=dp(c,13); lp.rightMargin=dp(c,8); r.addView(copy,lp);
        if(action!=null){r.addView(icon(c,"arrow",MUTED,0,24),new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));r.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,new ColorDrawable(0xffffffff)));r.setFocusable(true);r.setOnClickListener(v->action.run());}
        return r;
    }
    /** Centered explanation with one call to action, used wherever a list is still empty. */
    static LinearLayout empty(Context c,String icon,String title,String body,String action,Runnable click){
        LinearLayout box=column(c);box.setGravity(Gravity.CENTER_HORIZONTAL);int p=dp(c,28);box.setPadding(p,dp(c,36),p,dp(c,24));
        box.addView(icon(c,icon,ACCENT,TINT,56),new LinearLayout.LayoutParams(dp(c,56),dp(c,56)));
        TextView t=text(c,title,17,INK,true);t.setGravity(Gravity.CENTER);add(box,t,16);TextView b=text(c,body,13,MUTED,false);b.setGravity(Gravity.CENTER);add(box,b,8);
        if(action!=null){Button button=button(c,action,true,click);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.topMargin=dp(c,18);box.addView(button,lp);}
        return box;
    }
    static void divider(LinearLayout p) { View v=new View(p.getContext()); v.setBackgroundColor(LINE); p.addView(v,new LinearLayout.LayoutParams(-1,dp(p.getContext(),1))); }
    static void notice(Activity a,String title,String body) { if(a.isFinishing()||a.isDestroyed())return; new AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("知道了",null).show(); }
    /** Selectable body plus a copy action; used for identities and diagnostic details people need to paste elsewhere. */
    static void copyable(Activity a,String title,String body,String copy){
        if(a.isFinishing()||a.isDestroyed())return;
        TextView view=text(a,body,13,INK,false);view.setTextIsSelectable(true);view.setTypeface(Typeface.MONOSPACE);int p=dp(a,22);view.setPadding(p,dp(a,8),p,dp(a,8));ScrollView scroll=new ScrollView(a);scroll.addView(view);
        new AlertDialog.Builder(a).setTitle(title).setView(scroll).setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{
            var clipboard=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title,copy));
            Toast.makeText(a,"已复制",Toast.LENGTH_SHORT).show();
        }).show();
    }
    /** Confirmation for actions that lose data or stop work: the consequence is stated and the action keeps its own verb. */
    static void confirm(Activity a,String title,String message,String action,boolean destructive,Runnable run){
        if(a.isFinishing()||a.isDestroyed())return;
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton(action,(d,w)->run.run()).create();
        dialog.show();if(destructive)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(DANGER);
    }
    /** Segmented tabs; the selected tab is announced as selected, not only colored. */
    static LinearLayout tabs(Context c,String[] names,int selected,IntConsumer select){
        LinearLayout bar=row(c);bar.setBackground(shape(night?SURFACE:0xffe8eaf2,14,c));int p=dp(c,4);bar.setPadding(p,p,p,p);
        for(int i=0;i<names.length;i++){final int index=i;boolean on=i==selected;TextView t=text(c,names[i],14,on?INK:MUTED,on);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(c,40));t.setPadding(dp(c,6),0,dp(c,6),0);
            t.setBackground(on?shape(night?TINT:SURFACE,11,c):null);t.setSelected(on);t.setFocusable(true);t.setOnClickListener(v->select.accept(index));
            t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.TabWidget");info.setSelected(index==selected);}});
            bar.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
        return bar;
    }
    /** Busy indicator and the latest operation message under a page toolbar; hidden when there is nothing to say. */
    static final class Status {
        final LinearLayout view;final ProgressBar progress;final TextView message;
        Status(Activity a){
            view=row(a);view.setPadding(dp(a,20),dp(a,4),dp(a,20),dp(a,8));progress=new ProgressBar(a,null,android.R.attr.progressBarStyleSmall);progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,16),dp(a,16));lp.rightMargin=dp(a,10);view.addView(progress,lp);
            message=text(a,"",12,MUTED,false);message.setMaxLines(3);message.setEllipsize(android.text.TextUtils.TruncateAt.END);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);view.addView(message,new LinearLayout.LayoutParams(0,-2,1));
        }
        void show(String text,boolean busy){message.setText(text);progress.setVisibility(busy?View.VISIBLE:View.GONE);view.setVisibility(text.isEmpty()&&!busy?View.GONE:View.VISIBLE);}
    }
    /** Root column with system-bar insets applied and status bar icons matching the palette. */
    static LinearLayout root(Activity activity){LinearLayout root=column(activity);root.setBackgroundColor(BG);UiInsets.apply(root);activity.setContentView(root);return root;}
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
                case "more": p.setStyle(Paint.Style.FILL);c.drawCircle(12,5,1.8f,p);c.drawCircle(12,12,1.8f,p);c.drawCircle(12,19,1.8f,p);break;
                case "search": c.drawCircle(10.5f,10.5f,6.5f,p);c.drawLine(15.5f,15.5f,21,21,p);break;
                case "check": path(c,5,12.5f,10,17.5f,19,7);break;
                case "folder": path(c,3,6,3,19,21,19,21,8,11,8,9,5,3,5,3,6);break;
                case "box": path(c,12,2,21,7,21,17,12,22,3,17,3,7,12,2);path(c,3,7,12,12,21,7);c.drawLine(12,12,12,22,p);break;
                default: path(c,12,2,22,20,2,20,12,2);path(c,12,9,17,18,7,18,12,9);break;
            } c.restore();
        }
        private void path(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
        public void setAlpha(int a){p.setAlpha(a);} public void setColorFilter(ColorFilter f){p.setColorFilter(f);} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
