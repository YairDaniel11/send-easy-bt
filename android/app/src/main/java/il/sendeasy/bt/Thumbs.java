package il.sendeasy.bt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** תמונות ממוזערות לצ'אט: פענוח קטן בשרשור רקע, מטמון קטן בזיכרון, בלי ספריות חיצוניות. */
final class Thumbs {
    private Thumbs() {}

    static final int THUMB_WIDTH = 500;

    interface Callback {
        void onThumb(Bitmap b); // בשרשור הראשי; b יכול להיות null (פענוח נכשל)
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String key(File f) { return f.getAbsolutePath() + "|" + f.lastModified(); }

    /** מהמטמון בלבד (בלי פענוח). */
    static Bitmap cached(File f) {
        return CACHE.get(key(f));
    }

    static void load(final File f, final Callback cb) {
        final String k = key(f);
        try {
            POOL.execute(new Runnable() {
                @Override public void run() {
                    Bitmap b = CACHE.get(k);
                    if (b == null) {
                        b = decode(f, THUMB_WIDTH, 0);
                        if (b != null) CACHE.put(k, b);
                    }
                    final Bitmap res = b;
                    MAIN.post(new Runnable() {
                        @Override public void run() { cb.onThumb(res); }
                    });
                }
            });
        } catch (RuntimeException ignored) { }
    }

    /**
     * מפענח בגודל מופחת. minWidth > 0: הרוחב לפחות כך (עד פי 2). maxDim > 0: הצלע הגדולה לכל היותר כך.
     * מחזיר null בכשל, גם ב-OutOfMemoryError (בשקט).
     */
    static Bitmap decode(File f, int minWidth, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            if (minWidth > 0) {
                while (w / (sample * 2) >= minWidth) sample *= 2;
            } else if (maxDim > 0) {
                while (Math.max(w, h) / sample > maxDim) sample *= 2;
            }
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            int deg = exifDegrees(f);
            if (deg != 0) {
                try {
                    Matrix m = new Matrix();
                    m.postRotate(deg);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                } catch (OutOfMemoryError e) {
                    // מציגים בלי סיבוב
                }
            }
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int exifDegrees(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.US);
        if (!(n.endsWith(".jpg") || n.endsWith(".jpeg"))) return 0;
        try {
            int o = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) { }
        return 0;
    }
}
