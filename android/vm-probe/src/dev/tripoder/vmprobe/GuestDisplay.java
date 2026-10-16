package dev.tripoder.vmprobe;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Diagnostic framebuffer sampling, not a GPU renderer or an FPS benchmark. */
final class GuestDisplay extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    private Bitmap bitmap;
    GuestDisplay(Context context) { super(context); setBackgroundColor(Color.BLACK); }
    void update(File image) throws IOException {
        Bitmap next;
        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(image))) {
            if (!token(stream).equals("P6")) throw new IOException("Expected P6 framebuffer");
            int width = Integer.parseInt(token(stream)), height = Integer.parseInt(token(stream));
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || !token(stream).equals("255"))
                throw new IOException("Unsupported framebuffer dimensions or format");
            byte[] rgb = stream.readNBytes(width * height * 3);
            if (rgb.length != width * height * 3) throw new EOFException("Incomplete framebuffer");
            int[] pixels = new int[width * height];
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | ((rgb[i * 3] & 255) << 16)
                | ((rgb[i * 3 + 1] & 255) << 8) | (rgb[i * 3 + 2] & 255);
            next = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }
        post(() -> { bitmap = next; invalidate(); });
    }
    private static String token(InputStream input) throws IOException {
        StringBuilder value = new StringBuilder(); int b;
        do { b = input.read(); if (b == -1) throw new EOFException("PPM header"); } while (b <= 32);
        do { value.append((char) b); if (value.length() > 16) throw new IOException("PPM header too long"); b = input.read(); } while (b > 32);
        return value.toString();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        bounds.set((getWidth() - width) / 2, (getHeight() - height) / 2, (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.drawBitmap(bitmap, null, bounds, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || VmRun.current == null || !bounds.contains(event.getX(), event.getY())) return false;
        int x = Math.max(0, Math.min(32767, (int) ((event.getX() - bounds.left) / bounds.width() * 32767)));
        int y = Math.max(0, Math.min(32767, (int) ((event.getY() - bounds.top) / bounds.height() * 32767)));
        boolean down = event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        input.execute(() -> {
            try (Qmp client = new Qmp(VmRun.current.qmp)) {
                JSONArray events = new JSONArray()
                    .put(new JSONObject().put("type", "abs").put("data", new JSONObject().put("axis", "x").put("value", x)))
                    .put(new JSONObject().put("type", "abs").put("data", new JSONObject().put("axis", "y").put("value", y)))
                    .put(new JSONObject().put("type", "btn").put("data", new JSONObject().put("button", "left").put("down", down)));
                client.execute("input-send-event", new JSONObject().put("events", events));
            } catch (Exception error) { android.util.Log.e("TripoderVM", "Guest input failed", error); }
        });
        return true;
    }
    void close() { input.shutdownNow(); }
}
