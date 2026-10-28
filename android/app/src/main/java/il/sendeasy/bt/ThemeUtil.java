package il.sendeasy.bt;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

/** קובע איזה style להפעיל. בלי AndroidX; "לפי המערכת" נופל לבהיר כשאין מושג Dark Mode. */
final class ThemeUtil {
    private ThemeUtil() {}

    static void applyTheme(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    static boolean isDark(Context c) {
        int pref = Prefs.theme(c);
        if (pref == 1) return false;
        if (pref == 2) return true;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** כל הממשק מימין לשמאל. API 17+, ולכן בטוח מ-minSdk 19. */
    static void forceRtl(Activity a) {
        a.getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }
}