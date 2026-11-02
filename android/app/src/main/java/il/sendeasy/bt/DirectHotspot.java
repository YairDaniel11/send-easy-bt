package il.sendeasy.bt;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * רשת ישירה: נקודה חמה פרטית וזמנית (LocalOnlyHotspot, אנדרואיד 8 ומעלה) עם שם וסיסמה אקראיים שהמערכת בוחרת.
 * היא לא נותנת אינטרנט, ונסגרת בסיום ההעברה או אחרי זמן קצוב. המפרט ב-PROTOCOL.md.
 */
@TargetApi(26)
final class DirectHotspot {
    /** כמה זמן נקודה חמה נשארת פתוחה אם הצד השני לא סגר אותה. */
    static final long MAX_OPEN_MS = 3 * 60 * 1000L;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private final Runnable autoStop = new Runnable() {
        @Override public void run() { stop(); }
    };

    DirectHotspot(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /** הרשאה שנדרשת לפתיחת נקודה חמה: מיקום עד אנדרואיד 12, מכשירי וויפי קרובים מאנדרואיד 13. */
    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : android.Manifest.permission.ACCESS_FINE_LOCATION;
    }

    /** פותח את הנקודה החמה (או מחזיר את הפתוחה). חוסם עד 20 שניות. מחזיר {שם רשת, סיסמה}. זורק IOException עם הסבר בעברית. */
    synchronized String[] start() throws IOException {
        if (!supported()) throw new IOException("נקודה חמה זמנית דורשת אנדרואיד 8 ומעלה");
        if (!Perms.has(ctx, permission())) throw new IOException("חסרה הרשאה לפתיחת נקודה חמה (מיקום או מכשירי וויפי קרובים)");
        if (reservation != null) {
            String[] c = credentials(reservation);
            if (c != null) { schedule(); return c; }
            reservation = null;
        }
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) throw new IOException("אין וויפי במכשיר");
        final CountDownLatch done = new CountDownLatch(1);
        final WifiManager.LocalOnlyHotspotReservation[] got = new WifiManager.LocalOnlyHotspotReservation[1];
        final int[] fail = {0};
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    got[0] = r;
                    done.countDown();
                }
                @Override public void onStopped() {
                    reservation = null;
                }
                @Override public void onFailed(int reason) {
                    fail[0] = reason;
                    done.countDown();
                }
            }, main);
        } catch (SecurityException e) {
            throw new IOException("חסרה הרשאה לפתיחת נקודה חמה");
        } catch (IllegalStateException e) {
            throw new IOException("אי אפשר לפתוח נקודה חמה עכשיו (אולי היא כבר פתוחה במכשיר)");
        }
        try {
            if (!done.await(20, TimeUnit.SECONDS)) throw new IOException("פתיחת הנקודה החמה לקחה יותר מדי זמן");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("בוטל");
        }
        if (got[0] == null) {
            throw new IOException(fail[0] == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
                    ? "אי אפשר לפתוח נקודה חמה בזמן ששיתוף אינטרנט או מצב אחר פעילים"
                    : "פתיחת הנקודה החמה נכשלה. ודא שהמיקום מופעל במכשיר");
        }
        String[] c = credentials(got[0]);
        if (c == null) {
            try { got[0].close(); } catch (RuntimeException ignored) { }
            throw new IOException("לא התקבלו פרטי הרשת מהמערכת");
        }
        reservation = got[0];
        schedule();
        return c;
    }

    private void schedule() {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, MAX_OPEN_MS);
    }

    @SuppressWarnings("deprecation")
    private static String[] credentials(WifiManager.LocalOnlyHotspotReservation r) {
        try {
            String ssid, pass;
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration c = r.getSoftApConfiguration();
                ssid = c.getSsid();
                pass = c.getPassphrase();
            } else {
                WifiConfiguration c = r.getWifiConfiguration();
                ssid = c == null ? null : c.SSID;
                pass = c == null ? null : c.preSharedKey;
            }
            ssid = unquote(ssid);
            pass = unquote(pass);
            if (ssid == null || ssid.length() == 0 || pass == null || pass.length() == 0) return null;
            return new String[]{ssid, pass};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') return s.substring(1, s.length() - 1);
        return s;
    }

    synchronized boolean active() {
        return reservation != null;
    }

    /** סוגר את הנקודה החמה. אפשר לקרוא כמה פעמים. */
    synchronized void stop() {
        main.removeCallbacks(autoStop);
        WifiManager.LocalOnlyHotspotReservation r = reservation;
        reservation = null;
        if (r != null) {
            try { r.close(); } catch (RuntimeException ignored) { }
        }
    }
}
