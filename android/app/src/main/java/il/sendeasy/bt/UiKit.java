package il.sendeasy.bt;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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


    /**
     * דיאלוג בחירה מרשימה, בלי המסגרת הכפולה של AlertDialog (Holo מצייר לוח פנימי מתחת לרקע המעוגל).
     * ה-API זהה ל-AlertDialog.Builder במה שנחוץ: setTitle, setItems, show.
     */
    static final class ListDialog {
        private final Activity act;
        private CharSequence title;
        private CharSequence[] items = new CharSequence[0];
        private DialogInterface.OnClickListener listener;

        ListDialog(Activity a) { act = a; }

        ListDialog setTitle(CharSequence t) { title = t; return this; }

        ListDialog setItems(CharSequence[] i, DialogInterface.OnClickListener l) { items = i; listener = l; return this; }

        Dialog show() {
            final Activity a = act;
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final int text = attr(a, R.attr.sbText);
            final int accent = attr(a, R.attr.sbAccent);
            final int row = attr(a, R.attr.sbRow);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int pad = dp(a, 8);
            box.setPadding(pad, pad, pad, pad);

            if (title != null && title.length() > 0) {
                TextView t = new TextView(a);
                t.setText(title);
                t.setTextSize(16);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(attr(a, R.attr.sbTextSecondary));
                t.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
                box.addView(t);
            }
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < items.length; i++) {
                final int which = i;
                TextView r = new TextView(a);
                r.setText(items[i]);
                r.setTextSize(17);
                r.setTextColor(text);
                r.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                r.setMinHeight(dp(a, 52));
                r.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
                r.setFocusable(true);
                r.setClickable(true);
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, rect(a, row, 12, accent, 3));
                bg.addState(new int[]{android.R.attr.state_pressed}, rect(a, row, 12));
                r.setBackgroundDrawable(bg);
                r.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        if (listener != null) listener.onClick(d, which);
                    }
                });
                list.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ScrollView sv = new ScrollView(a);
            sv.addView(list);
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            d.setContentView(box);
            d.setCanceledOnTouchOutside(true);
            d.show();
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(rect(a, attr(a, R.attr.sbBg), 16));
                int width = a.getResources().getDisplayMetrics().widthPixels - dp(a, 32);
                w.setLayout(Math.min(width, dp(a, 420)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            return d;
        }
    }

    /** דיאלוג אישור עם אותה מסגרת אחת של ListDialog (במקום AlertDialog של המערכת עם המסגרת הכפולה). */
    static final class AlertDlg extends Dialog {
        private final android.widget.Button[] buttons = new android.widget.Button[3];

        private final Activity owner;

        AlertDlg(Activity a) { super(a); owner = a; }

        @Override public void show() {
            super.show();
            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(rect(owner, attr(owner, R.attr.sbBg), 16));
                int width = owner.getResources().getDisplayMetrics().widthPixels - dp(owner, 32);
                w.setLayout(Math.min(width, dp(owner, 420)), ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        }

        /** מקבל DialogInterface.BUTTON_POSITIVE / NEGATIVE / NEUTRAL; null אם אין כפתור כזה. */
        android.widget.Button getButton(int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) return buttons[0];
            if (which == DialogInterface.BUTTON_NEGATIVE) return buttons[1];
            if (which == DialogInterface.BUTTON_NEUTRAL) return buttons[2];
            return null;
        }
    }

    /** תחליף ל-AlertDialog.Builder: אותם setTitle/setMessage/setView/כפתורים/create/show. */
    static final class Alert {
        private final Activity act;
        private CharSequence title, message;
        private View view;
        private boolean cancelable = true;
        private DialogInterface.OnCancelListener onCancel;
        private final CharSequence[] labels = new CharSequence[3];
        private final DialogInterface.OnClickListener[] listeners = new DialogInterface.OnClickListener[3];

        Alert(Activity a) { act = a; }

        Alert setTitle(int r) { title = act.getString(r); return this; }
        Alert setTitle(CharSequence t) { title = t; return this; }
        Alert setMessage(int r) { message = act.getString(r); return this; }
        Alert setMessage(CharSequence m) { message = m; return this; }
        Alert setView(View v) { view = v; return this; }
        Alert setCancelable(boolean c) { cancelable = c; return this; }
        Alert setOnCancelListener(DialogInterface.OnCancelListener l) { onCancel = l; return this; }
        Alert setPositiveButton(int r, DialogInterface.OnClickListener l) { return btn(0, act.getString(r), l); }
        Alert setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { return btn(0, t, l); }
        Alert setNegativeButton(int r, DialogInterface.OnClickListener l) { return btn(1, act.getString(r), l); }
        Alert setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { return btn(1, t, l); }
        Alert setNeutralButton(int r, DialogInterface.OnClickListener l) { return btn(2, act.getString(r), l); }
        Alert setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) { return btn(2, t, l); }

        private Alert btn(int i, CharSequence t, DialogInterface.OnClickListener l) {
            labels[i] = t; listeners[i] = l; return this;
        }

        AlertDlg create() {
            final Activity a = act;
            final AlertDlg d = new AlertDlg(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final int text = attr(a, R.attr.sbText);
            final int accent = attr(a, R.attr.sbAccent);
            final int row = attr(a, R.attr.sbRow);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int pad = dp(a, 16);
            box.setPadding(pad, pad, pad, dp(a, 10));
            if (title != null && title.length() > 0) {
                TextView t = new TextView(a);
                t.setText(title);
                t.setTextSize(18);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(text);
                t.setPadding(0, 0, 0, dp(a, 10));
                box.addView(t);
            }
            if (message != null && message.length() > 0) {
                TextView m = new TextView(a);
                m.setText(message);
                m.setTextSize(16);
                m.setTextColor(text);
                m.setPadding(0, dp(a, 2), 0, dp(a, 12));
                box.addView(m);
            }
            if (view != null) {
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                box.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout bar = new LinearLayout(a);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            bar.setPadding(0, dp(a, 8), 0, 0);
            int[] order = {0, 2, 1}; // אישור, ניטרלי, ביטול (מימין לשמאל)
            android.widget.Button first = null;
            for (int k = 0; k < order.length; k++) {
                final int i = order[k];
                if (labels[i] == null) continue;
                android.widget.Button b = new android.widget.Button(a);
                b.setText(labels[i]);
                b.setTextSize(16);
                b.setAllCaps(false);
                b.setTextColor(i == 0 ? 0xFFFFFFFF : text);
                b.setMinHeight(dp(a, 46));
                b.setMinWidth(0);
                b.setFocusable(true);
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, rect(a, i == 0 ? accent : row, 12, text, 3));
                bg.addState(new int[]{android.R.attr.state_pressed}, rect(a, i == 0 ? accent : row, 12, accent, 2));
                bg.addState(new int[0], rect(a, i == 0 ? accent : row, 12));
                b.setBackgroundDrawable(bg);
                final DialogInterface.OnClickListener l = listeners[i];
                final int which = i == 0 ? DialogInterface.BUTTON_POSITIVE : i == 1 ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_NEUTRAL;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        if (l != null) l.onClick(d, which);
                    }
                });
                d.buttons[i] = b;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(a, 4), 0, dp(a, 4), 0);
                bar.addView(b, lp);
                if (first == null) first = b;
            }
            if (first != null) box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView sv = new ScrollView(a);
            sv.addView(box);
            d.setContentView(sv);
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(cancelable);
            if (onCancel != null) d.setOnCancelListener(onCancel);
            return d;
        }

        AlertDlg show() {
            AlertDlg d = create();
            d.show();
            return d;
        }
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

    /**
     * כפתור לדיאלוג מותאם: גובה לפי הכיתוב (עד שתי שורות ויותר), לפחות 48dp, ממוקד, וטבעת פוקוס בולטת.
     * משמש במקום שורת הכפתורים של AlertDialog, שגובהה קבוע ומחתך כיתוב ארוך במסך קטן.
     */
    static TextView actionButton(Context c, CharSequence text, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 42));
        int p = dp(c, 8);
        t.setPadding(p * 2, p, p * 2, p);
        t.setFocusable(true);
        t.setClickable(true);
        int accent = attr(c, R.attr.sbAccent);
        int row = attr(c, R.attr.sbRow);
        t.setTextColor(attr(c, R.attr.sbText));
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rect(c, row, 20, accent, 3));
        sl.addState(new int[]{android.R.attr.state_pressed}, rect(c, row, 20, accent, 3));
        sl.addState(new int[]{}, rect(c, row, 20, 0x44888888, 1));
        t.setBackgroundDrawable(sl);
        t.setOnClickListener(l);
        return t;
    }

    /** מתג הדלקה וכיבוי בעיצוב אחד לכל ערכות הנושא (רק הצבעים משתנים). הנגיעה מטופלת בשורה שמכילה אותו. */
    static final class ToggleView extends View {
        interface Listener { void onChanged(boolean on); }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int onColor, offColor;
        private boolean checked;
        private Listener listener;

        ToggleView(Context c, boolean checked) {
            super(c);
            this.checked = checked;
            onColor = attr(c, R.attr.sbAccent);
            offColor = 0x88888888;
            setFocusable(false);
            setClickable(false);
        }

        void setListener(Listener l) { listener = l; }

        void toggle() {
            checked = !checked;
            invalidate();
            if (listener != null) listener.onChanged(checked);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 46), dp(getContext(), 26));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setColor(checked ? onColor : offColor);
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            paint.setColor(0xFFFFFFFF);
            float r = h / 2 - dp(getContext(), 3);
            float cx = checked ? w - h / 2 : h / 2;
            canvas.drawCircle(cx, h / 2, r, paint);
        }
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

    /** כפתור תפריט (שלוש נקודות) בכותרת: במכשיר מגע אין מקש תפריט פיזי. */
    static IconView addMenuButton(android.app.Activity a, android.view.ViewGroup header, int color, View.OnClickListener l) {
        IconView m = new IconView(a);
        m.setIcon(IconView.MENU);
        m.setIconColor(color);
        m.setClickable(true);
        m.setFocusable(true);
        m.setBackgroundDrawable(UiKit.oval(0x22888888));
        m.setOnClickListener(l);
        header.addView(m, new android.widget.LinearLayout.LayoutParams(UiKit.dp(a, 40), UiKit.dp(a, 40)));
        return m;
    }
}
