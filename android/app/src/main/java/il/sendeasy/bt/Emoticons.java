package il.sendeasy.bt;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * אייקוני רגש. הקוד נשאר טקסט ASCII בפרוטוקול ובהודעות השמורות (תאימות לגרסאות ישנות ולמחשב),
 * ורק בתצוגה מוחלף בציור.
 */
final class Emoticons {
    private Emoticons() {}

    /** אינדקס = סוג האייקון ב-EmoticonDrawable. */
    static final String[] CODES = {":)", ":(", ":+1:", ":-1:", ":pray:"};
    /** סדר בדיקה: הארוכים קודם. */
    private static final int[] ORDER = {4, 2, 3, 0, 1};

    /** ImageSpan שלנו, כדי שאפשר יהיה להסיר רק אותו מהשדה. */
    static final class EmoSpan extends ImageSpan {
        EmoSpan(Drawable d) { super(d, ImageSpan.ALIGN_BOTTOM); }
    }

    private static Drawable drawable(Context c, int type, int sizePx) {
        Drawable d = new EmoticonDrawable(c, type);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** מוצא קודים: מחזיר רשימת {התחלה, סוף, סוג}. לא מחליף בתוך כתובת (מילה שמכילה ://). */
    static List<int[]> find(CharSequence cs) {
        List<int[]> out = new ArrayList<int[]>();
        String s = cs.toString();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) != ':') { i++; continue; }
            int hit = -1;
            for (int t : ORDER) {
                if (s.startsWith(CODES[t], i)) { hit = t; break; }
            }
            if (hit < 0) { i++; continue; }
            int ts = i;
            while (ts > 0 && !Character.isWhitespace(s.charAt(ts - 1))) ts--;
            int te = i;
            while (te < s.length() && !Character.isWhitespace(s.charAt(te))) te++;
            if (s.substring(ts, te).contains("://")) { i++; continue; }
            out.add(new int[]{i, i + CODES[hit].length(), hit});
            i += CODES[hit].length();
        }
        return out;
    }

    /** מחליף כל קוד ב-ImageSpan בגודל ~1.3 מגודל הטקסט. */
    static SpannableString render(Context c, CharSequence text, float textSizePx) {
        SpannableString ss = new SpannableString(text);
        int size = Math.max(1, Math.round(textSizePx * 1.3f));
        for (int[] m : find(text)) {
            ss.setSpan(new EmoSpan(drawable(c, m[2], size)), m[0], m[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** בשדה ההקלדה: מסיר ציורים קודמים ומחשב מחדש. הטקסט עצמו לא משתנה. */
    static void applyToEditable(Context c, Editable e, float textSizePx) {
        EmoSpan[] old = e.getSpans(0, e.length(), EmoSpan.class);
        for (EmoSpan sp : old) e.removeSpan(sp);
        int size = Math.max(1, Math.round(textSizePx * 1.3f));
        for (int[] m : find(e)) {
            e.setSpan(new EmoSpan(drawable(c, m[2], size)), m[0], m[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** מוחק קוד שלם שמסתיים בדיוק ב-pos. true אם נמחק. */
    static boolean deleteEndingAt(Editable e, int pos) {
        for (EmoSpan sp : e.getSpans(0, e.length(), EmoSpan.class)) {
            if (e.getSpanEnd(sp) == pos) {
                e.delete(e.getSpanStart(sp), pos);
                return true;
            }
        }
        return false;
    }

    static Drawable icon(Context c, int type, int sizePx) { return drawable(c, type, sizePx); }
}
