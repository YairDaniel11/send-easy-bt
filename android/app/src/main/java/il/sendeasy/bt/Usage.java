package il.sendeasy.bt;

import android.content.Context;
import android.content.SharedPreferences;

/** כמה משתמשים בכל מכשיר (קובץ או הודעה שנשלחו). משמש למיון הרשימה: הנפוצים והאחרונים קודם. */
final class Usage {
    private Usage() {}

    /** חצי חיים של ניקוד: שימוש בן 30 יום שווה חצי משימוש של היום. */
    private static final double HALF_LIFE_DAYS = 30;
    private static final double DAY_MS = 24 * 60 * 60 * 1000.0;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("sendeasy_usage", Context.MODE_PRIVATE);
    }

    private static double decayed(double score, long last, long now) {
        double days = Math.max(0, now - last) / DAY_MS;
        return score * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    /** הניקוד הנוכחי של מכשיר (0 = מעולם לא נשלח אליו כלום). */
    static double score(Context c, String address) {
        if (address == null) return 0;
        try {
            String v = sp(c).getString(address, null);
            if (v == null) return 0;
            int bar = v.indexOf('|');
            return decayed(Double.parseDouble(v.substring(0, bar)), Long.parseLong(v.substring(bar + 1)), System.currentTimeMillis());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** רושם שימוש אחד (קובץ או הודעה שנשלחו בהצלחה). */
    static void record(Context c, String address) {
        if (address == null) return;
        long now = System.currentTimeMillis();
        double s = score(c, address) + 1;
        sp(c).edit().putString(address, s + "|" + now).apply();
    }
}
