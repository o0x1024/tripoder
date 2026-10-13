package dev.tripoder.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.opengl.GLSurfaceView;
import android.opengl.GLES20;
import android.view.MotionEvent;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class FixtureActivity extends Activity {
    public static int creates, resumes, pauses, clicks, touches;
    public static volatile int frames, glError, pixelGreen;
    public static volatile String glVersion = "";
    private GLSurfaceView surface;
    private TextView result;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        creates++; setContentView(R.layout.guest_screen);
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        result = findViewById(R.id.result);
        findViewById(R.id.action).setOnClickListener(view -> { clicks++; refresh(); });
        surface = new GLSurfaceView(this); surface.setEGLContextClientVersion(2);
        surface.setRenderer(new GLSurfaceView.Renderer() {
            public void onSurfaceCreated(GL10 gl, EGLConfig config) { glVersion = GLES20.glGetString(GLES20.GL_VERSION); }
            public void onSurfaceChanged(GL10 gl, int width, int height) { GLES20.glViewport(0, 0, width, height); }
            public void onDrawFrame(GL10 gl) {
                GLES20.glClearColor(0.08f, 0.65f, 0.4f, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                ByteBuffer pixel = ByteBuffer.allocateDirect(4); GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
                pixelGreen = pixel.get(1) & 255; glError = GLES20.glGetError(); frames++;
            }
        });
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        surface.setOnTouchListener((view, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_UP) { touches++; refresh(); } return true; });
        ((LinearLayout)findViewById(R.id.graphics)).addView(surface, new LinearLayout.LayoutParams(-1, 320));
        refresh();
    }
    private void refresh() { result.setText(GuestProbe.greet("phone") + "\nNative: " + GuestProbe.nativeDelay(3)
        + "\nApplication: " + getApplication().getClass().getName() + "\n点击: " + clicks + " · 触摸: " + touches + "\n设备: " + android.os.Build.MODEL); }
    @Override public void onResume() { super.onResume(); resumes++; if (surface != null) surface.onResume(); }
    @Override public void onPause() { pauses++; if (surface != null) surface.onPause(); super.onPause(); }
}
