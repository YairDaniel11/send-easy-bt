package il.sendeasy.bt;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** הגדרות האפליקציה. SharedPreferences בלבד, בלי יומן ארוך (ההיסטוריה בקבצים). */
final class Prefs {
    private Prefs() {}

    private static final String NAME = "sendeasy";
    private static final String K_DEVICE_ID = "deviceId";
    private static final String K_RECEIVE = "receive";
    private static final String K_THEME = "theme";
    private static final String K_CHAT_STYLE = "chatStyle";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** מזהה קבוע של המכשיר (16 בייט), נוצר בהפעלה הראשונה. */
    static byte[] deviceId(Context c) {
        SharedPreferences p = sp(c);
        String hex = p.getString(K_DEVICE_ID, null);
        if (hex == null || hex.length() != 32) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            hex = toHex(b);
            p.edit().putString(K_DEVICE_ID, hex).apply();
        }
        return fromHex(hex);
    }

    static boolean receiveEnabled(Context c) {
        return sp(c).getBoolean(K_RECEIVE, true);
    }

    static void setReceiveEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_RECEIVE, on).apply();
    }

    /** האם ההתראה הקבועה של שירות הקבלה מוצגת בסטטוס בר (ברירת מחדל כן). כבוי = מוצגת בצורה שקטה וממוזערת. */
    static boolean showServiceNotification(Context c) {
        return sp(c).getBoolean("showSvcNotif", true);
    }

    static void setShowServiceNotification(Context c, boolean on) {
        sp(c).edit().putBoolean("showSvcNotif", on).apply();
    }

    /** הצגת התראה בעת קבלת הודעה/קובץ (ברירת מחדל כן). */
    static boolean notifyEvents(Context c) {
        return sp(c).getBoolean("notifyEvents", true);
    }

    static void setNotifyEvents(Context c, boolean on) {
        sp(c).edit().putBoolean("notifyEvents", on).apply();
    }

    /** רטט בעת קבלת הודעה (ברירת מחדל כן). */
    static boolean notifyVibrate(Context c) {
        return sp(c).getBoolean("notifyVibrate", true);
    }

    static void setNotifyVibrate(Context c, boolean on) {
        sp(c).edit().putBoolean("notifyVibrate", on).apply();
    }

    /** הדלקת המסך בעת קבלת הודעה (ברירת מחדל לא). */
    static boolean notifyWake(Context c) {
        return sp(c).getBoolean("notifyWake", false);
    }

    static void setNotifyWake(Context c, boolean on) {
        sp(c).edit().putBoolean("notifyWake", on).apply();
    }

    /** צלצול בעת קבלת הודעה (ברירת מחדל כן). */
    static boolean notifySound(Context c) {
        return sp(c).getBoolean("notifySound", true);
    }

    static void setNotifySound(Context c, boolean on) {
        sp(c).edit().putBoolean("notifySound", on).apply();
    }

    /** 0 = לפי המערכת, 1 = בהיר, 2 = כהה */
    static int theme(Context c) {
        return sp(c).getInt(K_THEME, 0);
    }

    static void setTheme(Context c, int t) {
        sp(c).edit().putInt(K_THEME, t).apply();
    }

    /** 0 = ווטסאפ, 1 = גוגל צ'אט, 2 = טקסט פשוט */
    static int chatStyle(Context c) {
        return sp(c).getInt(K_CHAT_STYLE, 0);
    }

    static void setChatStyle(Context c, int s) {
        sp(c).edit().putInt(K_CHAT_STYLE, s).apply();
    }

    /** כינוי מקומי למכשיר מצומד (לפי כתובת). null = אין כינוי. */
    static String alias(Context c, String address) {
        String a = sp(c).getString("alias_" + address, null);
        return a == null || a.trim().length() == 0 ? null : a;
    }

    /** ריק = מחיקת הכינוי. */
    static void setAlias(Context c, String address, String name) {
        String n = name == null ? "" : name.trim();
        SharedPreferences.Editor e = sp(c).edit();
        if (n.length() == 0) e.remove("alias_" + address); else e.putString("alias_" + address, n);
        e.apply();
    }

    /** תיקיית שמירה מותאמת כנתיב רגיל (API 19-28). null = ברירת המחדל. */
    static String saveDir(Context c) {
        String s = sp(c).getString("saveDir", null);
        return s == null || s.length() == 0 ? null : s;
    }

    /** תיקיית שמירה מותאמת כ-treeUri של SAF (API 21+). null = ברירת המחדל. */
    static String saveTree(Context c) {
        String s = sp(c).getString("saveTree", null);
        return s == null || s.length() == 0 ? null : s;
    }

    /** שומר נתיב רגיל (מבטל treeUri). */
    static void setSaveDir(Context c, String path) {
        sp(c).edit().putString("saveDir", path).remove("saveTree").apply();
    }

    /** שומר treeUri (מבטל נתיב רגיל). */
    static void setSaveTree(Context c, String uri) {
        sp(c).edit().putString("saveTree", uri).remove("saveDir").apply();
    }

    static void clearSaveLocation(Context c) {
        sp(c).edit().remove("saveDir").remove("saveTree").apply();
    }

    /** הגרסה האחרונה שעליה כבר נשלחה התראת עדכון (פעם אחת לכל גרסה). */
    static String notifiedUpdate(Context c) {
        return sp(c).getString("updNotified", "");
    }

    static void setNotifiedUpdate(Context c, String v) {
        sp(c).edit().putString("updNotified", v).apply();
    }

    static long lastUpdateCheck(Context c) {
        return sp(c).getLong("updCheck", 0L);
    }

    static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("updCheck", t).apply();
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] fromHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}