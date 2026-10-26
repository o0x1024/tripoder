package dev.tripoder.closurefixture;

/** XML inflation must use the guest resource loader even before any Activity exists. */
public final class OwnedDrawable extends android.graphics.drawable.Drawable {
    @Override public void draw(android.graphics.Canvas canvas){}
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(android.graphics.ColorFilter filter){}
    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSPARENT;}
}
