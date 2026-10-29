package il.sendeasy.bt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** עזרי עיצוב בקוד (צורות עגולות, אווטארים), כי ?attr בתוך drawable נתמך רק מאנדרואיד 5. */
final class UiKit {
    private UiKit() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int attr(Context c, int attrId) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attrId, tv, true);
        return tv.data;
    }

    static GradientDrawable rect(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable rect(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = rect(c, color, radiusDp);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private static final int[] AVATAR_COLORS = {
            0xFF00897B, 0xFF5E35B1, 0xFFE65100, 0xFF0B57D0, 0xFFC2185B, 0xFF2E7D32, 0xFF6D4C41, 0xFF00838F};

    static int colorFor(String key) {
        int h = key == null ? 0 : key.hashCode();
        return AVATAR_COLORS[(h & 0x7FFFFFFF) % AVATAR_COLORS.length];
    }

    static String initial(String name) {
        if (name == null) return "?";
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) return String.valueOf(ch).toUpperCase();
        }
        return "?";
    }

    /** מחיל אווטאר עגול עם האות הראשונה על TextView. */
    static void avatar(TextView t, String name, int sizeDp) {
        Context c = t.getContext();
        t.setText(initial(name));
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f);
        t.setBackgroundDrawable(oval(colorFor(name)));
        android.view.ViewGroup.LayoutParams lp = t.getLayoutParams();
        if (lp != null) { lp.width = dp(c, sizeDp); lp.height = dp(c, sizeDp); t.setLayoutParams(lp); }
    }

    /** צורת גל קול: עמודות בגבהים קבועים לפי זרע, עם התקדמות צבועה. */
    static final class WaveView extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] heights = new float[28];
        private float progress;

        WaveView(Context c, int seed, int colorOn, int colorOff) {
            super(c);
            on.setColor(colorOn);
            off.setColor(colorOff);
            java.util.Random r = new java.util.Random(seed);
            for (int i = 0; i < heights.length; i++) heights[i] = 0.25f + r.nextFloat() * 0.75f;
        }

        void setProgress(float p) { progress = p; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float step = w / heights.length;
            float bar = step * 0.55f;
            for (int i = 0; i < heights.length; i++) {
                float bh = h * heights[i];
                float x = i * step + (step - bar) / 2;
                RectF rf = new RectF(x, (h - bh) / 2, x + bar, (h + bh) / 2);
                canvas.drawRoundRect(rf, bar / 2, bar / 2, (i / (float) heights.length) < progress ? on : off);
            }
        }
    }
}