package il.sendeasy.bt;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * תמונה במסך מלא עם זום, בלי ספריות. מגע: צביטה, גרירה, נגיעה כפולה.
 * מקשים: אישור = התאמה / 2x / 3x, חצים = הזזה כשמוגדל, חזרה = סגירה.
 */
public class ImageViewerActivity extends Activity {
    static final String EXTRA_NAME = "name";

    private ZoomView zoom;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle b) {
        ThemeUtil.applyTheme(this);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) { finish(); return; }
        final File f = new File(Storage.imagesDir(this), new File(name).getName());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        zoom = new ZoomView(this);
        zoom.setFocusable(true);
        root.addView(zoom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        boolean touch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        TextView hint = new TextView(this);
        hint.setText(touch ? R.string.img_viewer_hint_touch : R.string.img_viewer_hint);
        hint.setTextColor(0xFFDDDDDD);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x88000000);
        hint.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 5), UiKit.dp(this, 8), UiKit.dp(this, 5));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.BOTTOM;
        root.addView(hint, hl);

        setContentView(root);
        ThemeUtil.forceRtl(this);
        zoom.requestFocus();

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = Thumbs.decode(f, 0, 2048);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        if (bmp == null) {
                            Toast.makeText(ImageViewerActivity.this, R.string.img_open_failed, Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            zoom.setBitmap(bmp);
                        }
                    }
                });
            }
        }, "SE-Img");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (zoom == null) return super.dispatchKeyEvent(e);
        if (Keys.isBackAlt(code)) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (Keys.isSelect(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) zoom.cycle();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                // חץ ימינה = רואים את הצד הימני של התמונה (התמונה זזה שמאלה)
                int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? -1 : 0;
                int dy = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? -1 : 0;
                zoom.pan(dx, dy);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** תצוגת תמונה עם Matrix: התאמה למסך, זום, גרירה. */
    private static final class ZoomView extends View {
        private Bitmap bmp;
        private final Matrix m = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private float fit = 1f;
        private float sc = 1f, tx, ty;
        private int level; // 0 התאמה, 1 = 2x, 2 = 3x (למקשים)
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomView(Context c) {
            super(c);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomAt(sc * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scaler.isInProgress() || bmp == null) return false;
                    tx -= dx;
                    ty -= dy;
                    clamp();
                    invalidate();
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (sc > fit * 1.05f) { level = 0; setFit(); }
                    else { level = 1; zoomAt(fit * 2.5f, e.getX(), e.getY()); }
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            setFit();
        }

        private void setFit() {
            if (bmp == null || getWidth() == 0) { invalidate(); return; }
            fit = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
            sc = fit;
            tx = (getWidth() - bmp.getWidth() * sc) / 2f;
            ty = (getHeight() - bmp.getHeight() * sc) / 2f;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { setFit(); }

        /** זום סביב נקודה במסך. */
        private void zoomAt(float newSc, float fx, float fy) {
            if (bmp == null) return;
            newSc = Math.max(fit, Math.min(fit * 8f, newSc));
            float r = newSc / sc;
            tx = fx - (fx - tx) * r;
            ty = fy - (fy - ty) * r;
            sc = newSc;
            clamp();
            invalidate();
        }

        private void clamp() {
            float w = bmp.getWidth() * sc, h = bmp.getHeight() * sc;
            if (w <= getWidth()) tx = (getWidth() - w) / 2f; else tx = Math.max(getWidth() - w, Math.min(0, tx));
            if (h <= getHeight()) ty = (getHeight() - h) / 2f; else ty = Math.max(getHeight() - h, Math.min(0, ty));
        }

        /** מקשים: התאמה, 2x, 3x, וחוזר חלילה. */
        void cycle() {
            if (bmp == null) return;
            level = (level + 1) % 3;
            if (level == 0) setFit(); else zoomAt(fit * (level + 1), getWidth() / 2f, getHeight() / 2f);
        }

        /** הזזה בחצים (dx/dy = כיוון תנועת התמונה), רק כשהתמונה מוגדלת. */
        void pan(int dx, int dy) {
            if (bmp == null || sc <= fit * 1.01f) return;
            tx += dx * getWidth() * 0.2f;
            ty += dy * getHeight() * 0.2f;
            clamp();
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null) return true;
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (bmp == null) return;
            m.setScale(sc, sc);
            m.postTranslate(tx, ty);
            canvas.drawBitmap(bmp, m, paint);
        }
    }
}
