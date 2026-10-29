package il.sendeasy.bt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

/**
 * אייקוני רגש: אימוג'י צבעוניים (Noto Emoji, Apache 2.0) מקבצי PNG ב-drawable-nodpi,
 * מצוירים לפי getBounds(). הבסיס נשמר במטמון סטטי קטן (חמישה bitmaps של 128x128).
 */
final class EmoticonDrawable extends Drawable {
    static final int HAPPY = 0, SAD = 1, LIKE = 2, DISLIKE = 3, PRAY = 4;

    private static final int[] RES = {
            R.drawable.emo_happy, R.drawable.emo_sad, R.drawable.emo_like, R.drawable.emo_dislike, R.drawable.emo_thanks};

    private static final SparseArray<Bitmap> CACHE = new SparseArray<Bitmap>();

    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    EmoticonDrawable(Context c, int type) {
        bitmap = load(c.getApplicationContext(), RES[type]);
    }

    private static synchronized Bitmap load(Context c, int res) {
        Bitmap b = CACHE.get(res);
        if (b != null && !b.isRecycled()) return b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false; // בלי שינוי גודל לפי דחיסות המסך: הקובץ 128x128
            b = BitmapFactory.decodeResource(c.getResources(), res, o);
        } catch (OutOfMemoryError e) {
            b = null;
        }
        if (b != null) CACHE.put(res, b);
        return b;
    }

    @Override
    public void draw(Canvas c) {
        if (bitmap == null || getBounds().isEmpty()) return;
        c.drawBitmap(bitmap, null, getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
