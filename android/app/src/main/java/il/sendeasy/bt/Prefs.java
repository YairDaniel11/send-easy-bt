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

    /** 0 = לפי המערכת, 1 = בהיר, 2 = כהה */
    static int theme(Context c) {
        return sp(c).getInt(K_THEME, 0);
    }

    static void setTheme(Context c, int t) {
        sp(c).edit().putInt(K_THEME, t).apply();
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