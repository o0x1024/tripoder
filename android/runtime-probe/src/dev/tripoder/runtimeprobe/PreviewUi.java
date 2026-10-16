package dev.tripoder.runtimeprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Native preview design system. All measurements are dp, all type is sp. */
final class PreviewUi {
    static final int BG=0xfff5f6fa,INK=0xff1c2638,MUTED=0xff68758c,ACCENT=0xff5363de,TINT=0xffe9edff,LINE=0xffe5e8f1,WHITE=0xffffffff,GREEN=0xff227665;
    static int d(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    static LinearLayout row(Context c){LinearLayout v=new LinearLayout(c);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    static TextView text(Context c,String value,int size,int color,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(d(c,4),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",0));return t;}
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable s=new GradientDrawable();s.setColor(color);s.setCornerRadius(d(c,radius));return s;}
    static void add(LinearLayout p,View v,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=d(p.getContext(),top);p.addView(v,lp);}
    static LinearLayout card(Context c){LinearLayout v=column(c);v.setPadding(d(c,20),d(c,20),d(c,20),d(c,20));v.setBackground(shape(c,WHITE,24));return v;}
    static TextView chip(Context c,String value,int color,int bg){TextView t=text(c,value,11,color,true);t.setPadding(d(c,10),d(c,6),d(c,10),d(c,6));t.setBackground(shape(c,bg,12));return t;}
    static Button button(Context c,String title,boolean primary,Runnable action){Button b=new Button(c);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(d(c,52));b.setMinimumHeight(d(c,52));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(d(c,12),d(c,10),d(c,12),d(c,10));b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff8791a6,primary?WHITE:ACCENT}));StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{-android.R.attr.state_enabled},shape(c,0xffe4e7ef,16));bg.addState(new int[]{},shape(c,primary?ACCENT:TINT,16));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225363de),bg,null));b.setOnClickListener(v->action.run());return b;}
    static View icon(Context c,String name,int color,int bg,int size){ImageView v=new ImageView(c);v.setImageDrawable(new Glyph(name,color));v.setPadding(d(c,10),d(c,10),d(c,10),d(c,10));v.setBackground(shape(c,bg,16));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(d(c,size),d(c,size)));return v;}
    static void section(LinearLayout p,String title,String detail){Context c=p.getContext();LinearLayout r=row(c);r.addView(text(c,title,18,INK,true),new LinearLayout.LayoutParams(0,-2,1));if(detail!=null)r.addView(text(c,detail,12,MUTED,false));add(p,r,26);}
    static LinearLayout entry(Context c,String glyph,String title,String detail,Runnable action){LinearLayout r=row(c);r.setMinimumHeight(d(c,76));r.addView(icon(c,glyph,ACCENT,TINT,46));LinearLayout copy=column(c);add(copy,text(c,title,15,INK,true),0);add(copy,text(c,detail,12,MUTED,false),5);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=d(c,14);lp.rightMargin=d(c,8);r.addView(copy,lp);r.addView(text(c,"›",24,MUTED,false));r.setOnClickListener(v->action.run());r.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,null));return r;}
    static void divider(LinearLayout p){View v=new View(p.getContext());v.setBackgroundColor(LINE);add(p,v,10);v.setLayoutParams(new LinearLayout.LayoutParams(-1,d(p.getContext(),1)));}
    static void insets(View root){root.setOnApplyWindowInsetsListener((v,i)->{var b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);if(v.getWindowInsetsController()!=null)v.getWindowInsetsController().setSystemBarsAppearance(24,24);return i;});}
    static final class Glyph extends Drawable {
        private final String name;private final Paint p=new Paint(3);private final int color;
        Glyph(String name,int color){this.name=name;this.color=color;}
        public void draw(Canvas c){c.save();Rect b=getBounds();float s=Math.min(b.width(),b.height());c.translate(b.left+(b.width()-s)/2,b.top+(b.height()-s)/2);c.scale(s/24,s/24);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "apps":for(int x=0;x<2;x++)for(int y=0;y<2;y++)c.drawRoundRect(3+x*11,3+y*11,10+x*11,10+y*11,2,2,p);break;
                case "spaces":path(c,12,2,22,7,12,12,2,7,12,2);path(c,2,12,12,17,22,12);path(c,2,17,12,22,22,17);break;
                case "records":c.drawRoundRect(4,3,20,22,3,3,p);path(c,8,8,16,8);path(c,8,12,16,12);path(c,8,16,13,16);break;
                case "settings":c.drawCircle(12,12,7,p);c.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,4,p);c.restore();}break;
                case "play":path(c,8,4,20,12,8,20,8,4);break;
                case "shield":path(c,12,2,21,6,20,16,12,22,4,16,3,6,12,2);path(c,8,12,11,15,16,9);break;
                case "plus":path(c,12,5,12,19);path(c,5,12,19,12);break;
                default:path(c,4,19,12,4,20,19,4,19);path(c,8,19,12,11,16,19);
            }c.restore();
        }
        private void path(Canvas c,float...n){Path path=new Path();path.moveTo(n[0],n[1]);for(int i=2;i<n.length;i+=2)path.lineTo(n[i],n[i+1]);c.drawPath(path,p);}
        public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    static final class Orbit extends View {
        final Paint p=new Paint(3);Orbit(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();c.save();c.translate(w*.5f,h*.5f);p.setColor(0x286e80ff);p.setStyle(Paint.Style.FILL);c.drawCircle(0,0,w*.42f,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(getContext(),1));p.setColor(0x757889df);c.drawOval(-w*.45f,-h*.2f,w*.45f,h*.2f,p);c.rotate(-45);c.drawOval(-w*.45f,-h*.2f,w*.45f,h*.2f,p);c.rotate(45);p.setStyle(Paint.Style.FILL);p.setColor(0xffc4ceff);c.drawRoundRect(-w*.18f,-h*.2f,w*.18f,h*.2f,d(getContext(),12),d(getContext(),12),p);p.setColor(0xff6374e9);c.drawRoundRect(-w*.09f,-h*.1f,w*.09f,h*.1f,d(getContext(),5),d(getContext(),5),p);p.setColor(0xffbce9da);c.drawCircle(w*.35f,-h*.13f,d(getContext(),6),p);c.restore();}
    }
}
