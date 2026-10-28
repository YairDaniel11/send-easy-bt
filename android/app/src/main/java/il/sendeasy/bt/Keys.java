package il.sendeasy.bt;

import android.view.KeyEvent;

/**
 * מיפוי מקשים אחד לכל האפליקציה. לא משתמשים ב-CALL/ENDCALL: המערכת תופסת אותם
 * לפני האפליקציה. כן: חצים, אישור, מקשים רכים, תפריט, חזרה וספרות.
 */
final class Keys {
    private Keys() {}

    /** תפריט / אפשרויות */
    static boolean isMenu(int code) {
        return code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_POUND;
    }

    /** חזרה / ביטול חלופי (מקש Back עצמו נשאר למערכת) */
    static boolean isBackAlt(int code) {
        return code == KeyEvent.KEYCODE_SOFT_RIGHT || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_CLEAR;
    }

    /** אישור / בחירה */
    static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }
}